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
import baritone.process.getitem.data.Ingredient;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.SmithingMenu;

import java.util.Set;

/**
 * A26: smithing table (netherite upgrades). One batch at a time: template, base, material into slots 0-2, take
 * the result. The base keeps its enchantments, name and damage, so the best matching copy is not chosen
 * specially: the first one in the inventory is used. Armor trims are not planned.
 */
public final class SmithTask extends StationMenuTask<SmithingMenu> {
    private final int target;
    private int made;

    public SmithTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step, "minecraft:smithing_table", SmithingMenu.class, 3, blacklist);
        this.target = data.count(step.items) + step.count;
    }

    @Override
    protected PathingCommand work(SmithingMenu m) {
        if (data.count(step.items) >= target || made >= step.batches) {
            return data.count(step.items) >= target ? succeed() : abort("smithed fewer than planned");
        }
        if (!m.getSlot(3).getItem().isEmpty()) {
            if (quick(m, 3)) made++;
            return pause();
        }
        for (int i = 0; i < 3 && i < step.recipe.ingredients.size(); i++) {
            Ingredient ing = step.recipe.ingredients.get(i);
            int r = fill(m, i, s -> ing.accepts(id(s)), 1);
            if (r < 0) return abort("no " + ing.options.get(0).replace("minecraft:", "") + " for the smithing table");
            if (r == 0) return pause();
        }
        if (openTicks() > 400) return abort("the smithing table gave no result");
        return pause();
    }

    @Override
    public String status() {
        return "smithing " + step.items.get(0).replace("minecraft:", "") + " " + Math.min(data.count(step.items), target) + "/" + target;
    }
}
