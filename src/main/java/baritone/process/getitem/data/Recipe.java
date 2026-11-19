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

package baritone.process.getitem.data;

import java.util.Collections;
import java.util.List;

/** A recipe (dataset or server recipe book), normalised for planning (plan A1). */
public final class Recipe {
    public enum Source { DATASET, SERVER }

    public final String id;
    public final RecipeType type;
    public final int width, height;
    public final List<Ingredient> ingredients;
    public final String output;
    public final int outputCount;
    public final int cookTime;
    public final double experience;
    /** The result carries data components (potions, enchantments, ...): not a plain item. */
    public final boolean componentResult;
    public final Source source;
    /** Shaped recipes: ingredient index per grid cell (row-major, {@link #width} wide, -1 empty); else null. */
    public final int[] pattern;

    public Recipe(String id, RecipeType type, int width, int height, List<Ingredient> ingredients, String output,
                  int outputCount, int cookTime, double experience, boolean componentResult, Source source) {
        this(id, type, width, height, ingredients, output, outputCount, cookTime, experience, componentResult, source, null);
    }

    public Recipe(String id, RecipeType type, int width, int height, List<Ingredient> ingredients, String output,
                  int outputCount, int cookTime, double experience, boolean componentResult, Source source, int[] pattern) {
        this.pattern = pattern;
        this.id = id;
        this.type = type;
        this.width = width;
        this.height = height;
        this.ingredients = Collections.unmodifiableList(ingredients);
        this.output = output;
        this.outputCount = Math.max(1, outputCount);
        this.cookTime = cookTime;
        this.experience = experience;
        this.componentResult = componentResult;
        this.source = source;
    }

    /** Crafting recipe that doesn't fit the 2x2 inventory grid. */
    public boolean needsTable() {
        return type.crafting && (width > 2 || height > 2 || totalItems() > 4);
    }

    /** The station block id this recipe needs, or null (2x2 inventory crafting). */
    public String station() {
        return type.station(needsTable());
    }

    public int totalItems() {
        int n = 0;
        for (Ingredient i : ingredients) n += i.count;
        return n;
    }

    /** Signature used to de-duplicate server recipes against the dataset. */
    public String signature() {
        StringBuilder sb = new StringBuilder(type.name()).append('|').append(output).append('x').append(outputCount);
        ingredients.stream().map(i -> i.count + ":" + String.join(",", i.options.stream().sorted().toList()))
                .sorted().forEach(s -> sb.append('|').append(s));
        return sb.toString();
    }

    @Override
    public String toString() {
        return id + "(" + type + " -> " + outputCount + "x" + output + ")";
    }
}
