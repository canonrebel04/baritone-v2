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

package baritone.process.getitem.sources;

import baritone.process.getitem.plan.Source;

import java.util.*;

/**
 * Plan A31: composter chances (vanilla ComposterBlock table, common items) and the bone meal source built from
 * spare compostables in the inventory. Seven successful layers make one bone meal. Pure.
 */
public final class Compost {
    public static final Map<String, Double> CHANCE = new LinkedHashMap<>();

    private static void put(double chance, String... items) {
        for (String i : items) CHANCE.put("minecraft:" + i, chance);
    }

    static {
        put(0.3, "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "torchflower_seeds", "pitcher_pod",
                "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
                "mangrove_leaves", "cherry_leaves", "azalea_leaves", "pale_oak_leaves", "oak_sapling", "spruce_sapling",
                "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling",
                "mangrove_propagule", "short_grass", "kelp", "dried_kelp", "sweet_berries", "glow_berries", "moss_carpet",
                "hanging_roots", "mangrove_roots", "small_dripleaf", "seagrass");
        put(0.5, "cactus", "sugar_cane", "vine", "nether_sprouts", "weeping_vines", "twisting_vines", "tall_grass",
                "dried_kelp_block", "melon_slice", "glow_lichen");
        put(0.65, "wheat", "carrot", "potato", "beetroot", "apple", "melon", "pumpkin", "carved_pumpkin", "cocoa_beans",
                "lily_pad", "fern", "large_fern", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
                "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots",
                "moss_block", "sea_pickle", "spore_blossom", "big_dripleaf", "nether_wart", "shroomlight");
        put(0.85, "baked_potato", "bread", "cookie", "hay_block", "brown_mushroom_block", "red_mushroom_block",
                "nether_wart_block", "warped_wart_block", "flowering_azalea");
        put(1.0, "cake", "pumpkin_pie");
    }

    /** Expected items for one bone meal (seven layers). */
    public static double itemsPerBoneMeal(double chance) {
        return 7 / chance;
    }

    /**
     * Bone meal sources from compostables the plan may spend: each spare item kind becomes a source whose batch
     * is one bone meal costing its expected item count. Protected items, the goal itself and food the player may
     * need (bread, baked potatoes, pies) are skipped.
     */
    public static List<Source> sources(Map<String, Integer> inventory, Set<String> keep, double setupSeconds, String ref) {
        return sources(inventory, keep, setupSeconds, ref, null);
    }

    /** {@code tool} is a block that must be held / placed (a composter) but is not consumed per batch; null if one is already there. */
    public static List<Source> sources(Map<String, Integer> inventory, Set<String> keep, double setupSeconds, String ref, String tool) {
        List<Source> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            Double ch = CHANCE.get(e.getKey());
            if (ch == null || keep.contains(e.getKey()) || ch >= 0.85) continue;
            int per = (int) Math.ceil(itemsPerBoneMeal(ch));
            int batches = e.getValue() / per;
            if (batches <= 0) continue;
            Map<String, Integer> in = new LinkedHashMap<>();
            in.put(e.getKey(), per);
            out.add(new Source(Source.Kind.COMPOST, "minecraft:bone_meal", 1, per * 0.4 + 1, setupSeconds, batches, in, tool, ref,
                    "Compost " + e.getKey().replace("minecraft:", "")));
        }
        return out;
    }
}
