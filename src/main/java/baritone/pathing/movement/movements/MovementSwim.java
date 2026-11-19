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
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Enhancement #29 & #30: Vertical bubble column & deep water swim travel
 * (rides soul-sand bubble columns upward and magma bubble columns downward, or swims up 1 block in water).
 */
public class MovementSwim extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private final boolean upward;

    public MovementSwim(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, boolean upward) {
        super(baritone, src, dest, EMPTY);
        this.upward = upward;
    }

    public static MovementSwim create(CalculationContext context, BetterBlockPos src, boolean upward) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, upward, res);
        return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z), upward);
    }

    public static void cost(CalculationContext context, int x, int y, int z, boolean upward, MutableMoveResult res) {
        if (!context.allowBubbleColumnTravel) {
            return;
        }
        BlockState here = context.get(x, y, z);
        if (!here.is(Blocks.BUBBLE_COLUMN)) {
            return;
        }
        boolean dragDown = here.getValue(BubbleColumnBlock.DRAG_DOWN);
        if (upward && dragDown) {
            return;
        }
        if (!upward && !dragDown) {
            return;
        }
        int step = upward ? 1 : -1;
        int nextY = y + step;
        if (nextY <= context.world.getMinY() || nextY >= context.world.getMaxY() - 1) {
            return;
        }
        if (!MovementHelper.canWalkThrough(context, x, nextY, z) || !MovementHelper.canWalkThrough(context, x, nextY + 1, z)) {
            return;
        }
        res.x = x;
        res.y = nextY;
        res.z = z;
        // Bubble columns move ~0.7 blocks/tick vertically -> very fast!
        res.cost = upward ? 2.2 : 2.5;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, upward, res);
        if (res.y != dest.y) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        set.add(dest);
        return set;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        // Keep centered inside the bubble column
        double distXZ = Math.hypot((dest.x + 0.5) - ctx.player().position().x, (dest.z + 0.5) - ctx.player().position().z);
        if (distXZ > 0.18) {
            MovementHelper.moveTowards(ctx, state, dest);
        }
        if (upward) {
            state.setInput(Input.JUMP, true);
        } else {
            // Avoid magma damage at the bottom of a downward bubble column
            if (BlockStateInterface.get(ctx, dest.below()).is(Blocks.MAGMA_BLOCK)) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return state;
    }
}
