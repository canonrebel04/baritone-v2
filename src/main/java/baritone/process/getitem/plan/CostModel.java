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

package baritone.process.getitem.plan;

import baritone.process.getitem.graph.RecipeGraph;

import java.util.*;

/**
 * Rough time cost (seconds) of each kind of acquisition step (plan 4.2 cost model). Only natural blocks are
 * mineable: blocks a player could have placed (planks, furnaces, glass, ...) are never planned as mine targets, so
 * Get Item never digs up builds.
 */
public final class CostModel {
    public static final double INF = Double.POSITIVE_INFINITY;

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    /** Seconds per crafting batch (grid fill + take). */
    public double craftSecondsPerBatch = 1.5;
    /** Opening a station / walking to it, per step. */
    public double stationUseSeconds = 4;
    /** Placing a station block from the inventory. */
    public double placeStationSeconds = 3;
    /** Cost multiplier for blocks only found in another dimension (Nether/End allowed but expensive). */
    public double otherDimensionFactor = 12;

    private static final String[][] TAG_COSTS = {
            {"minecraft:logs", "8"},
            {"minecraft:dirt", "3"},
            {"minecraft:sand", "3"},
            {"minecraft:base_stone_overworld", "3"},
            {"minecraft:base_stone_nether", "3"},
            {"minecraft:terracotta", "10"},
            {"minecraft:coal_ores", "25"},
            {"minecraft:copper_ores", "30"},
            {"minecraft:iron_ores", "45"},
            {"minecraft:gold_ores", "120"},
            {"minecraft:redstone_ores", "90"},
            {"minecraft:lapis_ores", "150"},
            {"minecraft:diamond_ores", "400"},
            {"minecraft:emerald_ores", "1500"},
            {"minecraft:ice", "20"},
            {"minecraft:snow", "6"},
            {"minecraft:leaves", "4"},
    };
    private static final Object[][] EXPLICIT = {
            {"minecraft:gravel", 3.0}, {"minecraft:clay", 15.0}, {"minecraft:obsidian", 60.0},
            {"minecraft:end_stone", 3.0}, {"minecraft:glowstone", 20.0}, {"minecraft:magma_block", 15.0},
            {"minecraft:soul_sand", 4.0}, {"minecraft:soul_soil", 4.0}, {"minecraft:smooth_basalt", 8.0},
            {"minecraft:calcite", 15.0}, {"minecraft:dripstone_block", 15.0}, {"minecraft:pointed_dripstone", 15.0},
            {"minecraft:moss_block", 20.0}, {"minecraft:mud", 15.0}, {"minecraft:pumpkin", 30.0},
            {"minecraft:melon", 30.0}, {"minecraft:nether_quartz_ore", 15.0}, {"minecraft:ancient_debris", 900.0},
            {"minecraft:amethyst_cluster", 120.0}, {"minecraft:sugar_cane", 15.0}, {"minecraft:cactus", 20.0},
            {"minecraft:bamboo", 15.0}, {"minecraft:short_grass", 2.0}, {"minecraft:tall_grass", 2.0},
            {"minecraft:snow_block", 6.0}, {"minecraft:sculk", 60.0}, {"minecraft:mycelium", 40.0},
            {"minecraft:podzol", 20.0}, {"minecraft:red_sand", 10.0}, {"minecraft:crimson_nylium", 6.0},
            {"minecraft:warped_nylium", 6.0}, {"minecraft:nether_wart_block", 15.0}, {"minecraft:warped_wart_block", 15.0},
            {"minecraft:shroomlight", 30.0}, {"minecraft:brown_mushroom", 30.0}, {"minecraft:red_mushroom", 30.0},
    };
    private static final Set<String> NETHER_BLOCKS = Set.of(
            "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore", "minecraft:ancient_debris", "minecraft:glowstone",
            "minecraft:magma_block", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:crimson_nylium",
            "minecraft:warped_nylium", "minecraft:nether_wart_block", "minecraft:warped_wart_block", "minecraft:shroomlight",
            "minecraft:crimson_stem", "minecraft:warped_stem");
    private static final Set<String> END_BLOCKS = Set.of("minecraft:end_stone");

    private final Map<String, Double> blockCost = new HashMap<>();
    private final Set<String> netherStone;

    public CostModel(RecipeGraph graph) {
        for (String[] tc : TAG_COSTS) {
            double c = Double.parseDouble(tc[1]);
            for (String b : graph.blockTag(tc[0])) blockCost.merge(b, c, Math::max);
        }
        // stripped logs and 6-sided wood only exist when a player made them
        blockCost.keySet().removeIf(b -> b.contains("stripped_") || b.endsWith("_wood") || b.endsWith("_hyphae"));
        for (Object[] e : EXPLICIT) blockCost.put((String) e[0], (Double) e[1]);
        netherStone = graph.blockTag("minecraft:base_stone_nether");
        // nether ores live in netherrack, which makes them cheap; keep gold-in-nether cheap too
        blockCost.put("minecraft:nether_gold_ore", 20.0);
    }

    /** Seconds to find + break one block, or INF when it isn't a natural block. */
    public double blockSeconds(String block) {
        return blockCost.getOrDefault(block, INF);
    }

    public boolean isNatural(String block) {
        return blockCost.containsKey(block);
    }

    /** The only dimension the block generates in, or null if it occurs in the overworld. */
    public String exclusiveDimension(String block) {
        if (NETHER_BLOCKS.contains(block) || netherStone.contains(block)) return NETHER;
        if (END_BLOCKS.contains(block)) return END;
        return null;
    }

    /** Block cost from the given dimension, including the other-dimension penalty. */
    public double blockSeconds(String block, String dimension) {
        double c = blockSeconds(block);
        String only = exclusiveDimension(block);
        String here = dimension == null ? OVERWORLD : dimension;
        if (only != null && !only.equals(here)) return c * otherDimensionFactor;
        // overworld-only blocks while standing in the nether/end
        if (only == null && !OVERWORLD.equals(here) && !(NETHER.equals(here) && block.contains("gravel"))) {
            return c * otherDimensionFactor;
        }
        return c;
    }

    /** Cost multiplier for natural blocks that haven't been seen near the player (when that is known). */
    public double unseenFactor = 4;

    /** Block cost given what's been seen nearby ({@code seen} null = unknown, no penalty). */
    public double blockSeconds(String block, String dimension, java.util.Set<String> seen) {
        double c = blockSeconds(block, dimension);
        if (seen != null && !seen.contains(block)) c *= unseenFactor;
        return c;
    }

    /** Seconds per item for a cooking recipe (furnace time; 200 ticks = 10s). */
    public double cookSeconds(int cookTicks) {
        return (cookTicks <= 0 ? 200 : cookTicks) / 20.0;
    }
}
