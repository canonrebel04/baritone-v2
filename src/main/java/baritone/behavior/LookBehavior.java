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
import baritone.behavior.look.ForkableRandom;
import baritone.utils.MouseGCD;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.ArrayDeque;
import java.util.Deque;
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

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
        this.hub = new LookPriorityHub();
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
        rotation = MouseGCD.quantize(rotation, gcdStep);
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract);
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
                // Resolve active look request (highest priority between external hub requests and Baritone internal target)
                Optional<ILookRequest> highestExternal = this.hub.getHighestPriorityRequest();

                Rotation targetRotation = null;
                Target.Mode targetMode = Target.Mode.NONE;
                boolean blockInteract = false;
                Boolean customSmooth = null;

                if (highestExternal.isPresent() && (this.target == null || highestExternal.get().getPriority() > ILookPriorityHub.DEFAULT_BARITONE_PRIORITY)) {
                    ILookRequest req = highestExternal.get();
                    targetRotation = req.getRotation();
                    if (targetRotation != null) {
                        blockInteract = req.isBlockInteract();
                        customSmooth = req.isSmooth();
                        if (req.isSilent()) {
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
                }

                if (targetRotation == null || targetMode == Target.Mode.NONE) {
                    this.lastAppliedMode = targetMode;
                    this.chase = null;
                    return;
                }

                this.lastAppliedMode = targetMode;
                final Rotation actual = this.processor.peekRotation(targetRotation);

                if (targetMode == Target.Mode.SERVER) {
                    this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                    // GCD-quantize so the silent rotation stream only contains angles reachable by
                    // real mouse input at the player's current sensitivity.
                    final float gcdStep = MouseGCD.step(ctx);
                    ctx.player().setYRot(MouseGCD.quantize(actual.getYaw(), gcdStep));
                    ctx.player().setXRot(MouseGCD.quantize(actual.getPitch(), gcdStep));
                } else if (targetMode == Target.Mode.CLIENT) {
                    boolean useSmooth;
                    if (customSmooth != null) {
                        useSmooth = customSmooth;
                    } else {
                        useSmooth = ctx.player().isFallFlying()
                                ? Baritone.settings().elytraSmoothLook.value
                                : Baritone.settings().smoothLook.value;
                    }

                    final float gcdStep = MouseGCD.step(ctx);
                    if (useSmooth) {
                        Rotation current = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());

                        double maxTurn = Baritone.settings().maxLookTurnSpeed.value;
                        if (blockInteract) {
                            maxTurn = Math.max(maxTurn, 65.0);
                        }

                        // Continuous quantized-space chase: critically-damped spring toward a live
                        // target, integrated in GCD-step units — applied camera positions always
                        // lie on the path, and re-targets preserve velocity (no restarts, no jitter).
                        Rotation smoothed = this.smoothRotation(current, actual, (float) maxTurn,
                                Baritone.settings().smoothLookTicks.value);
                        ctx.player().setYRot(MouseGCD.quantize(smoothed.getYaw(), gcdStep));
                        ctx.player().setXRot(MouseGCD.quantize(smoothed.getPitch(), gcdStep));
                    } else {
                        ctx.player().setYRot(MouseGCD.quantize(actual.getYaw(), gcdStep));
                        ctx.player().setXRot(MouseGCD.quantize(actual.getPitch(), gcdStep));
                    }
                }
                break;
            }
            case POST: {
                // Reset the player's rotations back to their original values ONLY for silent server-side rotations
                if (this.prevRotation != null) {
                    if (this.lastAppliedMode == Target.Mode.SERVER) {
                        ctx.player().setYRot(this.prevRotation.getYaw());
                        ctx.player().setXRot(this.prevRotation.getPitch());
                    }
                    this.prevRotation = null;
                }
                this.lastAppliedMode = null;
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
                break;
            }
            default:
                break;
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
            this.yawTarget = target.getYaw() / gcdStep;
            this.pitchTarget = target.getPitch() / gcdStep;
            this.yawVel = 0;
            this.pitchVel = 0;
        }

        /** Moves the set point; the spring state (position + velocity) is preserved. */
        void retarget(Rotation target, float gcdStep, float maxTurn, int smoothingTicks) {
            this.yawTarget = target.getYaw() / gcdStep;
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
        Rotation winning = getWinningRotation();
        if (winning != null) {
            final Rotation actual = this.processor.peekRotation(winning);
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
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level
            if (desiredPitch == prev.getPitch()) {
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
            this.randomYawOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            // randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Baritone.settings().randomLooking113.value;
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
            final float delta = target - current;
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

        public Target(Rotation rotation, Mode mode, boolean blockInteract) {
            this.rotation = rotation;
            this.mode = mode;
            this.blockInteract = blockInteract;
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
