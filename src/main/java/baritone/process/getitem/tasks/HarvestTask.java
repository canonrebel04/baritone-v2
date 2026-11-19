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

/**
 * A33/A36/A39: harvest the ripe crops of a known farm with {@code FarmProcess} (which replants with seeds / the
 * crop and bone meals if you carry some), until the step's count is in the inventory. Ends when the farm has
 * nothing ripe left so the process re-plans.
 */
public final class HarvestTask extends GatherTask {
    private final BlockPos center;
    private final int range;
    private boolean started;

    public HarvestTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        String[] p = step.source.ref.split(",");
        this.center = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        this.range = p.length > 3 ? Integer.parseInt(p[3]) : 8;
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        if (!started) {
            if (ctx.playerFeet().distSqr(center) > (range + 16) * (range + 16)) return walkNear(center, range);
            baritone.getFarmProcess().farm(range, center);
            started = true;
            return defer();
        }
        int stall = stalled();
        if (!baritone.getFarmProcess().isActive() || stall > 900) {
            if (have() > target - step.count) return finish(); // got some: re-plan for the rest
            return fail("nothing ripe to harvest at " + center.toShortString());
        }
        return defer();
    }

    @Override
    public void stop() {
        if (baritone.getFarmProcess().isActive()) baritone.getFarmProcess().onLostControl();
    }
}
