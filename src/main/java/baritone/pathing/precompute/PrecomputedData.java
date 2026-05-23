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

package baritone.pathing.precompute;

import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

public class PrecomputedData {

    private final short[] data = new short[Block.BLOCK_STATE_REGISTRY.size()];

    /**
     * short layout
     *
     *          15-11          10              9                 8                  7                     6              5              4              3              2              1             0
     *            |            |               |                 |                  |                     |              |              |              |              |              |             |
     *         unused     avoidWI   isNormCube   isReplaceable   isReplaceableMaybe   canWalkOn   maybe   canWalkThrough    maybe        fullyPassable    maybe       completed
     */

    private static final short COMPLETED_MASK = (short) (1 << 0);
    private static final short FULLY_PASSABLE_MAYBE_MASK = (short) (1 << 1);
    private static final short FULLY_PASSABLE_MASK = (short) (1 << 2);
    private static final short CAN_WALK_THROUGH_MAYBE_MASK = (short) (1 << 3);
    private static final short CAN_WALK_THROUGH_MASK = (short) (1 << 4);
    private static final short CAN_WALK_ON_MAYBE_MASK = (short) (1 << 5);
    private static final short CAN_WALK_ON_MASK = (short) (1 << 6);
    private static final short IS_REPLACEABLE_MAYBE_MASK = (short) (1 << 7);
    private static final short IS_REPLACEABLE_MASK = (short) (1 << 8);
    private static final short IS_BLOCK_NORMAL_CUBE_MASK = (short) (1 << 9);
    private static final short AVOID_WALKING_INTO_MASK = (short) (1 << 10);

    private short fillData(int id, BlockState state) {
        short blockData = 0;

        Ternary canWalkOnState = MovementHelper.canWalkOnBlockState(state);
        switch (canWalkOnState) {
            case YES -> blockData |= CAN_WALK_ON_MASK;
            case MAYBE -> blockData |= CAN_WALK_ON_MAYBE_MASK;
        }

        Ternary canWalkThroughState = MovementHelper.canWalkThroughBlockState(state);
        switch (canWalkThroughState) {
            case YES -> blockData |= CAN_WALK_THROUGH_MASK;
            case MAYBE -> blockData |= CAN_WALK_THROUGH_MAYBE_MASK;
        }

        Ternary fullyPassableState = MovementHelper.fullyPassableBlockState(state);
        switch (fullyPassableState) {
            case YES -> blockData |= FULLY_PASSABLE_MASK;
            case MAYBE -> blockData |= FULLY_PASSABLE_MAYBE_MASK;
        }

        // isReplaceable
        Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.AirBlock) {
            blockData |= IS_REPLACEABLE_MASK;
        } else if (block instanceof SnowLayerBlock) {
            blockData |= IS_REPLACEABLE_MAYBE_MASK;
        } else if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            blockData |= IS_REPLACEABLE_MASK;
        } else if (state.canBeReplaced()) {
            blockData |= IS_REPLACEABLE_MASK;
        }

        // isBlockNormalCube
        if (MovementHelper.isBlockNormalCube(state)) {
            blockData |= IS_BLOCK_NORMAL_CUBE_MASK;
        }

        // avoidWalkingInto
        if (MovementHelper.avoidWalkingInto(state)) {
            blockData |= AVOID_WALKING_INTO_MASK;
        }

        blockData |= COMPLETED_MASK;

        data[id] = blockData;
        return blockData;
    }

    public boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_ON_MAYBE_MASK) != 0) {
            return MovementHelper.canWalkOnPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_ON_MASK) != 0;
        }
    }

    public boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_THROUGH_MAYBE_MASK) != 0) {
            return MovementHelper.canWalkThroughPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_THROUGH_MASK) != 0;
        }
    }

    public boolean fullyPassable(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        if ((blockData & FULLY_PASSABLE_MAYBE_MASK) != 0) {
            return MovementHelper.fullyPassablePosition(bsi, x, y, z, state);
        } else {
            return (blockData & FULLY_PASSABLE_MASK) != 0;
        }
    }

    public boolean isReplaceable(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        if ((blockData & IS_REPLACEABLE_MAYBE_MASK) != 0) {
            if (state.getBlock() instanceof SnowLayerBlock) {
                if (!bsi.worldContainsLoadedChunk(x, z)) {
                    return true;
                }
                return state.getValue(SnowLayerBlock.LAYERS) == 1;
            }
            return false;
        } else {
            return (blockData & IS_REPLACEABLE_MASK) != 0;
        }
    }

    public boolean isBlockNormalCube(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        return (blockData & IS_BLOCK_NORMAL_CUBE_MASK) != 0;
    }

    public boolean avoidWalkingInto(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(id, state);
        }

        return (blockData & AVOID_WALKING_INTO_MASK) != 0;
    }
}
