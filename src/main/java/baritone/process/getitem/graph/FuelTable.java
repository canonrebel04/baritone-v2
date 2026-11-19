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

package baritone.process.getitem.graph;

import java.util.*;
import java.util.function.ToIntFunction;

/** Furnace burn times in ticks (plan A23 fuel planning). Runtime replaces this with {@code level.fuelValues()}. */
public final class FuelTable {
    private static final Map<String, Integer> VANILLA = new LinkedHashMap<>();

    static {
        VANILLA.put("minecraft:coal_block", 16000);
        VANILLA.put("minecraft:coal", 1600);
        VANILLA.put("minecraft:charcoal", 1600);
        VANILLA.put("minecraft:blaze_rod", 2400);
        VANILLA.put("minecraft:dried_kelp_block", 4000);
        VANILLA.put("minecraft:stick", 100);
        VANILLA.put("minecraft:bamboo", 50);
    }

    private final ToIntFunction<String> burnTime;

    public FuelTable(ToIntFunction<String> burnTime) {
        this.burnTime = burnTime;
    }

    /** Vanilla values, with planks/logs/wood from the given tags at 300 ticks. */
    public static FuelTable vanilla(RecipeGraph graph) {
        Map<String, Integer> map = new HashMap<>(VANILLA);
        for (String tag : new String[]{"minecraft:planks", "minecraft:logs_that_burn", "minecraft:wooden_slabs"}) {
            int t = tag.endsWith("slabs") ? 150 : 300;
            for (String i : graph.itemTag(tag)) map.putIfAbsent(i, t);
        }
        return new FuelTable(i -> map.getOrDefault(i, 0));
    }

    public int burnTicks(String item) {
        return burnTime.applyAsInt(item);
    }

    public boolean isFuel(String item) {
        return burnTicks(item) > 0;
    }
}
