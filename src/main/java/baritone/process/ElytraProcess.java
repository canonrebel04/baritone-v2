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

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.*;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private NetherPathfinderContext npfContext;
    private boolean predictingTerrain;
    private boolean allowTight;
    private boolean allowAboveBuildLimit;
    private boolean allowAboveRoof;
    private final Semaphore npfSema = new Semaphore(1);

    /**
     * Multi-leg trip state (roadmap item 3). {@code tripLegs} is non-null while a trip is in
     * progress; {@code tripLegIndex} points at the leg currently being flown. Progress is
     * persisted to {@code <dimension>-elytra-trip.json} in the baritone world folder so an
     * interrupted trip can be resumed.
     */
    private List<GoalXZ> tripLegs;
    private int tripLegIndex = -1;
    private boolean tripResumeAttempted;

    // firework economy (roadmap item 6)
    private long lastBurnCheckMs;
    private int burnWarnedLeg = Integer.MIN_VALUE;

    private static final Gson TRIP_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int SHORT_LANDING_COLUMN_HEIGHT = 15;
    private static final int LONG_LANDING_COLUMN_HEIGHT = 39;
    private static final long LANDING_SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(25); // half a tick
    private int landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();
    private LandingSearchState landingSearchState;

    @Override
    public void onLostControl() {
        onLostControl(true);
    }

    public void onLostControl(boolean destroyNpf) {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.reachedGoal = false;
        this.goal = null;
        destroyBehaviorAsync();
        if (destroyNpf) {
            destroyNpfContextAsync();
        }
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            final long seedSetting = Baritone.settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.npfContext.getSeed()) {
                logDirect("Nether seed changed, recalculating path");
                this.resetState();
            }
            if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraPredictTerrain setting changed, recalculating path from scratch");
                predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
                this.resetState();
            }
            if (allowTight != Baritone.settings().elytraAllowTightSpaces.value) {
                logDirect("elytraAllowTightSpaces setting changed, recalculating path from scratch");
                allowTight = Baritone.settings().elytraAllowTightSpaces.value;
                this.resetState();
            }
            if (allowAboveBuildLimit != Baritone.settings().elytraAllowAboveBuildLimit.value) {
                logDirect("elytraAllowAboveBuildLimit setting changed, recalculating path from scratch");
                allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
                this.resetState();
            }
            if (allowAboveRoof != Baritone.settings().elytraAllowAboveRoof.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraAllowAboveRoof setting changed, recalculating path from scratch");
                allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
                this.resetState();
            }
        } catch (IllegalArgumentException e) {
            logDirect(e.getMessage(), ChatFormatting.RED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        this.behavior.onTick();

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (last.distToCenterSqr(ctx.player().position()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                if (this.landingSearchState == null) {
                    logDirect("Path complete, searching for safe landing spot...");
                }
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    logDirect("Found potential landing spot.");
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                    this.goingToLandingSpot = true;
                } else {
                    this.goingToLandingSpot = false;
                }
            }

            if (last != null && last.distToCenterSqr(ctx.player().position()) < 1) {
                // only treat this as the real arrival when it's not a mid-trip leg about to be followed by another
                if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal && isFinalTripLeg()) {
                    logNotification("Pathing complete", false);
                }
                if (Baritone.settings().disconnectOnArrival.value && !reachedGoal && isFinalTripLeg()) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot && landingSpot != null) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - this.landingColumnHeight) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            checkFireworkBurn();
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.behavior.npfContext.tryAcquireReadLock()) {
                try {
                    behavior.tick();
                } finally {
                    this.behavior.npfContext.releaseReadLock();
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.tripLegs != null && !isFinalTripLeg()) {
                // multi-leg trip: continue straight to the next leg without tearing the process down
                this.advanceTripLeg();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            this.tripLegs = null;
            this.tripLegIndex = -1;
            this.clearTripState();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && Baritone.settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (!checkFireworkBudget()) {
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(getJumpOffYLevel());
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            final boolean canStartFlying = ctx.player().fallDistance > 1.0f
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (ctx.player().fallDistance > 1.0f) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(() -> {
                behavior.destroy();
            });
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.npfContext == null) return;

        ChunkSource chunkProvider = ctx.world().getChunkSource();
        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npfContext.queueForPacking(chunk);
                }
            }
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public List<BetterBlockPos> getPath() {
        return this.behavior != null ? behavior.pathManager.getPath() : Collections.emptyList();
    }

    @Override
    public void pathTo(BlockPos destination) {
        if (!isSupportedPos(destination)) {
            throw new IllegalArgumentException("The goal must be within bounds to use elytra flight.");
        }

        if (ctx.player() != null && !isSupportedPos(ctx.playerFeet())) {
            throw new IllegalArgumentException("The player must be within bounds to use elytra flight.");
        }

        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null) {
            return;
        }
        this.onLostControl(false);
        this.predictingTerrain = ctx.player().level().dimension() == Level.NETHER && Baritone.settings().elytraPredictTerrain.value;
        this.allowTight = Baritone.settings().elytraAllowTightSpaces.value;
        this.allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
        this.allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        this.behavior = new ElytraBehavior(this.baritone, this, getNpfContext(), destination, appendDestination);

        if (ctx.world() != null) {
            this.repackChunks();
        }
        this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            // ElytraBehavior will automatically change the destination height depending on if we're above or below the roof
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }

        this.pathTo((new BlockPos(x, y, z)));
    }

    @Override
    public void startTrip(List<GoalXZ> legs) {
        this.startTrip(legs, 0);
    }

    private void startTrip(List<GoalXZ> legs, int startIndex) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A trip requires at least two legs");
        }
        if (startIndex < 0 || startIndex >= legs.size()) {
            throw new IllegalArgumentException("Trip leg index out of bounds: " + startIndex);
        }
        this.tripLegs = new ArrayList<>(legs);
        this.tripLegIndex = startIndex;
        this.saveTripState();
        logDirect((startIndex > 0 ? "Resuming trip" : "Starting trip") + " with " + legs.size() + " legs, flying leg " + (startIndex + 1) + "/" + legs.size());
        this.pathTo(this.tripLegs.get(this.tripLegIndex));
    }

    @Override
    public void cancelTrip() {
        if (this.tripLegs == null) {
            return;
        }
        this.tripLegs = null;
        this.tripLegIndex = -1;
        this.clearTripState();
        logDirect("Trip cancelled");
    }

    @Override
    public boolean isTripActive() {
        return this.tripLegs != null;
    }

    /**
     * @return {@code true} if the leg currently being flown is the last one of an active trip
     * (or no trip is active, in which case normal single-goal behavior applies)
     */
    private boolean isFinalTripLeg() {
        return this.tripLegs == null || this.tripLegIndex >= this.tripLegs.size() - 1;
    }

    /**
     * Advances to the next leg of an active trip once the current leg's landing is complete.
     * The pathfinder context is kept alive across legs ({@code pathTo0} only re-creates the
     * behavior, not the npf context), so there is no full process shutdown between legs.
     */
    private void advanceTripLeg() {
        this.tripLegIndex++;
        this.saveTripState();
        logDirect("leg " + (this.tripLegIndex + 1) + "/" + this.tripLegs.size() + " complete, continuing");
        try {
            this.pathTo(this.tripLegs.get(this.tripLegIndex));
        } catch (IllegalArgumentException e) {
            logDirect("Failed to continue trip: " + e.getMessage(), ChatFormatting.RED);
            this.cancelTrip();
        }
    }

    private Path tripFile() {
        return baritone.getWorldProvider().getCurrentWorld().directory
                .resolve(ctx.world().dimension().identifier().getPath() + "-elytra-trip.json");
    }

    private void saveTripState() {
        try {
            Path file = this.tripFile();
            Files.createDirectories(file.getParent());
            SavedTrip saved = new SavedTrip();
            saved.currentLeg = this.tripLegIndex;
            saved.legs = this.tripLegs.stream().map(leg -> new int[]{leg.getX(), leg.getZ()}).toArray(int[][]::new);
            Files.writeString(file, TRIP_GSON.toJson(saved));
        } catch (Exception e) {
            logDirect("Failed to save elytra trip state: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private void clearTripState() {
        try {
            Files.deleteIfExists(this.tripFile());
        } catch (Exception e) {
            logDirect("Failed to clear elytra trip state: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    /**
     * @return The persisted trip, or {@code null} if there is none (or it is malformed/too small)
     */
    private SavedTrip loadTripState() {
        try {
            Path file = this.tripFile();
            if (!Files.exists(file)) {
                return null;
            }
            SavedTrip saved = TRIP_GSON.fromJson(Files.readString(file), SavedTrip.class);
            if (saved == null || saved.legs == null || saved.legs.length < 2) {
                return null;
            }
            return saved;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class SavedTrip {
        int[][] legs;
        int currentLeg;
    }

    private boolean isSupportedPos(BlockPos pos) {
        final boolean isNether = ctx.world().dimension() == Level.NETHER;
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = (isNether && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, ctx.world().dimensionType().height() + minY);

        final boolean aboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        final boolean aboveBuild = Baritone.settings().elytraAllowAboveBuildLimit.value;

        final boolean enforceMaxY = isNether ? !(aboveRoof && aboveBuild) : !aboveBuild;

        if (pos.getY() < minY) {
            return false;
        }

        return !enforceMaxY || pos.getY() < maxY;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    // =====================================================
    // Firework economy (roadmap item 6)
    // =====================================================

    /**
     * @return the count of firework rockets in the player's inventory
     */
    private int countFireworks() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        return qty;
    }

    /**
     * Estimates the firework need for the remaining trip: the sum of straight-line distances
     * of all remaining legs (first leg measured from the player's current position, which
     * already accounts for distance flown), or the straight-line distance to the current
     * single goal, divided by {@code elytraBlocksPerFirework} and multiplied by
     * {@code elytraFireworkSafetyFactor}.
     */
    private double estimateFireworkNeed(BetterBlockPos from) {
        final double blocksPerFirework = Math.max(1.0, Baritone.settings().elytraBlocksPerFirework.value);
        double distance = 0;
        if (this.tripLegs != null && !this.tripLegs.isEmpty()) {
            int x = from.x;
            int z = from.z;
            for (int i = Math.max(0, this.tripLegIndex); i < this.tripLegs.size(); i++) {
                GoalXZ leg = this.tripLegs.get(i);
                distance += Math.hypot(leg.getX() - x, leg.getZ() - z);
                x = leg.getX();
                z = leg.getZ();
            }
        } else {
            BlockPos dest = this.currentDestination();
            if (dest != null) {
                distance = Math.hypot(dest.getX() - from.x, dest.getZ() - from.z);
            }
        }
        return distance / blocksPerFirework * Baritone.settings().elytraFireworkSafetyFactor.value;
    }

    /**
     * Takeoff-time firework budget check (roadmap item 6). Warns when the inventory doesn't
     * cover the estimated need and, if {@code elytraRequireFireworkBudget} is set, refuses
     * takeoff entirely. Also (re)initializes the mid-flight burn-warning state.
     *
     * @return {@code false} if takeoff is refused under the budget gate
     */
    private boolean checkFireworkBudget() {
        this.lastBurnCheckMs = System.currentTimeMillis();
        this.burnWarnedLeg = Integer.MIN_VALUE;
        final double need = estimateFireworkNeed(ctx.playerFeet());
        final int have = countFireworks();
        if (have >= need) {
            return true;
        }
        logDirect(String.format("firework budget: need ~%d, have %d — trip may require mid-flight resupply", (int) Math.ceil(need), have));
        if (Baritone.settings().elytraRequireFireworkBudget.value) {
            logDirect("Refusing takeoff: inventory is under the firework budget and elytraRequireFireworkBudget is enabled.", ChatFormatting.RED);
            return false;
        }
        return true;
    }

    /**
     * Mid-flight firework burn check (roadmap item 6). Every 30 seconds, compares remaining
     * rockets against the projected need for the rest of the trip (see
     * {@link #estimateFireworkNeed}); warns once per leg when the remaining supply is below
     * 1.2x the projected need.
     */
    private void checkFireworkBurn() {
        final long now = System.currentTimeMillis();
        if (now - this.lastBurnCheckMs < 30_000 || this.burnWarnedLeg == this.tripLegIndex) {
            return;
        }
        this.lastBurnCheckMs = now;
        final double need = estimateFireworkNeed(ctx.playerFeet());
        final int have = countFireworks();
        if (have >= need * 1.2) {
            return;
        }
        this.burnWarnedLeg = this.tripLegIndex;
        logDirect(String.format("firework burn: ~%d still needed, %d remaining — consider resupply", (int) Math.ceil(need), have), ChatFormatting.YELLOW);
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        NONE("None"),
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
            // allow trip resume to be attempted again when joining the next world
            this.tripResumeAttempted = false;
        }
    }

    /**
     * Auto-resume hook for multi-leg trips: if {@code elytraTripResume} is enabled and a
     * persisted trip exists for this dimension (e.g. the client crashed or the server
     * restarted mid-trip), continue the trip at the saved leg once a world is joined.
     */
    @Override
    public void onTick(TickEvent event) {
        if (event.getState() != EventState.POST
                || this.behavior != null
                || this.tripLegs != null
                || this.tripResumeAttempted) {
            return;
        }
        if (!Baritone.settings().elytraTripResume.value
                || ctx.player() == null
                || ctx.world() == null
                || ctx.player().isFallFlying()) {
            return;
        }
        // only attempt once per world join, regardless of outcome
        this.tripResumeAttempted = true;
        SavedTrip saved = this.loadTripState();
        if (saved == null) {
            return;
        }
        if (saved.currentLeg < 0 || saved.currentLeg >= saved.legs.length) {
            // completed or corrupt trip file, nothing to resume
            this.clearTripState();
            return;
        }
        List<GoalXZ> legs = new ArrayList<>();
        for (int[] leg : saved.legs) {
            legs.add(new GoalXZ(leg[0], leg[1]));
        }
        try {
            this.startTrip(legs, saved.currentLeg);
        } catch (IllegalArgumentException e) {
            logDirect("Failed to resume elytra trip: " + e.getMessage(), ChatFormatting.RED);
            this.cancelTrip();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static final Set<Block> OVERWORLD_SAFE_BLOCKS = Set.of(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.COBBLESTONE,
        Blocks.SAND, Blocks.SANDSTONE, Blocks.GRAVEL, Blocks.SNOW_BLOCK,
        Blocks.OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.GRANITE,
        Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE
    );

    private int getJumpOffYLevel() {
        if (ctx.world().dimension() == Level.NETHER) {
            return 31;
        }
        if (ctx.world().dimension() == Level.END) {
            return 100;
        }
        return Math.min(ctx.world().getMaxY() - 16, 120);
    }

    private static boolean isInBounds(Level dim, BlockPos pos) {
        DimensionType dimType = dim.dimensionType();
        int minY = dimType.minY();
        int maxY = (dim.dimension() == Level.NETHER && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, dimType.height() + minY);
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(Block block) {
        if (ctx.world().dimension() == Level.NETHER) {
            return block == Blocks.NETHERRACK || block == Blocks.GRAVEL
                || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value);
        }
        if (ctx.world().dimension() == Level.END) {
            return block == Blocks.END_STONE || block == Blocks.OBSIDIAN;
        }
        return OVERWORLD_SAFE_BLOCKS.contains(block);
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        final boolean useHeightmap = ctx.player().getY() > ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ());
        if (this.landingSearchState == null || !this.landingSearchState.isCompatible(start, useHeightmap)) {
            this.landingSearchState = new LandingSearchState(start, this.behavior.destination, useHeightmap);
        } else {
            this.landingSearchState.updateStartPosition(start);
        }

        BetterBlockPos landingSpot = this.landingSearchState.advance();
        if (landingSpot != null || this.landingSearchState.exhausted) {
            this.landingSearchState = null;
        }
        return landingSpot;
    }

    private boolean isChunkLoaded(BetterBlockPos pos) {
        return ctx.world().getChunkSource().hasChunk(pos.x >> 4, pos.z >> 4);
    }

    private final class LandingSearchState {
        private final BetterBlockPos origin;
        private final boolean useHeightmap;
        private final Queue<BetterBlockPos> queue;
        private final Set<BetterBlockPos> visited = new HashSet<>();
        private final LongOpenHashSet checkedPositions = new LongOpenHashSet();
        private boolean exhausted;

        private LandingSearchState(BetterBlockPos origin, BetterBlockPos dest, boolean useHeightmap) {
            this.origin = origin;
            this.useHeightmap = useHeightmap;

            final BetterBlockPos target = isChunkLoaded(dest) ? dest : origin;
            this.queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - target.x) * (pos.x - target.x) + (pos.z - target.z) * (pos.z - target.z)).thenComparingInt(pos -> -pos.y));
            this.queue.add(target);
        }

        private boolean isCompatible(BetterBlockPos start, boolean useHeightmap) {
            // Restart if we've moved more than a chunk so the priority adjusts and newly loaded chunks get revisited
            return this.useHeightmap == useHeightmap && this.origin.distanceSq(start) <= (16 * 16);
        }

        private void updateStartPosition(BetterBlockPos start) {
            if (this.visited.add(start)) {
                this.queue.add(start);
            }
        }

        private BetterBlockPos advance() {
            final long deadline = System.nanoTime() + LANDING_SEARCH_BUDGET_NANOS;
            while (!this.queue.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                BetterBlockPos qPos = this.queue.poll();
                if (!isChunkLoaded(qPos)) {
                    continue;
                }
                BetterBlockPos landing = this.useHeightmap ? this.advanceHeightmap(qPos) : this.advanceUnderground(qPos);
                if (landing != null) {
                    return landing;
                }
            }
            this.exhausted = true;
            return null;
        }

        private BetterBlockPos advanceUnderground(BetterBlockPos pos) {
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot, landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
                if (this.visited.add(pos.above())) this.queue.add(pos.above());
                if (this.visited.add(pos.below())) this.queue.add(pos.below());
            }
            return null;
        }

        private BetterBlockPos advanceHeightmap(BetterBlockPos qPos) {
            int height = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, qPos.getX(), qPos.getZ());
            BetterBlockPos pos = new BetterBlockPos(qPos.getX(), height + 1, qPos.getZ());
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = ctx.playerFeet().y - actualLandingSpot.y < LONG_LANDING_COLUMN_HEIGHT ? SHORT_LANDING_COLUMN_HEIGHT : LONG_LANDING_COLUMN_HEIGHT;
                    if (hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
            }
            return null;
        }
    }

    private NetherPathfinderContext getNpfContext() {
        if(this.npfContext == null) {
            npfSema.acquireUninterruptibly();
            this.npfContext = new NetherPathfinderContext(
                    Baritone.settings().elytraNetherSeed.value,
                    Baritone.settings().elytraUseCache.value ? baritone.getWorldProvider().getCurrentWorld().directory.resolve("cache") : null,
                    ctx.world()
            );
        }
        return this.npfContext;
    }

    private void destroyNpfContextAsync() {
        NetherPathfinderContext npf = this.npfContext;
        if (npf != null) {
            this.npfContext = null;
            Baritone.getExecutor().execute(() -> {
                npf.destroy();
                npfSema.release();
            });
        }
    }
}
