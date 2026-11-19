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
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Enhancement #25: Neo jump around a 1-block solid pillar/corner between {@code src}
 * and {@code src.relative(dir, 2)}. Opt-in via {@code allowNeoJump} (default OFF for anticheat safety).
 */
public class MovementNeo extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction forwardDir;
    private final Direction sideDir;

    private MovementNeo(IBaritone baritone, BetterBlockPos src, Direction forwardDir, Direction sideDir) {
        super(baritone, src, src.relative(forwardDir, 2), EMPTY);
        this.forwardDir = forwardDir;
        this.sideDir = sideDir;
    }

    public static MovementNeo create(CalculationContext context, BetterBlockPos src, Direction forwardDir) {
        MutableMoveResult res = new MutableMoveResult();
        Direction chosenSide = findClearSide(context, src.x, src.y, src.z, forwardDir);
        cost(context, src.x, src.y, src.z, forwardDir, res);
        return new MovementNeo(context.getBaritone(), src, forwardDir, chosenSide != null ? chosenSide : forwardDir.getClockWise());
    }

    private static Direction findClearSide(CalculationContext context, int x, int y, int z, Direction forwardDir) {
        int fx = forwardDir.getStepX();
        int fz = forwardDir.getStepZ();
        Direction[] sides = {forwardDir.getClockWise(), forwardDir.getCounterClockWise()};
        for (Direction side : sides) {
            int sx = side.getStepX();
            int sz = side.getStepZ();
            // Need clear 3-block corridor beside src, pillar, and dest
            if (MovementHelper.fullyPassable(context, x + sx, y, z + sz)
                    && MovementHelper.fullyPassable(context, x + sx, y + 1, z + sz)
                    && MovementHelper.fullyPassable(context, x + fx + sx, y, z + fz + sz)
                    && MovementHelper.fullyPassable(context, x + fx + sx, y + 1, z + fz + sz)
                    && MovementHelper.fullyPassable(context, x + fx + sx, y + 2, z + fz + sz)
                    && MovementHelper.fullyPassable(context, x + fx * 2 + sx, y, z + fz * 2 + sz)
                    && MovementHelper.fullyPassable(context, x + fx * 2 + sx, y + 1, z + fz * 2 + sz)) {
                return side;
            }
        }
        return null;
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction forwardDir, MutableMoveResult res) {
        if (!context.allowNeoJump || !context.allowParkour || !context.canSprint) {
            return;
        }
        int fx = forwardDir.getStepX();
        int fz = forwardDir.getStepZ();
        // Neo only applies when the block directly in front is solid (blocking a normal traverse/parkour)
        if (MovementHelper.fullyPassable(context, x + fx, y, z + fz) && MovementHelper.fullyPassable(context, x + fx, y + 1, z + fz)) {
            return;
        }
        int destX = x + fx * 2;
        int destZ = z + fz * 2;
        if (!MovementHelper.canWalkOn(context, destX, y - 1, destZ)
                || !MovementHelper.fullyPassable(context, destX, y, destZ)
                || !MovementHelper.fullyPassable(context, destX, y + 1, destZ)
                || !MovementHelper.fullyPassable(context, destX, y + 2, destZ)
                || !MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        if (findClearSide(context, x, y, z, forwardDir) == null) {
            return;
        }
        res.x = destX;
        res.y = y;
        res.z = destZ;
        res.cost = MovementSimulator.simulatedParkourCost(2.6, 0, true, context.simConfig) + context.jumpPenalty + 6.0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, forwardDir, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        BetterBlockPos mid = src.relative(forwardDir);
        set.add(src);
        set.add(dest);
        set.add(src.relative(sideDir));
        set.add(mid.relative(sideDir));
        set.add(mid.relative(sideDir).above());
        set.add(dest.relative(sideDir));
        set.add(dest.above());
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
            return state.setStatus(MovementStatus.SUCCESS);
        }

        double px = ctx.player().position().x;
        double pz = ctx.player().position().z;
        double fwdProgress = (px - (src.x + 0.5)) * forwardDir.getStepX() + (pz - (src.z + 0.5)) * forwardDir.getStepZ();
        double sideProgress = (px - (src.x + 0.5)) * sideDir.getStepX() + (pz - (src.z + 0.5)) * sideDir.getStepZ();

        state.setInput(Input.SPRINT, true);
        if (fwdProgress < 1.05) {
            // Arc outward past the corner of the blocking pillar
            Vec3 apex = new Vec3(
                    src.x + 0.5 + forwardDir.getStepX() * 1.0 + sideDir.getStepX() * 0.92,
                    src.y,
                    src.z + 0.5 + forwardDir.getStepZ() * 1.0 + sideDir.getStepZ() * 0.92
            );
            MovementHelper.moveTowards(ctx, state, apex);
            if (ctx.player().onGround() && (sideProgress >= 0.35 || fwdProgress >= 0.32)) {
                state.setInput(Input.JUMP, true);
            }
        } else {
            // Past the pillar: curve back in toward dest center
            MovementHelper.moveTowards(ctx, state, dest);
        }
        return state;
    }
}
