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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    /**
     * Enhancements #1 & #11: Next movement in the active path, supplied by PathExecutor
     * so movements can compute approach vectors, pre-turn corners, and hand off momentum.
     */
    protected IMovement nextMovement;
    private boolean prevForward;
    private boolean prevBack;
    private boolean prevLeft;
    private boolean prevRight;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public Double getCost() {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().position().y < dest.y + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // Enhancement #42: Yaw/pitch decoupling — keep pitch steady/level during pure movement
        // unless we are forcing rotations for mining, placing, or climbing.
        currentState.getTarget().getRotation().ifPresent(rotation -> {
            Rotation effective = rotation;
            if (!currentState.getTarget().hasToForceRotations() && Baritone.settings().movementDecouplePitch.value) {
                float currentPitch = ctx.playerRotations().getPitch();
                float levelPitch = Math.max(-18.0f, Math.min(18.0f, currentPitch * 0.85f));
                effective = rotation.withPitch(levelPitch);
            }
            baritone.getLookBehavior().updateTarget(
                    effective,
                    currentState.getTarget().hasToForceRotations());
        });

        // Enhancement #10: Input debouncing — avoid 1-tick opposite-key oscillation (W<->S, A<->D)
        if (Baritone.settings().movementInputDebounce.value) {
            Map<Input, Boolean> inputs = currentState.getInputStates();
            boolean fwd = inputs.getOrDefault(Input.MOVE_FORWARD, false);
            boolean back = inputs.getOrDefault(Input.MOVE_BACK, false);
            boolean left = inputs.getOrDefault(Input.MOVE_LEFT, false);
            boolean right = inputs.getOrDefault(Input.MOVE_RIGHT, false);
            if (fwd && back) {
                inputs.put(Input.MOVE_FORWARD, false);
                inputs.put(Input.MOVE_BACK, false);
                fwd = false;
                back = false;
            }
            if (left && right) {
                inputs.put(Input.MOVE_LEFT, false);
                inputs.put(Input.MOVE_RIGHT, false);
                left = false;
                right = false;
            }
            if (left && prevRight) {
                inputs.put(Input.MOVE_LEFT, false);
                left = false;
            } else if (right && prevLeft) {
                inputs.put(Input.MOVE_RIGHT, false);
                right = false;
            }
            prevForward = fwd;
            prevBack = back;
            prevLeft = left;
            prevRight = right;
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.calculateBlockCenter(ctx.world(), blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
        prevForward = false;
        prevBack = false;
        prevLeft = false;
        prevRight = false;
    }

    public void setNextMovement(IMovement nextMovement) {
        this.nextMovement = nextMovement;
    }

    /**
     * S16/S20: centers {x0, z0, x1, z1, ...} of this movement's destination and the following flat, already
     * clear walking nodes (same Y, nothing to break or place), supplied by the path executor; null if none.
     */
    protected double[] upcomingFlat;

    public void setUpcomingFlat(double[] nodes) {
        this.upcomingFlat = nodes;
    }

    public IMovement getNextMovement() {
        return nextMovement;
    }

    /**
     * Enhancement #11: Returns true if the next movement continues along the same horizontal heading.
     */
    public boolean continuesStraight() {
        if (nextMovement == null) {
            return false;
        }
        int dx1 = Integer.compare(dest.x, src.x);
        int dz1 = Integer.compare(dest.z, src.z);
        int dx2 = Integer.compare(nextMovement.getDest().x, nextMovement.getSrc().x);
        int dz2 = Integer.compare(nextMovement.getDest().z, nextMovement.getSrc().z);
        return dx1 == dx2 && dz1 == dz2 && (dx1 != 0 || dz1 != 0);
    }

    /**
     * Returns true if the path ends after this movement or makes a >= 90-degree horizontal turn.
     */
    public boolean isNextTurnSharp() {
        if (nextMovement == null) {
            return true;
        }
        int dx1 = Integer.compare(dest.x, src.x);
        int dz1 = Integer.compare(dest.z, src.z);
        int dx2 = Integer.compare(nextMovement.getDest().x, nextMovement.getSrc().x);
        int dz2 = Integer.compare(nextMovement.getDest().z, nextMovement.getSrc().z);
        if (dx1 == 0 && dz1 == 0) {
            return true;
        }
        if (dx2 == 0 && dz2 == 0) {
            return true;
        }
        return (dx1 * dx2 + dz1 * dz2) <= 0;
    }

    /**
     * Enhancement #11: Expected horizontal exit speed (blocks/tick) handed off to the next movement.
     */
    public double expectedExitSpeed() {
        if (continuesStraight()) {
            return Baritone.settings().allowSprint.value ? 0.28 : 0.21;
        }
        return isNextTurnSharp() ? 0.0 : 0.16;
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
