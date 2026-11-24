/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ILookPriorityHub;
import baritone.api.behavior.look.ILookRequest;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.look.ForkableRandom;
import baritone.behavior.look.LookProfile;
import baritone.behavior.look.SilentCameraBlend;
import baritone.behavior.look.MicroMotion;
import baritone.behavior.look.SaccadeAimController;
import baritone.pathing.path.PathExecutor;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import baritone.utils.MouseGCD;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public final class LookBehavior extends Behavior implements ILookBehavior {

    /**
     * The current look target, may be {@code null}.
     */
    private Target target;

    /**
     * The priority hub managing rotation requests from external and internal sources.
     */
    private final LookPriorityHub hub;

    /**
     * The rotation known to the server. Returned by {@link #getEffectiveRotation()} for use in {@link IPlayerContext}.
     */
    private Rotation serverRotation;

    /**
     * The last player rotation. Used to restore the player's angle when using free look or silent rotations.
     *
     * @see Settings#freeLook
     */
    private Rotation prevRotation;

    /**
     * Mode applied during PRE state, used to determine restore behavior in POST.
     */
    private Target.Mode lastAppliedMode;

    private final AimProcessor processor;

    /**
     * State for the continuous quantized-space rotation chase. See {@link #smoothRotation}.
     *
     * <p>Design (replaces the old restart-on-target-change smoothstep animation):
     * the camera is modeled as a critically-damped spring chasing a live target, integrated in
     * GCD-step units. Both the position and the velocity are tracked in integer multiples of the
     * mouse-GCD angle step, so every written angle is exactly on the path (no per-frame
     * re-quantization drift → no staircase jitter), and target movements re-target the SAME
     * spring (velocity continuity — no restarts → no velocity discontinuities).
     */
    private QuantizedChase chase;

    /**
     * If the player's actual camera is more than this (degrees) away from where the chase thinks
     * it should be, the chase is reset from the real camera position (free look toggles, external
     * mods, ...).
     */
    private static final double SMOOTH_RESYNC_EPSILON = 5.0;

    /** Ticks a silent rotation keeps easing back to the camera after it ends. */
    private static final int SERVER_RETURN_MAX_TICKS = 20;

    /** Ticks of "nothing to look at" before idle gaze starts drifting. */
    private static final int IDLE_GAZE_DELAY_TICKS = 30;

    /** Profile-driven aim controllers (plan S1-S8) for the visible camera and the silent server rotation. */
    private final SaccadeAimController clientAim;
    private final SaccadeAimController serverAim;

    /** Correlated micro-noise / idle drift source (plan S5, S10, S13). */
    private final MicroMotion micro;

    /** S11: set from the network thread when the server teleports/sets us back. */
    private volatile boolean setbackPending;
    private int setbackTicks;

    private int serverReturnTicks;

    private int idleTicks;
    private boolean idleBlocked;
    private Rotation idleBase;

    /** The camera rotation Baritone wrote last tick (to detect the user taking over during idle gaze). */
    private Rotation lastCameraWrite;
    private Rotation pendingCameraWrite;
    /** Silent->camera blend: camera value to restore at POST while the server leg is still converging. */
    private Rotation cameraOverride;

    /** This tick's planned rotation (see {@link #ensurePlanned}). */
    private boolean planValid;
    private Rotation planned;
    /** Whether movement this tick should use {@link #planned} instead of the raw target. */
    private boolean plannedConsistent;

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
        this.hub = new LookPriorityHub();
        final long seed = System.nanoTime() ^ 0x5DEECE66DL;
        this.clientAim = new SaccadeAimController(seed);
        this.serverAim = new SaccadeAimController(seed * 31);
        this.micro = new MicroMotion(seed * 131);
    }

    @Override
    public ILookPriorityHub getPriorityHub() {
        return this.hub;
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        // GCD-quantize the requested target so the smoothing path, the silent rotation stream and
        // (via PathExecutor) overshoot rotations never contain angles a real mouse couldn't produce.
        final float gcdStep = MouseGCD.step(ctx);
        // remainWithExistingLookDirection's hold-pitch marker (current pitch + 0.0001) must survive
        // quantization: if it collapses into exact equality with the current pitch, nudgeToLevel
        // re-activates and the camera drifts up from steep targets instead of holding — the mining
        // look-drift loop. Re-inject the marker after quantizing.
        final boolean holdPitchMarker = Math.abs(rotation.getPitch()
                - (ctx.playerRotations().getPitch() + 0.0001f)) < 0.00005f;
        // The movement doesn't care about pitch (moveTowards keeps the current pitch): S9 gaze may replace it.
        final boolean pitchFree = !blockInteract
                && Math.abs(rotation.getPitch() - ctx.playerRotations().getPitch()) < 0.00005f;
        rotation = MouseGCD.quantize(rotation, gcdStep);
        if (holdPitchMarker) {
            rotation = new Rotation(rotation.getYaw(), ctx.playerRotations().getPitch() + 0.0001f);
        }
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract, pitchFree);
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.hub.onTick();
            this.processor.tick();
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        switch (event.getState()) {
            case PRE: {
                this.ensurePlanned();
                this.applyPlanned();
                break;
            }
            case POST: {
                // Reset the player's rotations back to their original values ONLY for silent server-side rotations
                if (this.prevRotation != null) {
                    if (this.lastAppliedMode == Target.Mode.SERVER) {
                        Rotation restore = this.cameraOverride != null ? this.cameraOverride : this.prevRotation;
                        ctx.player().setYRot(restore.getYaw());
                        ctx.player().setXRot(restore.getPitch());
                        if (this.cameraOverride != null) {
                            this.pendingCameraWrite = this.cameraOverride;
                        }
                    }
                    this.prevRotation = null;
                }
                this.cameraOverride = null;
                this.lastAppliedMode = null;
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
                if (this.pendingCameraWrite != null) {
                    this.lastCameraWrite = this.pendingCameraWrite;
                    this.pendingCameraWrite = null;
                }
                this.planned = null;
                this.plannedConsistent = false;
                this.planValid = false;
                break;
            }
            default:
                break;
        }
    }

    /**
     * Plans this tick's rotation once. Called from whichever runs first: the movement-direction hook
     * ({@link #onPlayerRotationMove}, during {@code aiStep}) or the PRE player update, so the yaw the player moves
     * with and the yaw sent to the server are the same value (anticheat movement simulation consistency).
     */
    private void ensurePlanned() {
        if (this.planValid) {
            return;
        }
        this.planValid = true;
        this.planned = null;
        this.plannedConsistent = false;
        this.planTick();
    }

    /** Records the rotation to write this tick (GCD-quantized). */
    private void plan(Rotation rotation, float gcdStep, boolean consistent) {
        this.planned = new Rotation(MouseGCD.quantize(rotation.getYaw(), gcdStep), MouseGCD.quantize(rotation.getPitch(), gcdStep));
        this.plannedConsistent = consistent;
    }

    /** Writes the planned rotation at PRE (before the movement packet is sent). */
    private void applyPlanned() {
        if (this.planned == null) {
            return;
        }
        if (this.lastAppliedMode == Target.Mode.SERVER) {
            this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        }
        ctx.player().setYRot(this.planned.getYaw());
        ctx.player().setXRot(this.planned.getPitch());
        if (this.lastAppliedMode == Target.Mode.CLIENT) {
            this.pendingCameraWrite = this.planned;
        }
    }

    /**
     * PRE player update: arbitrate the winning rotation (hub requests vs. Baritone's own target, plan S15),
     * then apply it through the active smoothing profile. Runs after {@code AbstractClientPlayer.tick()}, i.e.
     * after the entity's {@code yRotO/xRotO} were latched for this tick, so vanilla's partial-tick camera lerp
     * renders every write smoothly per frame instead of stair-stepping at 20 Hz (plan S2).
     */
    private void planTick() {
        final Settings settings = Baritone.settings();
        final LookProfile profile = LookProfile.parse(settings.lookProfile.value);

        // S11: ease out of server corrections instead of whipping straight back to the target.
        final int easeTicks = Math.max(0, settings.lookSetbackEaseTicks.value);
        if (this.setbackPending) {
            this.setbackPending = false;
            this.setbackTicks = easeTicks;
            this.chase = null;
            this.clientAim.invalidate();
            this.serverAim.invalidate();
            this.serverReturnTicks = 0;
        }
        final double turnScale = this.setbackTicks > 0 && easeTicks > 0
                ? 0.25 + 0.75 * (1.0 - (double) this.setbackTicks / easeTicks)
                : 1.0;
        if (this.setbackTicks > 0) {
            this.setbackTicks--;
        }

        // S13: micro-motion phase is stride-coupled
        final double walked = Math.hypot(ctx.player().getX() - ctx.player().xo, ctx.player().getZ() - ctx.player().zo);
        this.micro.tick(walked);

        // Resolve active look request (highest priority between external hub requests and Baritone internal target)
        Optional<ILookRequest> highestExternal = this.hub.getHighestPriorityRequest();

        Rotation targetRotation = null;
        Target.Mode targetMode = Target.Mode.NONE;
        boolean blockInteract = false;
        boolean pitchFree = false;
        Boolean customSmooth = null;

        if (highestExternal.isPresent() && (this.target == null || highestExternal.get().getPriority() > ILookPriorityHub.DEFAULT_BARITONE_PRIORITY)) {
            ILookRequest req = highestExternal.get();
            targetRotation = req.getRotation();
            if (targetRotation != null) {
                blockInteract = req.isBlockInteract();
                customSmooth = req.isSmooth();
                if (req.isCamera()) {
                    targetMode = Target.Mode.CLIENT;
                } else if (req.isSilent()) {
                    targetMode = Target.Mode.SERVER;
                } else {
                    targetMode = Target.Mode.resolve(ctx, blockInteract);
                }
            }
        }

        if (targetRotation == null && this.target != null) {
            targetRotation = this.target.rotation;
            targetMode = this.target.mode;
            blockInteract = this.target.blockInteract;
            pitchFree = this.target.pitchFree;
        }

        final boolean flying = ctx.player().isFallFlying();
        final boolean legacy = profile.isLegacy() || flying;

        if (targetRotation == null || targetMode == Target.Mode.NONE) {
            this.chase = null;
            if (targetRotation == null && !legacy && this.tryServerReturn(settings, profile, turnScale)) {
                this.clientAim.invalidate();
                this.idleTicks = 0;
                return;
            }
            this.serverAim.invalidate();
            this.serverReturnTicks = 0;
            if (targetRotation == null && !legacy) {
                if (this.tryIdleGaze(settings, profile, turnScale)) {
                    return;
                }
            } else {
                this.idleTicks = 0;
            }
            if (this.idleTicks == 0) {
                // Not idling (or idle gaze not allowed): the camera controller restarts from the camera next time
                this.clientAim.invalidate();
            }
            this.lastAppliedMode = targetMode;
            return;
        }
        this.idleTicks = 0;

        // Pathing/movement yaw snap: walk with the exact requested heading so
        // MOVE_FORWARD cannot leave a block while a saccade is still turning.
        // Combat/hub requests, block interacts, elytra, and idle gaze stay smoothed.
        final boolean hubWins = highestExternal.isPresent()
                && (this.target == null || highestExternal.get().getPriority() > ILookPriorityHub.DEFAULT_BARITONE_PRIORITY);
        final boolean pathingSnap = settings.pathingSnapMovementYaw.value
                && !hubWins
                && !blockInteract
                && !flying
                && this.target != null;
        if (pathingSnap) {
            this.lastAppliedMode = targetMode;
            Rotation snapped = this.processor.peekRotation(targetRotation, true);
            final float snapGcd = MouseGCD.step(ctx);
            this.clientAim.invalidate();
            this.serverAim.invalidate();
            this.chase = null;
            this.plan(snapped, snapGcd, true);
            if (targetMode == Target.Mode.SERVER) {
                this.serverReturnTicks = SERVER_RETURN_MAX_TICKS;
            }
            return;
        }

        // S9: look at the ground a few blocks ahead on the path when the movement doesn't care about pitch
        boolean gazeApplied = false;
        if (pitchFree && !blockInteract && !legacy && profile.gaze && settings.lookPathGaze.value) {
            final double gaze = this.pathGazePitch(walked);
            if (!Double.isNaN(gaze)) {
                targetRotation = new Rotation(targetRotation.getYaw(), (float) gaze);
                gazeApplied = true;
            }
        }

        this.lastAppliedMode = targetMode;
        Rotation actual = this.processor.peekRotation(targetRotation, blockInteract || gazeApplied);

        // S5 + S12: correlated micro-noise, never on block interaction and never while the hurt tilt plays
        if (!blockInteract && !legacy && settings.lookMicroNoise.value && profile.noiseAmplitude > 0
                && ctx.player().hurtTime <= 0) {
            actual = new Rotation(
                    (float) (actual.getYaw() + this.micro.noiseYaw(profile.noiseAmplitude)),
                    (float) Math.max(-90.0, Math.min(90.0, actual.getPitch() + this.micro.noisePitch(profile.noiseAmplitude)))
            );
        }

        final float gcdStep = MouseGCD.step(ctx);
        if (targetMode == Target.Mode.SERVER) {
            this.clientAim.invalidate();
            this.chase = null;
            boolean smoothServer = customSmooth != null ? customSmooth : settings.smoothSilentLook.value;
            if (smoothServer && !legacy) {
                // Silent rotations are exactly what anticheat sees: smooth them with the same profile.
                Rotation from = this.serverRotation != null ? this.serverRotation
                        : new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                Rotation smoothed = this.stepAim(this.serverAim, from, actual, profile,
                        this.maxTurn(settings, blockInteract) * turnScale, blockInteract, gcdStep);
                this.plan(smoothed, gcdStep, true);
                this.serverReturnTicks = SERVER_RETURN_MAX_TICKS;
            } else {
                // GCD-quantize so the silent rotation stream only contains angles reachable by
                // real mouse input at the player's current sensitivity.
                this.serverAim.invalidate();
                this.plan(actual, gcdStep, false);
            }
        } else if (targetMode == Target.Mode.CLIENT) {
            if (this.trySilentToCameraBlend(settings, profile, actual, blockInteract, turnScale, gcdStep, legacy, customSmooth)) {
                return;
            }
            this.serverAim.invalidate();
            this.serverReturnTicks = 0;
            boolean useSmooth;
            if (customSmooth != null) {
                useSmooth = customSmooth;
            } else {
                useSmooth = flying
                        ? settings.elytraSmoothLook.value
                        : settings.smoothLook.value;
            }

            if (useSmooth) {
                Rotation current = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                double maxTurn = this.maxTurn(settings, blockInteract) * turnScale;
                Rotation smoothed;
                if (legacy) {
                    this.clientAim.invalidate();
                    // Continuous quantized-space chase: critically-damped spring toward a live
                    // target, integrated in GCD-step units — applied camera positions always
                    // lie on the path, and re-targets preserve velocity (no restarts, no jitter).
                    smoothed = this.smoothRotation(current, actual, (float) maxTurn, settings.smoothLookTicks.value);
                } else {
                    this.chase = null;
                    smoothed = this.stepAim(this.clientAim, current, actual, profile, maxTurn, blockInteract, gcdStep);
                }
                this.plan(smoothed, gcdStep, !legacy);
            } else {
                this.clientAim.invalidate();
                this.chase = null;
                this.plan(actual, gcdStep, false);
            }
        }
    }

    /**
     * Silent -> camera hand-off: while the server rotation is still away from the camera, step the server leg
     * (sent this tick) and the camera leg (restored at POST) toward the new target separately until they meet.
     */
    private boolean trySilentToCameraBlend(Settings settings, LookProfile profile, Rotation actual, boolean blockInteract,
                                           double turnScale, float gcdStep, boolean legacy, Boolean customSmooth) {
        if (legacy || !settings.lookSilentToCameraBlend.value || this.serverReturnTicks <= 0
                || !this.serverAim.isInitialized() || this.serverRotation == null) {
            return false;
        }
        final Rotation camera = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        if (!SilentCameraBlend.needed(this.serverRotation.getYaw(), this.serverRotation.getPitch(),
                camera.getYaw(), camera.getPitch(), gcdStep)) {
            return false;
        }
        this.serverReturnTicks--;
        final double maxTurn = this.maxTurn(settings, blockInteract) * turnScale;
        final Rotation server = this.stepAim(this.serverAim, this.serverRotation, actual, profile, maxTurn, blockInteract, gcdStep);
        final boolean smooth = customSmooth != null ? customSmooth : settings.smoothLook.value;
        final Rotation cam = smooth ? this.stepAim(this.clientAim, camera, actual, profile, maxTurn, blockInteract, gcdStep) : actual;
        if (SilentCameraBlend.converged(server.getYaw(), server.getPitch(), cam.getYaw(), cam.getPitch(), gcdStep)) {
            this.serverReturnTicks = 0;
            this.serverAim.invalidate();
            return false; // legs met: plain camera mode from here (clientAim already continues from the camera)
        }
        this.plan(server, gcdStep, true);
        this.cameraOverride = new Rotation(MouseGCD.quantize(cam.getYaw(), gcdStep), MouseGCD.quantize(cam.getPitch(), gcdStep));
        this.lastAppliedMode = Target.Mode.SERVER;
        return true;
    }

    private double maxTurn(Settings settings, boolean blockInteract) {
        double maxTurn = settings.maxLookTurnSpeed.value;
        if (blockInteract) {
            maxTurn = Math.max(maxTurn, 65.0);
        }
        return maxTurn;
    }

    /**
     * Advances a profile-driven aim controller one tick (S1/S3/S4/S6/S7/S8). If the real rotation was displaced
     * externally (mouse, other mod, teleport) the controller restarts from where the rotation actually is.
     */
    private Rotation stepAim(SaccadeAimController aim, Rotation current, Rotation target, LookProfile profile,
                             double maxTurn, boolean precise, float gcdStep) {
        if (!aim.isInitialized()
                || rotationDistance(new Rotation((float) aim.yaw(), (float) aim.pitch()), current) > SMOOTH_RESYNC_EPSILON) {
            aim.reset(current.getYaw(), current.getPitch());
        }
        aim.setSnapEpsilon(gcdStep * 0.5);
        aim.step(target.getYaw(), target.getPitch(), profile, maxTurn, precise);
        return new Rotation((float) aim.yaw(), (float) aim.pitch());
    }

    /**
     * After a smoothed silent rotation ends, keep easing the server-side rotation back to the camera for a few
     * ticks instead of letting the next movement packet snap straight to it.
     */
    private boolean tryServerReturn(Settings settings, LookProfile profile, double turnScale) {
        if (this.serverReturnTicks <= 0 || !this.serverAim.isInitialized() || !settings.smoothSilentLook.value) {
            return false;
        }
        this.serverReturnTicks--;
        final Rotation camera = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        final float gcdStep = MouseGCD.step(ctx);
        final Rotation from = this.serverRotation != null ? this.serverRotation : camera;
        final Rotation smoothed = this.stepAim(this.serverAim, from, camera, profile,
                this.maxTurn(settings, false) * turnScale, false, gcdStep);
        if (rotationDistance(smoothed, camera) <= gcdStep) {
            this.serverReturnTicks = 0;
            this.serverAim.invalidate();
            this.lastAppliedMode = Target.Mode.NONE;
            return true;
        }
        this.plan(smoothed, gcdStep, true);
        this.lastAppliedMode = Target.Mode.SERVER;
        return true;
    }

    /**
     * S10 idle gaze: while a Baritone process is in control but nothing asked for a rotation this tick, drift the
     * camera slowly around where it was instead of freezing it. Never with free look (the camera is the user's),
     * never with a screen open, and it stops for the rest of the idle period as soon as the user moves the mouse.
     */
    private boolean tryIdleGaze(Settings settings, LookProfile profile, double turnScale) {
        if (!profile.gaze || !settings.lookIdleGaze.value || settings.freeLook.value
                || ctx.minecraft().gui.screen() != null
                || !this.baritone.getPathingControlManager().mostRecentInControl().isPresent()) {
            this.idleTicks = 0;
            return false;
        }
        final Rotation camera = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        if (this.idleTicks == 0) {
            this.idleBlocked = false;
        }
        if (this.idleTicks < IDLE_GAZE_DELAY_TICKS) {
            // Waiting: keep following the camera so the drift starts from wherever it is when it kicks in
            this.idleBase = camera;
            this.idleTicks++;
            if (this.idleTicks == IDLE_GAZE_DELAY_TICKS) {
                this.clientAim.reset(camera.getYaw(), camera.getPitch());
                this.lastCameraWrite = camera;
            }
            return false;
        }
        if (this.idleBlocked || (this.lastCameraWrite != null && rotationDistance(this.lastCameraWrite, camera) > 0.01)) {
            this.idleBlocked = true; // the user (or another mod) moved the camera: hands off until idle restarts
            return false;
        }
        final float gcdStep = MouseGCD.step(ctx);
        final Rotation target = new Rotation(
                (float) (this.idleBase.getYaw() + this.micro.idleYaw()),
                (float) Math.max(-90.0, Math.min(90.0, this.idleBase.getPitch() + this.micro.idlePitch())));
        final Rotation smoothed = this.stepAim(this.clientAim, camera, target, profile,
                Math.min(6.0, this.maxTurn(settings, false)) * turnScale, false, gcdStep);
        this.plan(smoothed, gcdStep, true);
        this.lastAppliedMode = Target.Mode.CLIENT;
        return true;
    }

    /**
     * S9 + S13: pitch that looks at the path's ground a speed-dependent distance ahead, or NaN when not pathing.
     */
    private double pathGazePitch(double speed) {
        final PathExecutor current = this.baritone.getPathingBehavior().getCurrent();
        if (current == null) {
            return Double.NaN;
        }
        final List<BetterBlockPos> positions = current.getPath().positions();
        final int index = current.getPosition();
        if (index < 0 || index >= positions.size() - 1) {
            return Double.NaN;
        }
        final double want = MicroMotion.gazeDistance(speed);
        final double eyeX = ctx.player().getX();
        final double eyeY = ctx.player().getEyeY();
        final double eyeZ = ctx.player().getZ();
        BetterBlockPos node = null;
        double horizontal = 0;
        final int last = Math.min(positions.size() - 1, index + 16);
        for (int i = index + 1; i <= last; i++) {
            node = positions.get(i);
            horizontal = Math.hypot(node.x + 0.5 - eyeX, node.z + 0.5 - eyeZ);
            if (horizontal >= want) {
                break;
            }
        }
        if (node == null || horizontal < 1.0) {
            return Double.NaN;
        }
        return MicroMotion.gazePitch(eyeY, node.y, horizontal);
    }

    /** S11: called from the network thread, consumed on the next PRE. */
    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            this.setbackPending = true;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            this.serverRotation = new Rotation(packet.getYRot(0.0f), packet.getXRot(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.target = null;
        this.hub.clear();
        this.chase = null;
        this.clientAim.invalidate();
        this.serverAim.invalidate();
        this.serverReturnTicks = 0;
        this.idleTicks = 0;
        this.lastCameraWrite = null;
        this.planValid = false;
        this.planned = null;
    }

    private Rotation getWinningRotation() {
        Optional<ILookRequest> highestExternal = this.hub.getHighestPriorityRequest();
        if (highestExternal.isPresent() && (this.target == null || highestExternal.get().getPriority() > ILookPriorityHub.DEFAULT_BARITONE_PRIORITY)) {
            Rotation rot = highestExternal.get().getRotation();
            if (rot != null) {
                return rot;
            }
        }
        if (this.target != null) {
            return this.target.rotation;
        }
        return null;
    }

    /**
     * Advances the continuous quantized-space chase by one tick and returns the next rotation to
     * apply.
     *
     * <p>The camera is modeled as a critically-damped spring chasing a live target. Unlike the
     * previous restart-on-target-change smoothstep animation, target movements simply move the
     * spring's set point — position and velocity are continuous across re-targets, which is why
     * the motion no longer jitters when pathing re-submits slightly different targets every tick.
     *
     * <p>All integration happens in GCD-step units (integer multiples of the smallest rotation
     * increment reachable at the player's mouse sensitivity). Every angle this method returns is
     * therefore exactly a GCD multiple — quantization is the coordinate system, not a post-hoc
     * rounding step — which removes the staircase drift the old per-frame quantization caused.
     *
     * <p>Peak turn rate stays capped at {@code maxTurn} degrees per tick by clamping the spring's
     * velocity.
     *
     * @param current        The player's current rotation (already GCD-quantized)
     * @param target         The rotation the arbitration winner wants
     * @param maxTurn        Peak allowed turn rate in degrees per tick
     * @param smoothingTicks The {@code smoothLookTicks} setting — the spring time-constant in
     *                       ticks; higher = smoother but lazier
     * @return The next rotation on the chase path (already GCD-quantized)
     */
    private Rotation smoothRotation(Rotation current, Rotation target, float maxTurn, int smoothingTicks) {
        final float gcdStep = MouseGCD.step(ctx);

        // External camera displacement (free look toggles, other mods): reset the chase from
        // where the camera actually is instead of fighting it.
        if (this.chase != null
                && rotationDistance(this.chase.positionRotation(gcdStep), current) > SMOOTH_RESYNC_EPSILON) {
            this.chase = null;
        }

        if (this.chase == null) {
            this.chase = new QuantizedChase(current, target, gcdStep, maxTurn, smoothingTicks);
        } else {
            this.chase.retarget(target, gcdStep, maxTurn, smoothingTicks);
        }

        return this.chase.step(gcdStep, maxTurn);
    }

    /**
     * Critically-damped spring chasing a live rotation target, integrated in GCD-step units.
     *
     * <p>State: position {@code pos} and velocity {@code vel} per axis (yaw/pitch), both in
     * GCD steps. Each {@link #step} integrates the spring for one tick:
     * <pre>
     *     acc = (target - pos) / tau^2 - 2 * vel / tau     (critically damped, tau = time constant)
     *     vel = clamp(vel + acc, maxVel)                   (peak turn-rate cap)
     *     pos = pos + vel
     * </pre>
     * The integration is semi-implicit Euler, which is stable for the tau values used here.
     * Settling: when |target - pos| &lt; 1 step and |vel| &lt; 1 step, the chase snaps to the
     * target and deactivates.
     */
    private static final class QuantizedChase {
        private double yawPos, yawVel, pitchPos, pitchVel; // in GCD steps
        private double yawTarget, pitchTarget;             // in GCD steps
        private final double tau;                          // time constant in ticks

        QuantizedChase(Rotation current, Rotation target, float gcdStep, float maxTurn, int smoothingTicks) {
            this.tau = Math.max(1.0, smoothingTicks / 3.0); // 5 ticks -> tau ~1.67: responsive but eased
            this.yawPos = current.getYaw() / gcdStep;
            this.pitchPos = current.getPitch() / gcdStep;
            float wrappedYawTarget = current.getYaw() + net.minecraft.util.Mth.wrapDegrees(target.getYaw() - current.getYaw());
            this.yawTarget = wrappedYawTarget / gcdStep;
            this.pitchTarget = target.getPitch() / gcdStep;
            this.yawVel = 0;
            this.pitchVel = 0;
        }

        /** Moves the set point; the spring state (position + velocity) is preserved. */
        void retarget(Rotation target, float gcdStep, float maxTurn, int smoothingTicks) {
            float currentYaw = (float) (this.yawPos * gcdStep);
            float wrappedYawTarget = currentYaw + net.minecraft.util.Mth.wrapDegrees(target.getYaw() - currentYaw);
            this.yawTarget = wrappedYawTarget / gcdStep;
            this.pitchTarget = target.getPitch() / gcdStep;
        }

        /** Integrates one tick and returns the new position as a (quantized) rotation. */
        Rotation step(float gcdStep, float maxTurn) {
            final double maxVel = maxTurn / gcdStep; // per-tick velocity cap in GCD steps

            yawVel = stepAxis(yawPos, yawVel, yawTarget, tau, maxVel);
            // Quantize POSITION to the step grid each tick (the coordinate system), while velocity
            // stays continuous — this is the anti-jitter core: the applied camera always sits
            // exactly on the grid, and the continuous velocity means no staircase drift.
            yawPos = Math.round(yawPos + yawVel);
            pitchVel = stepAxis(pitchPos, pitchVel, pitchTarget, tau, maxVel);
            pitchPos = Math.round(pitchPos + pitchVel);

            // Snap-and-stop when effectively at the target (sub-step distance, sub-step velocity)
            if (Math.abs(yawTarget - yawPos) < 0.5 && Math.abs(yawVel) < 0.5
                    && Math.abs(pitchTarget - pitchPos) < 0.5 && Math.abs(pitchVel) < 0.5) {
                yawPos = yawTarget;
                pitchPos = pitchTarget;
                yawVel = 0;
                pitchVel = 0;
            }

            return new Rotation(
                    (float) (yawPos * gcdStep),
                    (float) (pitchPos * gcdStep)
            );
        }

        /** The position as a rotation — used for external-displacement detection. */
        Rotation positionRotation(float gcdStep) {
            return new Rotation((float) (yawPos * gcdStep), (float) (pitchPos * gcdStep));
        }

        /**
         * One semi-implicit Euler step of a critically-damped spring on one axis.
         * acc = (target - pos)/tau² − 2·vel/tau; vel += acc; |vel| clamped to maxVel.
         */
        private static double stepAxis(double pos, double vel, double target, double tau, double maxVel) {
            final double acc = (target - pos) / (tau * tau) - 2.0 * vel / tau;
            double newVel = vel + acc;
            if (newVel > maxVel) newVel = maxVel;
            if (newVel < -maxVel) newVel = -maxVel;
            return newVel;
        }
    }

    /**
     * Angular distance between two rotations: the max of the wrap-aware yaw distance and the raw
     * pitch distance, in degrees.
     */
    private static double rotationDistance(Rotation a, Rotation b) {
        final Rotation delta = a.subtract(b).normalize();
        return Math.max(Math.abs(delta.getYaw()), Math.abs(delta.getPitch()));
    }

    public void pig() {
        Rotation winning = getWinningRotation();
        if (winning != null) {
            final Rotation actual = this.processor.peekRotation(winning);
            ctx.player().setYRot(MouseGCD.quantize(actual.getYaw(), MouseGCD.step(ctx)));
        }
    }

    public Optional<Rotation> getEffectiveRotation() {
        if (Baritone.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        // If freeLook isn't on, just defer to the player's actual rotations
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        this.ensurePlanned();
        if (this.planned != null && this.plannedConsistent) {
            // Move with exactly the (smoothed) rotation that is about to be sent to the server
            event.setYaw(this.planned.getYaw());
            event.setPitch(this.planned.getPitch());
            return;
        }
        Rotation winning = getWinningRotation();
        if (winning != null) {
            final boolean blockInteract = this.target != null && this.target.blockInteract;
            final Rotation actual = this.processor.peekRotation(winning, blockInteract);
            final float gcdStep = MouseGCD.step(ctx);
            event.setYaw(MouseGCD.quantize(actual.getYaw(), gcdStep));
            event.setPitch(MouseGCD.quantize(actual.getPitch(), gcdStep));
        }
    }

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            // Implementation will use LookBehavior.serverRotation
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        /** AR(1) coefficient for correlated random-look offsets. */
        private static final double RANDOM_RHO = 0.8;
        /** (1 - rho) * sqrt((1 + rho) / (1 - rho)): keeps the stationary spread equal to the white-noise spread. */
        private static final double RANDOM_GAIN = (1.0 - RANDOM_RHO) * Math.sqrt((1.0 + RANDOM_RHO) / (1.0 - RANDOM_RHO));

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom();
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx = source.ctx;
            this.rand = source.rand.fork();
            this.randomYawOffset = source.randomYawOffset;
            this.randomPitchOffset = source.randomPitchOffset;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            return this.peekRotation(rotation, false);
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation, final boolean skipNudge) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level. Never nudge block-interact rotations: mining
            // needs a steady stare — a wandering crosshair resets the server-side break progress.
            if (!skipNudge && desiredPitch == prev.getPitch()) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            desiredYaw += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), desiredYaw),
                    this.calculateMouseMove(prev.getPitch(), desiredPitch)
            ).clamp();
        }

        @Override
        public final void tick() {
            // randomLooking
            double yawOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            double pitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            // randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            yawOffset += random * Baritone.settings().randomLooking113.value;

            if (LookProfile.parse(Baritone.settings().lookProfile.value).isLegacy()) {
                this.randomYawOffset = yawOffset;
                this.randomPitchOffset = pitchOffset;
            } else {
                // S5: white per-tick jitter reads as vibration. Low-pass it (AR(1)) into a correlated drift with
                // about the same spread, so randomLooking still randomizes without shaking the camera.
                this.randomYawOffset = RANDOM_RHO * this.randomYawOffset + RANDOM_GAIN * yawOffset;
                this.randomPitchOffset = RANDOM_RHO * this.randomPitchOffset + RANDOM_GAIN * pitchOffset;
            }
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        /**
         * Nudges the player's pitch to a regular level. (Between {@code -20} and {@code 10}, increments are by {@code 1})
         */
        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
                return pitch - 1;
            }
            return pitch;
        }

        private float calculateMouseMove(float current, float target) {
            final float delta = net.minecraft.util.Mth.wrapDegrees(target - current);
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
        }

        private float mouseToAngle(double mouseDelta) {
            // casting float literals to double gets us the precise values used by mc
            final double f = ctx.minecraft().options.sensitivity().get() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f; // yes, one double and one float scaling factor
        }
    }

    private static class Target {

        public final Rotation rotation;
        public final Mode mode;
        public final boolean blockInteract;
        public final boolean pitchFree;

        public Target(Rotation rotation, Mode mode, boolean blockInteract, boolean pitchFree) {
            this.rotation = rotation;
            this.mode = mode;
            this.blockInteract = blockInteract;
            this.pitchFree = pitchFree;
        }

        enum Mode {
            /**
             * Rotation will be set client-side and is visual to the player
             */
            CLIENT,

            /**
             * Rotation will be set server-side and is silent to the player
             */
            SERVER,

            /**
             * Rotation will remain unaffected on both the client and server
             */
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings = Baritone.settings();
                final boolean antiCheat = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    // always need to set angles while flying
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    // Regardless of if antiCheatCompatibility is enabled, if a blockInteract is requested then the player
                    // rotation needs to be set somehow, otherwise Baritone will halt since objectMouseOver() will just be
                    // whatever the player is mousing over visually. Let's just settle for setting it silently.
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    return antiCheat ? SERVER : NONE;
                }

                // all freeLook settings are disabled so set the angles
                return CLIENT;
            }
        }
    }
}
