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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.sim.MovementSimulator;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Enhancement #21: Diagonal parkour jumps (2x1, 3x1, 3x2 offsets in any quadrant).
 * Opt-in via {@code allowDiagonalParkour} (default OFF for anticheat servers).
 */
public class MovementDiagonalParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int[][] CANDIDATE_OFFSETS = {
            {2, 1}, {1, 2}, {3, 1}, {1, 3}, {3, 2}, {2, 3}
    };

    private final int signX;
    private final int signZ;

    private MovementDiagonalParkour(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, int signX, int signZ) {
        super(baritone, src, dest, EMPTY);
        this.signX = signX;
        this.signZ = signZ;
    }

    public static MovementDiagonalParkour create(CalculationContext context, BetterBlockPos src, int signX, int signZ) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, signX, signZ, res);
        BetterBlockPos dest = new BetterBlockPos(res.x, res.y, res.z);
        return new MovementDiagonalParkour(context.getBaritone(), src, dest, signX, signZ);
    }

    public static void cost(CalculationContext context, int x, int y, int z, int signX, int signZ, MutableMoveResult res) {
        if (!context.allowDiagonalParkour || !context.allowParkour || !context.canSprint) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock())
                || standingOn.getBlock() instanceof StairBlock
                || MovementHelper.isBottomSlab(standingOn)
                || standingOn.is(Blocks.SOUL_SAND)
                || standingOn.is(Blocks.HONEY_BLOCK)
                || !context.get(x, y, z).getFluidState().isEmpty()) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        // Check immediate diagonal gap exists (don't parkour if we can walk normally)
        if (MovementHelper.canWalkOn(context, x + signX, y - 1, z + signZ)) {
            return;
        }

        double maxSpan = MovementSimulator.maxReachableJumpSpan(5, 0.0, true, context.simConfig);
        for (int[] off : CANDIDATE_OFFSETS) {
            int dx = off[0] * signX;
            int dz = off[1] * signZ;
            double dist = Math.hypot(dx, dz);
            if (dist > maxSpan - 0.25) {
                continue;
            }
            int destX = x + dx;
            int destZ = z + dz;
            BlockState landingOn = context.get(destX, y - 1, destZ);
            if (landingOn.is(Blocks.FARMLAND) || !MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn)) {
                continue;
            }
            if (!MovementHelper.fullyPassable(context, destX, y, destZ)
                    || !MovementHelper.fullyPassable(context, destX, y + 1, destZ)
                    || !MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                continue;
            }
            // Verify midpoint air corridor is clear
            int midX = x + dx / 2;
            int midZ = z + dz / 2;
            if (!MovementHelper.fullyPassable(context, midX, y, midZ)
                    || !MovementHelper.fullyPassable(context, midX, y + 1, midZ)
                    || !MovementHelper.fullyPassable(context, midX, y + 2, midZ)) {
                continue;
            }
            res.x = destX;
            res.y = y;
            res.z = destZ;
            res.cost = MovementSimulator.simulatedParkourCost(dist, 0, true, context.simConfig) + context.jumpPenalty + 2.0;
            return;
        }
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, signX, signZ, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int minX = Math.min(src.x, dest.x);
        int maxX = Math.max(src.x, dest.x);
        int minZ = Math.min(src.z, dest.z);
        int maxZ = Math.max(src.z, dest.z);
        for (int ix = minX; ix <= maxX; ix++) {
            for (int iz = minZ; iz <= maxZ; iz++) {
                set.add(new BetterBlockPos(ix, src.y, iz));
                set.add(new BetterBlockPos(ix, src.y + 1, iz));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < src.y) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (ctx.playerFeet().equals(dest) && ctx.player().onGround()) {
            if (Baritone.settings().movementLandingSnap.value && isNextTurnSharp()) {
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.SNEAK, true);
            }
            return state.setStatus(MovementStatus.SUCCESS);
        }

        state.setInput(Input.SPRINT, true);
        MovementHelper.moveTowards(ctx, state, dest);

        if (ctx.playerFeet().equals(src) && ctx.player().onGround()) {
            double px = ctx.player().position().x;
            double pz = ctx.player().position().z;
            double vx = ctx.player().getDeltaMovement().x;
            double vz = ctx.player().getDeltaMovement().z;
            double relX = Math.abs(px - (src.x + 0.5));
            double relZ = Math.abs(pz - (src.z + 0.5));
            boolean nearEdge = Math.max(relX, relZ) >= 0.34
                    || MovementSimulator.willLeaveBlockNextTick(px, pz, vx, vz, src.x, src.z, signX, 0, 0.08)
                    || MovementSimulator.willLeaveBlockNextTick(px, pz, vx, vz, src.x, src.z, 0, signZ, 0.08);
            if (nearEdge && state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false)) {
                state.setInput(Input.JUMP, true);
            }
        }
        return state;
    }
}
