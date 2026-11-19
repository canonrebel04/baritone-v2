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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.world.entity.item.ItemEntity;

/** A16: walk over a dropped item the plan needs. Never fails the plan: gives up quietly after a while. */
public final class PickupTask extends StepTask {
    private final ItemEntity target;

    public PickupTask(Baritone baritone, GameData data, ItemEntity target) {
        super(baritone, data, PlanStep.withdraw(GameData.id(target.getItem().getItem()), target.getItem().getCount(), 0, 0));
        this.target = target;
    }

    @Override
    protected PathingCommand onTick() {
        if (!target.isAlive() || target.isRemoved() || ticks > 300) return finish();
        return new PathingCommand(new GoalBlock(target.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public String status() {
        return "picking up " + step.count + "x " + step.items.get(0).replace("minecraft:", "");
    }
}
