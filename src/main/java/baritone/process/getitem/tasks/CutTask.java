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
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A26: stonecutter. Puts exactly the planned input blocks in, selects the recipe whose output is the planned item
 * (a menu button, like clicking the icon) and shift-takes the result, which cuts the whole input.
 */
public final class CutTask extends StationMenuTask<StonecutterMenu> {
    private final String input;
    private final int target;
    private boolean loaded;

    public CutTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step, "minecraft:stonecutter", StonecutterMenu.class, 1, blacklist);
        String in = null;
        for (Map.Entry<String, Integer> e : step.inputs.entrySet()) in = e.getKey();
        this.input = in;
        this.target = data.count(step.items) + step.count;
    }

    @Override
    protected PathingCommand work(StonecutterMenu m) {
        if (input == null) return abort("no input for " + step.recipe.id);
        if (data.count(step.items) >= target && m.getSlot(1).getItem().isEmpty()) return succeed();
        if (!loaded) {
            int r = fill(m, 0, s -> input.equals(id(s)), step.batches);
            if (r < 0) return abort("no " + input.replace("minecraft:", "") + " to cut");
            if (r == 0) return pause();
            loaded = true;
            return pause();
        }
        if (m.getSlot(0).getItem().isEmpty()) {
            return data.count(step.items) >= target ? succeed() : abort("cut fewer " + step.items.get(0).replace("minecraft:", "") + " than planned");
        }
        int want = recipeIndex(m);
        if (want < 0) {
            if (openTicks() > 60) return abort("the stonecutter doesn't offer " + step.items.get(0).replace("minecraft:", ""));
            return pause();
        }
        if (m.getSelectedRecipeIndex() != want) {
            if (pacer.ready()) ctx.minecraft().gameMode.handleInventoryButtonClick(m.containerId, want);
            return pause();
        }
        if (m.getSlot(1).getItem().isEmpty()) return pause();
        quick(m, 1);
        return pause();
    }

    private int recipeIndex(StonecutterMenu m) {
        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = m.getVisibleRecipes().entries();
        var context = SlotDisplayContext.fromLevel(ctx.world());
        String out = step.items.get(0);
        for (int i = 0; i < entries.size(); i++) {
            ItemStack s = entries.get(i).recipe().optionDisplay().resolveForFirstStack(context);
            if (!s.isEmpty() && out.equals(GameData.id(s.getItem())) && s.getCount() == step.recipe.outputCount) return i;
        }
        for (int i = 0; i < entries.size(); i++) {
            ItemStack s = entries.get(i).recipe().optionDisplay().resolveForFirstStack(context);
            if (!s.isEmpty() && out.equals(GameData.id(s.getItem()))) return i;
        }
        return -1;
    }

    @Override
    public String status() {
        return "cutting " + step.items.get(0).replace("minecraft:", "") + " " + Math.min(data.count(step.items), target) + "/" + target;
    }
}
