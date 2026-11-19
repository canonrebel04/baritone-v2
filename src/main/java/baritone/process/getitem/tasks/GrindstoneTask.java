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
import net.minecraft.world.inventory.GrindstoneMenu;

import java.util.Collections;
import java.util.Set;

/** A25: strip an item's enchantments on a grindstone (returns some XP; curses stay). */
public final class GrindstoneTask extends StationMenuTask<GrindstoneMenu> {
    private final int baseInv;
    private int resultWait;
    private boolean took;

    public GrindstoneTask(Baritone baritone, GameData data, String item, int baseInv, Set<BlockPos> blacklist) {
        super(baritone, data, PlanStep.operate("Clean " + item.replace("minecraft:", "") + " on a grindstone", "minecraft:grindstone",
                Collections.singletonList(item), 1), "minecraft:grindstone", GrindstoneMenu.class, 2, blacklist);
        this.baseInv = baseInv;
    }

    @Override
    protected PathingCommand work(GrindstoneMenu m) {
        if (took) return succeed();
        if (m.getSlot(0).getItem().isEmpty()) {
            int from = menuSlotOf(m, baseInv);
            if (from < 0 || m.getCarried().isEmpty() && m.getSlot(from).getItem().isEmpty()) return abort("the item to clean moved");
            place(m, from, 0);
            return pause();
        }
        if (!m.getCarried().isEmpty()) {
            if (pacer.ready()) putAway(m);
            return pause();
        }
        if (m.getSlot(2).getItem().isEmpty()) {
            if (++resultWait > 60) return abort("the grindstone can't clean that");
            return pause();
        }
        if (quick(m, 2)) took = true;
        return pause();
    }

    @Override
    public String status() {
        return step.label;
    }
}
