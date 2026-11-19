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

package baritone.process.getitem;

import baritone.process.getitem.data.GetItemDataset;
import baritone.process.getitem.data.Recipe;
import baritone.process.getitem.data.RecipeType;
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.graph.ToolRules;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** A1: the bundled dataset loads and has the shape the planner relies on. */
public class GetItemDatasetTest {
    static RecipeGraph graph;

    @BeforeClass
    public static void load() {
        graph = new RecipeGraph(GetItemDataset.bundled());
    }

    @Test
    public void datasetHasRecipesLootAndTags() {
        GetItemDataset d = GetItemDataset.bundled();
        assertTrue(d.recipes.size() > 1500);
        assertTrue(d.loot.size() > 1000);
        assertTrue(d.itemTags.get("minecraft:planks").contains("minecraft:oak_planks"));
        assertTrue(d.blockTags.get("minecraft:mineable/pickaxe").contains("minecraft:stone"));
    }

    @Test
    public void tagIngredientsAreResolved() {
        Recipe sticks = graph.producers("minecraft:stick").stream().filter(r -> r.id.equals("minecraft:stick")).findFirst().orElseThrow();
        assertEquals(RecipeType.SHAPED, sticks.type);
        assertEquals(4, sticks.outputCount);
        assertEquals("minecraft:planks", sticks.ingredients.get(0).tag);
        assertTrue(sticks.ingredients.get(0).accepts("minecraft:birch_planks"));
        assertFalse(sticks.needsTable());
        Recipe pick = graph.producers("minecraft:iron_pickaxe").get(0);
        assertTrue(pick.needsTable());
        assertEquals("minecraft:crafting_table", pick.station());
    }

    @Test
    public void lootExpectations() {
        assertEquals(1.0, graph.drops("minecraft:stone").get("minecraft:cobblestone"), 1e-9);
        assertEquals(1.0, graph.drops("minecraft:iron_ore").get("minecraft:raw_iron"), 1e-9);
        assertEquals(0.1, graph.drops("minecraft:gravel").get("minecraft:flint"), 1e-9);
        assertTrue(graph.drops("minecraft:lapis_ore").get("minecraft:lapis_lazuli") > 4);
        List<RecipeGraph.MineSource> coal = graph.mineSources("minecraft:coal");
        assertTrue(coal.stream().anyMatch(s -> s.block.equals("minecraft:deepslate_coal_ore")));
    }

    @Test
    public void toolTiersFromTags() {
        ToolRules t = ToolRules.fromTags(graph);
        assertTrue(t.acceptableTools("minecraft:oak_log").isEmpty());
        assertEquals("minecraft:wooden_pickaxe", t.acceptableTools("minecraft:stone").get(0));
        List<String> iron = t.acceptableTools("minecraft:iron_ore");
        assertFalse(iron.contains("minecraft:wooden_pickaxe"));
        assertTrue(iron.contains("minecraft:stone_pickaxe"));
        List<String> diamond = t.acceptableTools("minecraft:diamond_ore");
        assertFalse(diamond.contains("minecraft:stone_pickaxe"));
        assertTrue(diamond.contains("minecraft:iron_pickaxe"));
        assertEquals(List.of("minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), t.acceptableTools("minecraft:obsidian"));
    }

    @Test
    public void serverRecipesMergeWithoutDuplicates() {
        RecipeGraph g = new RecipeGraph(GetItemDataset.bundled());
        Recipe existing = g.producers("minecraft:stick").get(0);
        assertFalse(g.addServerRecipe(new Recipe("server:dupe", existing.type, existing.width, existing.height,
                existing.ingredients, existing.output, existing.outputCount, 0, 0, false, Recipe.Source.SERVER)));
        Recipe custom = new Recipe("server:dirt_to_diamond", RecipeType.SHAPELESS, 0, 0,
                List.of(new baritone.process.getitem.data.Ingredient(List.of("minecraft:dirt"), null, 4)),
                "minecraft:diamond", 1, 0, 0, false, Recipe.Source.SERVER);
        assertTrue(g.addServerRecipe(custom));
        assertEquals(1, g.serverRecipeCount());
        assertTrue(g.producers("minecraft:diamond").contains(custom));
    }
}
