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
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;

/** A43: throw away junk stacks (inventory indices) to free slots, one paced click per stack. */
public final class DropJunkTask extends StepTask {
    private final List<Integer> slots;
    private final GuiPacer pacer = new GuiPacer();
    private int next;

    public DropJunkTask(Baritone baritone, GameData data, List<Integer> inventorySlots) {
        super(baritone, data, PlanStep.withdraw("minecraft:air", 0, 0, 0));
        this.slots = new ArrayList<>(inventorySlots);
    }

    @Override
    protected PathingCommand onTick() {
        if (ctx.minecraft().gui.screen() != null) return pause();
        if (next >= slots.size()) return finish();
        if (!pacer.ready()) return pause();
        int inv = slots.get(next++);
        int menuSlot = inv < 9 ? 36 + inv : inv; // InventoryMenu: 9-35 main, 36-44 hotbar
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, menuSlot, 1, ContainerInput.THROW, ctx.player());
        return pause();
    }

    @Override
    public String status() {
        return "dropping junk (" + next + "/" + slots.size() + ")";
    }
}
