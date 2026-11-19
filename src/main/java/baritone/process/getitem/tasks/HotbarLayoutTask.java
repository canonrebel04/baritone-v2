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
import baritone.process.getitem.inventory.HotbarLayout;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A43: apply {@code getItemHotbarLayout} with paced swap clicks on the player inventory (between steps only). */
public final class HotbarLayoutTask extends StepTask {
    private final GuiPacer pacer = new GuiPacer();
    private int swaps;

    public HotbarLayoutTask(Baritone baritone, GameData data) {
        super(baritone, data, PlanStep.operate("Arrange the hotbar", null, Collections.emptyList(), 0));
    }

    /** The current inventory as layout items. */
    public static List<HotbarLayout.Item> items(net.minecraft.world.entity.player.Inventory inv) {
        List<HotbarLayout.Item> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            out.add(new HotbarLayout.Item(i, GameData.id(s.getItem()), s.has(DataComponents.FOOD), s.getItem() instanceof BlockItem));
        }
        return out;
    }

    @Override
    protected PathingCommand onTick() {
        if (baritone.getPathingBehavior().isPathing() || ctx.player().containerMenu != ctx.player().inventoryMenu) return finish();
        int[] swap = HotbarLayout.nextSwap(Baritone.settings().getItemHotbarLayout.value, items(ctx.player().getInventory()));
        if (swap == null || swaps > 9) return finish();
        if (!pacer.ready()) return pause();
        int menuSlot = swap[0] < 9 ? 36 + swap[0] : swap[0];
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, menuSlot, swap[1], ContainerInput.SWAP, ctx.player());
        swaps++;
        return pause();
    }

    @Override
    public String status() {
        return "arranging the hotbar";
    }
}
