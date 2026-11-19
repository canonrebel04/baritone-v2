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

import baritone.process.getitem.data.*;

import java.util.*;

/**
 * Headless acquisition graph (plan 4.2): items are nodes, recipes and block loot are the edges that produce them.
 * Built from the bundled dataset plus any recipes the server revealed at runtime (plan A1 merge).
 */
public final class RecipeGraph {
    /** A natural block whose loot yields an item. */
    public static final class MineSource {
        public final String block;
        /** Expected items per block broken (no Silk Touch / Fortune). */
        public final double expected;

        MineSource(String block, double expected) {
            this.block = block;
            this.expected = expected;
        }

        @Override
        public String toString() {
            return block + "~" + expected;
        }
    }

    private final GetItemDataset dataset;
    private final TagLookup tags;
    private final List<Recipe> recipes = new ArrayList<>();
    private final Map<String, List<Recipe>> byOutput = new HashMap<>();
    private final Map<String, List<MineSource>> mineSources = new HashMap<>();
    private final Set<String> signatures = new HashSet<>();
    private final Set<String> items = new TreeSet<>();
    private int serverRecipes;

    public RecipeGraph(GetItemDataset dataset, TagLookup tags) {
        this.dataset = dataset;
        this.tags = tags;
        for (GetItemDataset.RawRecipe raw : dataset.recipes) {
            Recipe r = resolve(raw, Recipe.Source.DATASET);
            if (r != null) add(r);
        }
        for (Map.Entry<String, Map<String, Double>> e : dataset.loot.entrySet()) {
            for (Map.Entry<String, Double> d : e.getValue().entrySet()) {
                if (d.getValue() <= 0) continue;
                mineSources.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(new MineSource(e.getKey(), d.getValue()));
                items.add(d.getKey());
            }
        }
        mineSources.values().forEach(l -> l.sort(Comparator.comparing(s -> s.block)));
    }

    /** Graph over the bundled vanilla data with vanilla tags. */
    public static RecipeGraph vanilla() {
        GetItemDataset d = GetItemDataset.bundled();
        return new RecipeGraph(d, TagLookup.of(d.itemTags));
    }

    public RecipeGraph(GetItemDataset dataset) {
        this(dataset, TagLookup.of(dataset.itemTags));
    }

    private Recipe resolve(GetItemDataset.RawRecipe raw, Recipe.Source source) {
        RecipeType type = RecipeType.fromDataset(raw.type);
        if (type == null) return null;
        List<Ingredient> ingredients = new ArrayList<>();
        for (GetItemDataset.RawIngredient ri : raw.in) {
            List<String> options = new ArrayList<>();
            String tag = null;
            for (String o : ri.options) {
                if (o.startsWith("#")) {
                    if (ri.options.size() == 1) tag = o.substring(1);
                    for (String i : tags.items(o.substring(1))) if (!options.contains(i)) options.add(i);
                } else if (!options.contains(o)) {
                    options.add(o);
                }
            }
            if (options.isEmpty()) return null; // unknown tag: can't plan with it
            ingredients.add(new Ingredient(options, tag, ri.count));
        }
        return new Recipe(raw.id, type, raw.w, raw.h, ingredients, raw.out, raw.c, raw.cook, raw.xp, raw.nbt, source);
    }

    private boolean add(Recipe r) {
        if (!signatures.add(r.signature())) return false;
        recipes.add(r);
        items.add(r.output);
        r.ingredients.forEach(i -> items.addAll(i.options));
        if (!r.componentResult) byOutput.computeIfAbsent(r.output, k -> new ArrayList<>()).add(r);
        return true;
    }

    /**
     * Merge a recipe revealed by the server (recipe book). Recipes the dataset already has are ignored.
     *
     * @return whether it was new
     */
    public boolean addServerRecipe(Recipe r) {
        boolean added = add(r);
        if (added) serverRecipes++;
        return added;
    }

    public int serverRecipeCount() {
        return serverRecipes;
    }

    public List<Recipe> recipes() {
        return Collections.unmodifiableList(recipes);
    }

    /** Recipes producing this item as a plain stack (component results excluded). */
    public List<Recipe> producers(String item) {
        return byOutput.getOrDefault(item, Collections.emptyList());
    }

    public List<MineSource> mineSources(String item) {
        return mineSources.getOrDefault(item, Collections.emptyList());
    }

    /** Expected drops of a block (item -> count per block), empty if unknown. */
    public Map<String, Double> drops(String block) {
        return dataset.loot.getOrDefault(block, Collections.emptyMap());
    }

    public Set<String> blockTag(String tag) {
        return dataset.blockTags.getOrDefault(tag, Collections.emptySet());
    }

    public List<String> itemTag(String tag) {
        return tags.items(tag);
    }

    public boolean knows(String item) {
        return items.contains(item);
    }

    public Set<String> items() {
        return Collections.unmodifiableSet(items);
    }
}
