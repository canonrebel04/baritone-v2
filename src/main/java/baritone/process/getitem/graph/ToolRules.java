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
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Which tool a block needs to drop anything (plan A4 tool-tier prerequisites), derived from the vanilla
 * {@code mineable/*} and {@code incorrect_for_*_tool} block tags. At runtime the game's own answers
 * ({@code requiresCorrectToolForDrops}, {@code isCorrectToolForDrops}) can be plugged in instead.
 */
public final class ToolRules {
    /** Pickaxes cheapest-tier first, each with the tag of blocks it can't harvest (null = harvests all). */
    public static final String[][] PICKAXES = {
            {"minecraft:wooden_pickaxe", "minecraft:incorrect_for_wooden_tool"},
            {"minecraft:golden_pickaxe", "minecraft:incorrect_for_gold_tool"},
            {"minecraft:stone_pickaxe", "minecraft:incorrect_for_stone_tool"},
            {"minecraft:copper_pickaxe", "minecraft:incorrect_for_copper_tool"},
            {"minecraft:iron_pickaxe", "minecraft:incorrect_for_iron_tool"},
            {"minecraft:diamond_pickaxe", "minecraft:incorrect_for_diamond_tool"},
            {"minecraft:netherite_pickaxe", null},
    };
    public static final String[] SHOVELS = {
            "minecraft:wooden_shovel", "minecraft:golden_shovel", "minecraft:stone_shovel", "minecraft:copper_shovel",
            "minecraft:iron_shovel", "minecraft:diamond_shovel", "minecraft:netherite_shovel"
    };
    /** Non-pickaxe blocks that still require a tool to drop (vanilla). */
    private static final Set<String> SHOVEL_REQUIRED = Set.of("minecraft:snow", "minecraft:snow_block");

    private final Predicate<String> requiresTool;
    private final BiPredicate<String, String> canHarvest;

    public ToolRules(Predicate<String> requiresTool, BiPredicate<String, String> canHarvest) {
        this.requiresTool = requiresTool;
        this.canHarvest = canHarvest;
    }

    /** Rules from the dataset's block tags (headless / fallback). */
    public static ToolRules fromTags(RecipeGraph graph) {
        Set<String> pickaxe = graph.blockTag("minecraft:mineable/pickaxe");
        Map<String, Set<String>> incorrect = new HashMap<>();
        for (String[] p : PICKAXES) {
            incorrect.put(p[0], p[1] == null ? Collections.emptySet() : graph.blockTag(p[1]));
        }
        return new ToolRules(
                block -> pickaxe.contains(block) || SHOVEL_REQUIRED.contains(block),
                (tool, block) -> {
                    if (SHOVEL_REQUIRED.contains(block)) return Arrays.asList(SHOVELS).contains(tool);
                    Set<String> bad = incorrect.get(tool);
                    return bad != null && !bad.contains(block);
                });
    }

    public boolean requiresTool(String block) {
        return requiresTool.test(block);
    }

    public boolean canHarvest(String tool, String block) {
        return canHarvest.test(tool, block);
    }

    /** Tools that harvest the block, cheapest tier first; empty when no tool is needed. */
    public List<String> acceptableTools(String block) {
        if (!requiresTool(block)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (SHOVEL_REQUIRED.contains(block)) {
            out.addAll(Arrays.asList(SHOVELS));
            return out;
        }
        for (String[] p : PICKAXES) if (canHarvest(p[0], block)) out.add(p[0]);
        return out;
    }
}
