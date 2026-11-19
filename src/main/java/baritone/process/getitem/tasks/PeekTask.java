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
import baritone.process.getitem.GameData;
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** A11: open a never-seen container just to index its contents, then close it (nothing is clicked). */
public final class PeekTask extends ContainerTask {
    public PeekTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, BlockPos pos) {
        super(baritone, data, knowledge, PlanStep.withdraw("minecraft:air", 0, 0, 0), pos);
    }

    @Override
    protected boolean work(AbstractContainerMenu m) {
        return true;
    }

    @Override
    protected boolean succeeded() {
        return true;
    }

    @Override
    public String status() {
        return "checking the chest at " + access.pos.toShortString();
    }
}
