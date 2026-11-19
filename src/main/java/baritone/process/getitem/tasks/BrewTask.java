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
import baritone.process.getitem.advanced.StackDesc;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A27: one brewing stand cycle: blaze powder into an empty fuel slot, up to three bottles of the current stage,
 * the ingredient, wait for the 20 s brew, take the bottles back (the fuel stays). Stands already holding someone
 * else's bottles are skipped.
 */
public final class BrewTask extends StationMenuTask<BrewingStandMenu> {
    private static final String FUEL = "minecraft:blaze_powder";
    private final StackDesc from;
    private final String ingredient;
    private final int bottles;
    private final Set<BlockPos> blacklist;
    private boolean started;
    private int fuelLeft = -1;
    private int waited;

    public BrewTask(Baritone baritone, GameData data, StackDesc from, String ingredient, int bottles, Set<BlockPos> blacklist) {
        super(baritone, data, PlanStep.operate("Brew " + bottles + "x " + from.potion + " + " + ingredient.replace("minecraft:", ""),
                "minecraft:brewing_stand", Collections.singletonList(from.item), bottles), "minecraft:brewing_stand", BrewingStandMenu.class, 4, blacklist);
        this.from = from;
        this.ingredient = ingredient;
        this.bottles = bottles;
        this.blacklist = blacklist;
    }

    /** Fuel charges left in the stand when the task ended (-1 = unknown). */
    public int fuelLeft() {
        return fuelLeft;
    }

    private Predicate<ItemStack> ours() {
        return s -> {
            if (s.isEmpty()) return false;
            StackDesc d = GameData.describe(-1, s);
            return d.item.equals(from.item) && java.util.Objects.equals(d.potion, from.potion);
        };
    }

    @Override
    protected PathingCommand work(BrewingStandMenu m) {
        fuelLeft = m.getFuel();
        if (!started) {
            for (int i = 0; i < 3; i++) {
                ItemStack s = m.getSlot(i).getItem();
                if (!s.isEmpty() && !ours().test(s)) {
                    if (stationPos() != null) blacklist.add(stationPos());
                    return abort("that brewing stand is in use; will use another");
                }
            }
            if (m.getFuel() <= 0) {
                int r = fill(m, 4, s -> FUEL.equals(id(s)), 1);
                if (r < 0) return abort("no blaze powder for the brewing stand");
                if (r == 0) return pause();
            }
            for (int i = 0; i < bottles; i++) {
                int r = fill(m, i, ours(), 1);
                if (r < 0) {
                    if (i == 0) return abort("no " + from.potion + " bottles to brew");
                    break;
                }
                if (r == 0) return pause();
            }
            int r = fill(m, 3, s -> ingredient.equals(id(s)), 1);
            if (r < 0) return abort("no " + ingredient.replace("minecraft:", "") + " to brew with");
            if (r == 0) return pause();
            started = true;
            return pause();
        }
        waited++;
        if (m.getBrewingTicks() > 0) return pause();
        if (m.getSlot(3).getItem().isEmpty() || waited > 500) {
            if (!m.getSlot(3).getItem().isEmpty()) return abort("the brewing stand didn't brew (" + ingredient.replace("minecraft:", "") + " doesn't apply?)");
            return succeed();
        }
        if (waited > 60 && m.getBrewingTicks() == 0 && m.getFuel() == 0) return abort("the brewing stand has no fuel");
        return pause();
    }

    @Override
    protected boolean keepInStation(int slot) {
        return slot == 4;
    }

    @Override
    public String status() {
        return "brewing " + from.potion + " + " + ingredient.replace("minecraft:", "") + (started ? " (waiting)" : "");
    }
}
