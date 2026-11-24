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
import baritone.api.process.PathingCommand;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Sweet-berry leftover: right-click ripe bushes (age &ge; 2). {@code FarmProcess} would break the bush
 * and destroy the farm, so this task never starts it.
 */
public final class BerryTask extends GatherTask {
    private final BlockPos center;
    private final int range;
    private BlockPos current;

    public BerryTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        String[] p = step.source.ref.split(",");
        this.center = new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        this.range = p.length > 3 ? Integer.parseInt(p[3].trim()) : 8;
    }

    public static boolean ripe(net.minecraft.world.level.block.state.BlockState st) {
        return st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 2;
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        ItemEntity drop = nearestDrop(Vec3.atCenterOf(center), range + 2);
        if (drop != null) return pickUp(drop);
        if (ctx.playerFeet().distSqr(center) > (range + 16L) * (range + 16L)) return walkNear(center, range);
        if (current == null || !ripe(ctx.world().getBlockState(current))) {
            current = null;
            for (BlockPos p : data.findBlocks(Set.of(Blocks.SWEET_BERRY_BUSH), Math.max(range, 16))) {
                if (p.distSqr(center) <= (double) range * range && ripe(ctx.world().getBlockState(p))) {
                    current = p;
                    break;
                }
            }
            if (current == null) {
                if (have() > target - step.count) return finish();
                return fail("no ripe sweet berry bushes at " + center.toShortString());
            }
        }
        hold(s -> s.isEmpty());
        PathingCommand cmd = useBlock(current);
        if (stalled() > 2400) return fail("picking sweet berries is taking too long");
        return cmd == null ? pause() : cmd;
    }

    @Override
    public String status() {
        return "picking sweet berries " + Math.min(have(), target) + "/" + target;
    }
}
