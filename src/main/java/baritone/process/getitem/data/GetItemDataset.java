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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Vanilla recipe / loot / tag data generated at build time from the Minecraft jar (plan A1, see
 * {@code buildSrc/.../DatasetGenerator}). Server recipes are merged on top at runtime by the recipe graph.
 */
public final class GetItemDataset {
    public static final String RESOURCE = "/baritone/getitem/dataset.json.gz";

    /** One raw recipe ingredient: options are item ids or "#tag" references, resolved by the graph. */
    public static final class RawIngredient {
        public final List<String> options;
        public final int count;

        RawIngredient(List<String> options, int count) {
            this.options = options;
            this.count = count;
        }
    }

    public static final class RawRecipe {
        public String id, type, out;
        public int w, h, c = 1, cook;
        public double xp;
        public boolean nbt;
        public final List<RawIngredient> in = new ArrayList<>();
    }

    public final Map<String, List<String>> itemTags = new HashMap<>();
    public final Map<String, Set<String>> blockTags = new HashMap<>();
    public final List<RawRecipe> recipes = new ArrayList<>();
    /** block id -> (item id -> expected count per block broken, without silk touch / fortune) */
    public final Map<String, Map<String, Double>> loot = new HashMap<>();

    private static GetItemDataset bundled;

    public static synchronized GetItemDataset bundled() {
        if (bundled == null) {
            try (InputStream in = GetItemDataset.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IllegalStateException("Missing " + RESOURCE + " (generateGetItemDataset task)");
                bundled = read(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read the Get Item dataset", e);
            }
        }
        return bundled;
    }

    public static GetItemDataset read(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        GetItemDataset d = new GetItemDataset();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("itemTags").entrySet()) {
            d.itemTags.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("blockTags").entrySet()) {
            d.blockTags.put(e.getKey(), new LinkedHashSet<>(strings(e.getValue().getAsJsonArray())));
        }
        for (JsonElement el : root.getAsJsonArray("recipes")) {
            JsonObject o = el.getAsJsonObject();
            RawRecipe r = new RawRecipe();
            r.id = o.get("id").getAsString();
            r.type = o.get("type").getAsString();
            r.out = o.get("out").getAsString();
            r.c = o.has("c") ? o.get("c").getAsInt() : 1;
            r.w = o.has("w") ? o.get("w").getAsInt() : 0;
            r.h = o.has("h") ? o.get("h").getAsInt() : 0;
            r.cook = o.has("cook") ? o.get("cook").getAsInt() : 0;
            r.xp = o.has("xp") ? o.get("xp").getAsDouble() : 0;
            r.nbt = o.has("nbt") && o.get("nbt").getAsBoolean();
            for (JsonElement ie : o.getAsJsonArray("in")) {
                JsonObject io = ie.getAsJsonObject();
                JsonElement opt = io.get("o");
                List<String> options = opt.isJsonArray() ? strings(opt.getAsJsonArray()) : Collections.singletonList(opt.getAsString());
                r.in.add(new RawIngredient(options, io.has("n") ? io.get("n").getAsInt() : 1));
            }
            d.recipes.add(r);
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("loot").entrySet()) {
            Map<String, Double> drops = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> de : e.getValue().getAsJsonObject().entrySet()) {
                drops.put(de.getKey(), de.getValue().getAsDouble());
            }
            d.loot.put(e.getKey(), drops);
        }
        return d;
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }
}
