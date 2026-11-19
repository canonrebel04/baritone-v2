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

import baritone.process.getitem.data.Ingredient;
import baritone.process.getitem.data.Recipe;
import baritone.process.getitem.data.RecipeType;

import java.util.*;

/** Pure grid layout for manual crafting (A22 fallback when the recipe book can't place the recipe). */
public final class GridLayout {
    private GridLayout() {
    }

    /**
     * Assign concrete input items to grid cells (row-major over {@code gridWidth}); null cells stay empty.
     * Shaped recipes follow the dataset pattern, shapeless ones fill cells in order.
     *
     * @return the cells, or null if the recipe doesn't fit the grid
     */
    public static String[] layout(Recipe recipe, Map<String, Integer> inputs, int gridWidth, int batches) {
        Map<String, Integer> pool = new LinkedHashMap<>(inputs);
        String[] cells = new String[gridWidth * gridWidth];
        List<Integer> ingredientPerCell = new ArrayList<>();
        List<Integer> cellIndex = new ArrayList<>();
        if (recipe.type == RecipeType.SHAPED) {
            if (recipe.pattern == null || recipe.width > gridWidth || recipe.height > gridWidth) return null;
            for (int i = 0; i < recipe.pattern.length; i++) {
                if (recipe.pattern[i] < 0) continue;
                int row = i / recipe.width, col = i % recipe.width;
                ingredientPerCell.add(recipe.pattern[i]);
                cellIndex.add(row * gridWidth + col);
            }
        } else {
            int c = 0;
            for (int ing = 0; ing < recipe.ingredients.size(); ing++) {
                for (int k = 0; k < recipe.ingredients.get(ing).count; k++) {
                    if (c >= cells.length) return null;
                    ingredientPerCell.add(ing);
                    cellIndex.add(c++);
                }
            }
        }
        for (int k = 0; k < ingredientPerCell.size(); k++) {
            Ingredient ing = recipe.ingredients.get(ingredientPerCell.get(k));
            String chosen = null;
            for (Map.Entry<String, Integer> e : pool.entrySet()) {
                if (ing.accepts(e.getKey()) && e.getValue() >= batches) {
                    chosen = e.getKey();
                    break;
                }
            }
            if (chosen == null) { // fall back: any accepted item (the inventory may hold more than planned)
                for (String o : ing.options) if (pool.containsKey(o)) { chosen = o; break; }
            }
            if (chosen == null) chosen = ing.options.get(0);
            if (pool.containsKey(chosen)) pool.merge(chosen, -batches, Integer::sum);
            cells[cellIndex.get(k)] = chosen;
        }
        return cells;
    }

}
