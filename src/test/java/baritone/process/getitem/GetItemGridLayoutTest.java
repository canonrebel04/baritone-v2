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
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.tasks.GridLayout;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

/** A22: manual grid layout from dataset patterns. */
public class GetItemGridLayoutTest {
    static final RecipeGraph graph = new RecipeGraph(GetItemDataset.bundled());

    static Recipe byId(String id) {
        return graph.recipes().stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void pickaxePatternOnTable() {
        Recipe r = byId("minecraft:wooden_pickaxe");
        assertNotNull(r.pattern);
        String[] cells = GridLayout.layout(r, Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2), 3, 1);
        String p = "minecraft:oak_planks", s = "minecraft:stick";
        assertArrayEquals(new String[]{p, p, p, null, s, null, null, s, null}, cells);
    }

    @Test
    public void sticksInInventoryGrid() {
        Recipe r = byId("minecraft:stick");
        String[] cells = GridLayout.layout(r, Map.of("minecraft:birch_planks", 4), 2, 2);
        assertEquals(Arrays.asList("minecraft:birch_planks", null, "minecraft:birch_planks", null), Arrays.asList(cells));
    }

    @Test
    public void tableRecipeDoesNotFitInventoryGrid() {
        assertNull(GridLayout.layout(byId("minecraft:furnace"), Map.of("minecraft:cobblestone", 8), 2, 1));
    }

    @Test
    public void shapelessFillsInOrder() {
        Recipe r = byId("minecraft:oak_planks");
        String[] cells = GridLayout.layout(r, Map.of("minecraft:oak_log", 1), 2, 1);
        assertEquals("minecraft:oak_log", cells[0]);
        assertNull(cells[1]);
    }

    @Test
    public void mixedInputsSplitAcrossCells() {
        Recipe r = byId("minecraft:crafting_table");
        String[] cells = GridLayout.layout(r, Map.of("minecraft:oak_planks", 2, "minecraft:birch_planks", 2), 2, 1);
        long oak = Arrays.stream(cells).filter("minecraft:oak_planks"::equals).count();
        long birch = Arrays.stream(cells).filter("minecraft:birch_planks"::equals).count();
        assertEquals(2, oak);
        assertEquals(2, birch);
    }
}
