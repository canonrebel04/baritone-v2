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
import net.minecraft.world.phys.Vec3;

/** A38: stand at the user's mob-farm collection spot and pick up the drops the step needs. */
public final class MobFarmTask extends GatherTask {
    private final BlockPos spot;

    public MobFarmTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.spot = parsePos(step.source.ref);
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        int stall = stalled();
        if (stall > 20 * 180) {
            return have() > target - step.count ? finish() : fail("the mob farm at " + spot.toShortString() + " produced nothing for 3 minutes");
        }
        if (ctx.playerFeet().distSqr(spot) > 4) {
            ItemEntity d = ctx.playerFeet().distSqr(spot) < 64 ? nearestDrop(Vec3.atCenterOf(spot), 8) : null;
            return d != null ? pickUp(d) : walkNear(spot, 0);
        }
        ItemEntity d = nearestDrop(Vec3.atCenterOf(spot), 8);
        return d != null ? pickUp(d) : pause();
    }
}
