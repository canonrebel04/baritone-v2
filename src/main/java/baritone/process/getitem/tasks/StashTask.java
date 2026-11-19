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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** A43: put inventory overflow (items the plan doesn't need, never protected ones or tools) into a container. */
public final class StashTask extends ContainerTask {
    private final Set<String> items;
    private int moved;

    public StashTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, BlockPos pos, Set<String> items) {
        super(baritone, data, knowledge, PlanStep.withdraw("minecraft:air", 0, 0, 0), pos);
        this.items = items;
        if (pos == null) setFailure("no unprotected container with free space nearby");
    }

    @Override
    protected boolean work(AbstractContainerMenu m) {
        int size = containerSize(m);
        boolean space = false;
        for (int i = 0; i < size; i++) if (m.getSlot(i).getItem().isEmpty()) space = true;
        if (!space) return true;
        for (int i = size; i < m.slots.size(); i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty() && items.contains(GameData.id(s.getItem()))) {
                if (!pacer.ready(i, 9)) return false; // S40: includes cursor travel to slot i
                ctx.playerController().windowClick(m.containerId, i, 0, ContainerInput.QUICK_MOVE, ctx.player());
                moved++;
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean succeeded() {
        if (moved == 0) setFailure("couldn't stash anything");
        return moved > 0;
    }

    @Override
    public String status() {
        return "stashing overflow in a chest";
    }
}
