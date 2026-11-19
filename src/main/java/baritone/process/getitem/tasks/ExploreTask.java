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

package baritone.process.getitem.tasks;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A15: go to an exploration waypoint (spiral around the start, ore depth from the biome hints) until one of the
 * wanted blocks is loaded nearby. The MINE step is then retried.
 */
public final class ExploreTask extends StepTask {
    private final int x, y, z;
    private final Set<Block> blocks = new HashSet<>();
    private final String label;

    public ExploreTask(Baritone baritone, GameData data, PlanStep mine, int[] waypoint, String label) {
        super(baritone, data, mine);
        this.x = waypoint[0];
        this.y = waypoint[1];
        this.z = waypoint[2];
        this.label = label;
        for (String b : mine.blocks) {
            Block blk = GameData.block(b);
            if (blk != null) blocks.add(blk);
        }
    }

    @Override
    protected PathingCommand onTick() {
        if (ticks % 40 == 0 && !data.findBlocks(blocks, 24).isEmpty()) return finish();
        double dx = ctx.player().getX() - x, dz = ctx.player().getZ() - z;
        boolean xzClose = dx * dx + dz * dz < 6 * 6;
        if (xzClose && (y == Integer.MIN_VALUE || Math.abs(ctx.player().getY() - y) < 3)) return finish();
        if (ticks > 20 * 240) return finish(); // 4 minutes per waypoint at most
        Goal g = y == Integer.MIN_VALUE ? new GoalXZ(x, z) : new GoalBlock(x, y, z);
        return new PathingCommand(g, PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public String status() {
        return "exploring for " + step.blocks.get(0).replace("minecraft:", "") + " (" + label + ")";
    }
}
