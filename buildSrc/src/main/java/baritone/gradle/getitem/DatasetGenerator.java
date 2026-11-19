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

package baritone.gradle.getitem;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Plan A1: build-time extractor of the vanilla recipe / block-loot / tag data from the Minecraft jar into a compact
 * dataset bundled with Baritone ({@code baritone/getitem/dataset.json.gz}). The 1.21.2+ client only knows recipes
 * the server reveals through the recipe book, so the Get Item planner needs this; at runtime it is merged with the
 * server's recipe book (datapack recipes) and real game tags.
 *
 * <p>Output format 1:
 * <pre>
 * { "format": 1,
 *   "itemTags":  { "minecraft:planks": ["minecraft:oak_planks", ...] },      // fully resolved
 *   "blockTags": { "minecraft:mineable/pickaxe": [...], ... },                // selected, resolved
 *   "recipes":   [ { "id", "type", "w", "h", "in": [ { "o": "id" | "#tag" | [..], "n": count } ], "out", "c", "cook", "xp" } ],
 *   "loot":      { "minecraft:stone": { "minecraft:cobblestone": 1.0 } }      // expected drops, no silk/fortune/shears
 * }
 * </pre>
 */
public final class DatasetGenerator {
    public static final int FORMAT = 1;

    private static final String RECIPES = "data/minecraft/recipe/";
    private static final String LOOT = "data/minecraft/loot_table/blocks/";
    private static final String ITEM_TAGS = "data/minecraft/tags/item/";
    private static final String BLOCK_TAGS = "data/minecraft/tags/block/";

    /** Block tags the planner uses (tool tiers, natural-block detection, ores). */
    static final List<String> BLOCK_TAGS_EXPORTED = List.of(
            "mineable/pickaxe", "mineable/axe", "mineable/shovel", "mineable/hoe",
            "incorrect_for_wooden_tool", "incorrect_for_stone_tool", "incorrect_for_copper_tool",
            "incorrect_for_iron_tool", "incorrect_for_diamond_tool", "incorrect_for_gold_tool",
            "logs", "leaves", "dirt", "sand", "base_stone_overworld", "base_stone_nether", "terracotta",
            "coal_ores", "iron_ores", "copper_ores", "gold_ores", "redstone_ores", "lapis_ores", "diamond_ores",
            "emerald_ores", "wool", "ice", "snow"
    );

    private DatasetGenerator() {
    }

    /** Generates the dataset JSON from a Minecraft jar. */
    public static JsonObject generate(File minecraftJar) throws IOException {
        try (ZipFile zip = new ZipFile(minecraftJar)) {
            Map<String, JsonObject> files = new TreeMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".json")) continue;
                if (!(name.startsWith(RECIPES) || name.startsWith(LOOT) || name.startsWith(ITEM_TAGS) || name.startsWith(BLOCK_TAGS))) continue;
                try (Reader r = new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el.isJsonObject()) files.put(name, el.getAsJsonObject());
                }
            }
            return generate(files);
        }
    }

    /** Generates from already-read data files (path inside the jar -> JSON); used by tests. */
    public static JsonObject generate(Map<String, JsonObject> files) {
        Map<String, JsonObject> itemTagFiles = new TreeMap<>();
        Map<String, JsonObject> blockTagFiles = new TreeMap<>();
        Map<String, JsonObject> recipeFiles = new TreeMap<>();
        Map<String, JsonObject> lootFiles = new TreeMap<>();
        for (Map.Entry<String, JsonObject> f : files.entrySet()) {
            String p = f.getKey();
            if (p.startsWith(ITEM_TAGS)) itemTagFiles.put("minecraft:" + strip(p, ITEM_TAGS), f.getValue());
            else if (p.startsWith(BLOCK_TAGS)) blockTagFiles.put("minecraft:" + strip(p, BLOCK_TAGS), f.getValue());
            else if (p.startsWith(RECIPES)) recipeFiles.put("minecraft:" + strip(p, RECIPES), f.getValue());
            else if (p.startsWith(LOOT)) lootFiles.put("minecraft:" + strip(p, LOOT), f.getValue());
        }

        JsonObject out = new JsonObject();
        out.addProperty("format", FORMAT);

        JsonObject itemTags = new JsonObject();
        for (String tag : itemTagFiles.keySet()) {
            itemTags.add(tag, toArray(resolveTag(tag, itemTagFiles, new HashSet<>())));
        }
        out.add("itemTags", itemTags);

        JsonObject blockTags = new JsonObject();
        for (String t : BLOCK_TAGS_EXPORTED) {
            String tag = "minecraft:" + t;
            if (blockTagFiles.containsKey(tag)) blockTags.add(tag, toArray(resolveTag(tag, blockTagFiles, new HashSet<>())));
        }
        out.add("blockTags", blockTags);

        JsonArray recipes = new JsonArray();
        for (Map.Entry<String, JsonObject> r : recipeFiles.entrySet()) {
            JsonObject conv = convertRecipe(r.getKey(), r.getValue());
            if (conv != null) recipes.add(conv);
        }
        out.add("recipes", recipes);

        JsonObject loot = new JsonObject();
        for (Map.Entry<String, JsonObject> l : lootFiles.entrySet()) {
            Map<String, Double> drops = new TreeMap<>();
            evalTable(l.getValue(), drops);
            if (drops.isEmpty()) continue;
            JsonObject d = new JsonObject();
            drops.forEach((k, v) -> d.addProperty(k, round(v)));
            loot.add(l.getKey(), d);
        }
        out.add("loot", loot);
        return out;
    }

    /** Writes the dataset gzip-compressed. */
    public static void write(JsonObject dataset, File file) throws IOException {
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8)) {
            new GsonBuilder().disableHtmlEscaping().create().toJson(dataset, w);
        }
    }

    private static String strip(String path, String prefix) {
        return path.substring(prefix.length(), path.length() - ".json".length());
    }

    // ---- tags ----

    static Set<String> resolveTag(String tag, Map<String, JsonObject> tagFiles, Set<String> visiting) {
        Set<String> result = new LinkedHashSet<>();
        JsonObject file = tagFiles.get(tag);
        if (file == null || !visiting.add(tag)) return result;
        JsonArray values = arr(file, "values");
        for (JsonElement v : values) {
            String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
            if (id.startsWith("#")) result.addAll(resolveTag(id.substring(1), tagFiles, visiting));
            else result.add(id);
        }
        visiting.remove(tag);
        return result;
    }

    /** Member as an array (a single object/value is wrapped; missing gives an empty array). */
    static JsonArray arr(JsonObject o, String member) {
        JsonElement e = o.get(member);
        if (e == null || e.isJsonNull()) return new JsonArray();
        if (e.isJsonArray()) return e.getAsJsonArray();
        JsonArray a = new JsonArray();
        a.add(e);
        return a;
    }

    private static JsonArray toArray(Collection<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    // ---- recipes ----

    static JsonObject convertRecipe(String id, JsonObject r) {
        String type = r.has("type") ? r.get("type").getAsString() : "";
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        // ingredient key -> count, insertion ordered
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashMap<String, JsonElement> raw = new LinkedHashMap<>();
        JsonElement result;
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                o.addProperty("type", "shaped");
                JsonObject key = r.getAsJsonObject("key");
                JsonArray pattern = arr(r, "pattern");
                int w = 0;
                for (JsonElement row : pattern) {
                    String s = row.getAsString();
                    w = Math.max(w, s.length());
                    for (char c : s.toCharArray()) {
                        if (c == ' ') continue;
                        JsonElement ing = key.get(String.valueOf(c));
                        if (ing == null) return null;
                        add(counts, raw, ing, 1);
                    }
                }
                o.addProperty("w", w);
                o.addProperty("h", pattern.size());
                result = r.get("result");
            }
            case "minecraft:crafting_shapeless" -> {
                o.addProperty("type", "shapeless");
                JsonArray ings = arr(r, "ingredients");
                for (JsonElement ing : ings) add(counts, raw, ing, 1);
                int n = ings.size();
                o.addProperty("w", n <= 4 ? 2 : 3);
                o.addProperty("h", n <= 4 ? 2 : 3);
                result = r.get("result");
            }
            case "minecraft:crafting_transmute" -> {
                o.addProperty("type", "shapeless");
                add(counts, raw, r.get("input"), 1);
                add(counts, raw, r.get("material"), 1);
                o.addProperty("w", 2);
                o.addProperty("h", 2);
                result = r.get("result");
            }
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" -> {
                o.addProperty("type", type.substring("minecraft:".length()));
                add(counts, raw, r.get("ingredient"), 1);
                int def = type.equals("minecraft:smelting") ? 200 : type.equals("minecraft:campfire_cooking") ? 600 : 100;
                o.addProperty("cook", r.has("cookingtime") ? r.get("cookingtime").getAsInt() : def);
                if (r.has("experience")) o.addProperty("xp", r.get("experience").getAsDouble());
                result = r.get("result");
            }
            case "minecraft:stonecutting" -> {
                o.addProperty("type", "stonecutting");
                add(counts, raw, r.get("ingredient"), 1);
                result = r.get("result");
            }
            case "minecraft:smithing_transform" -> {
                o.addProperty("type", "smithing");
                add(counts, raw, r.get("template"), 1);
                add(counts, raw, r.get("base"), 1);
                add(counts, raw, r.get("addition"), 1);
                result = r.get("result");
            }
            default -> {
                return null; // special/brewing/trim recipes: later phases
            }
        }
        if (result == null || counts.isEmpty() || counts.containsKey(null)) return null;
        String outId;
        int outCount = 1;
        if (result.isJsonObject()) {
            JsonObject res = result.getAsJsonObject();
            if (!res.has("id")) return null;
            outId = res.get("id").getAsString();
            if (res.has("count")) outCount = res.get("count").getAsInt();
            if (res.has("components")) o.addProperty("nbt", true); // result carries components: not a plain item
        } else {
            outId = result.getAsString();
        }
        o.addProperty("out", outId);
        o.addProperty("c", outCount);
        JsonArray in = new JsonArray();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            JsonObject ing = new JsonObject();
            ing.add("o", raw.get(e.getKey()));
            ing.addProperty("n", e.getValue());
            in.add(ing);
        }
        o.add("in", in);
        return o;
    }

    private static void add(LinkedHashMap<String, Integer> counts, LinkedHashMap<String, JsonElement> raw, JsonElement ing, int n) {
        JsonElement norm = normalizeIngredient(ing);
        if (norm == null) {
            counts.put(null, 0);
            return;
        }
        String key = norm.toString();
        counts.merge(key, n, Integer::sum);
        raw.putIfAbsent(key, norm);
    }

    /** Ingredient -> "id" / "#tag" / ["id", "#tag", ...], or null when unsupported. */
    static JsonElement normalizeIngredient(JsonElement ing) {
        if (ing == null || ing.isJsonNull()) return null;
        if (ing.isJsonPrimitive()) return new JsonPrimitive(ing.getAsString());
        if (ing.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement e : ing.getAsJsonArray()) {
                JsonElement n = normalizeIngredient(e);
                if (n == null) return null;
                if (n.isJsonArray()) n.getAsJsonArray().forEach(a::add);
                else a.add(n);
            }
            if (a.isEmpty()) return null;
            return a.size() == 1 ? a.get(0) : a;
        }
        JsonObject o = ing.getAsJsonObject();
        if (o.has("item")) return new JsonPrimitive(o.get("item").getAsString());
        if (o.has("tag")) return new JsonPrimitive("#" + o.get("tag").getAsString());
        if (o.has("id")) return new JsonPrimitive(o.get("id").getAsString());
        return null;
    }

    // ---- loot (expected drops with a plain tool, no silk touch / fortune / shears) ----

    static void evalTable(JsonObject table, Map<String, Double> drops) {
        if (!table.has("pools")) return;
        for (JsonElement p : arr(table, "pools")) {
            JsonObject pool = p.getAsJsonObject();
            double prob = conditionsProbability(pool);
            if (prob <= 0) continue;
            double rolls = pool.has("rolls") ? number(pool.get("rolls")) : 1.0;
            if (pool.has("bonus_rolls")) rolls += 0; // luck only
            for (JsonElement e : arr(pool, "entries")) {
                evalEntry(e.getAsJsonObject(), prob * rolls, drops);
            }
        }
    }

    /** Evaluates one entry with incoming probability; returns the probability that it produced something. */
    static double evalEntry(JsonObject entry, double prob, Map<String, Double> drops) {
        double p = prob * conditionsProbability(entry);
        if (p <= 0) return 0;
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        switch (type) {
            case "minecraft:item" -> {
                String name = entry.get("name").getAsString();
                double count = countOf(entry);
                if (count > 0) drops.merge(name, p * count, Double::sum);
                return p;
            }
            case "minecraft:alternatives" -> {
                // First child whose conditions pass wins; probabilistic children pass the remainder on
                double remaining = p;
                for (JsonElement c : arr(entry, "children")) {
                    if (remaining <= 1e-9) break;
                    double used = evalEntry(c.getAsJsonObject(), remaining, drops);
                    remaining -= used;
                }
                return p - remaining;
            }
            case "minecraft:group", "minecraft:sequence" -> {
                for (JsonElement c : arr(entry, "children")) evalEntry(c.getAsJsonObject(), p, drops);
                return p;
            }
            case "minecraft:empty" -> {
                return p;
            }
            default -> {
                return 0; // tag / loot_table / dynamic entries are ignored
            }
        }
    }

    private static double conditionsProbability(JsonObject o) {
        double p = 1.0;
        if (o.has("condition")) p *= condition(o.get("condition"));
        for (JsonElement c : arr(o, "conditions")) p *= condition(c);
        return p;
    }

    /** Probability that a loot condition passes for a player mining with a plain (unenchanted) correct tool. */
    static double condition(JsonElement c) {
        if (c.isJsonPrimitive()) {
            String ref = c.getAsString();
            // predicate references: tool/can_silk_touch, tool/can_shear, ...
            return ref.contains("silk_touch") || ref.contains("shear") ? 0.0 : 1.0;
        }
        if (c.isJsonArray()) {
            double p = 1.0;
            for (JsonElement e : c.getAsJsonArray()) p *= condition(e);
            return p;
        }
        JsonObject o = c.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : (o.has("condition") ? o.get("condition").getAsString() : "");
        switch (type) {
            case "minecraft:survives_explosion":
                return 1.0;
            case "minecraft:table_bonus": {
                JsonArray ch = arr(o, "chances");
                return ch == null || ch.isEmpty() ? 1.0 : ch.get(0).getAsDouble();
            }
            case "minecraft:random_chance":
                return o.has("chance") ? number(o.get("chance")) : 1.0;
            case "minecraft:random_chance_with_enchanted_bonus":
                return o.has("unenchanted_chance") ? o.get("unenchanted_chance").getAsDouble() : 1.0;
            case "minecraft:match_tool":
                return 0.0; // silk touch / shears / specific tools
            case "minecraft:inverted":
                return 1.0 - condition(o.get("term"));
            case "minecraft:any_of":
            case "minecraft:alternative": {
                double none = 1.0;
                for (JsonElement t : arr(o, "terms")) none *= 1.0 - condition(t);
                return 1.0 - none;
            }
            case "minecraft:all_of": {
                double p = 1.0;
                for (JsonElement t : arr(o, "terms")) p *= condition(t);
                return p;
            }
            case "minecraft:entity_properties":
            case "minecraft:killed_by_player":
                return 0.0;
            default:
                return 1.0; // block_state_property (fully grown crops, bed head, ...) and others
        }
    }

    private static double countOf(JsonObject entry) {
        double count = 1.0;
        JsonArray mods = entry.has("modifier") ? arr(entry, "modifier") : arr(entry, "functions");
        for (JsonElement m : mods) {
            if (!m.isJsonObject()) continue;
            JsonObject f = m.getAsJsonObject();
            String type = f.has("type") ? f.get("type").getAsString() : (f.has("function") ? f.get("function").getAsString() : "");
            if (type.equals("minecraft:set_count")) {
                double cond = conditionsProbability(f);
                double v = number(f.get("count"));
                boolean add = f.has("add") && f.get("add").getAsBoolean();
                double next = add ? count + v : v;
                count = cond * next + (1 - cond) * count;
            }
        }
        return Math.max(0, count);
    }

    /** Expected value of a number provider. */
    static double number(JsonElement n) {
        if (n == null) return 1.0;
        if (n.isJsonPrimitive()) return n.getAsDouble();
        JsonObject o = n.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "minecraft:uniform";
        switch (type) {
            case "minecraft:constant":
                return o.get("value").getAsDouble();
            case "minecraft:uniform":
                return (number(o.get("min")) + number(o.get("max"))) / 2.0;
            case "minecraft:binomial":
                return number(o.get("n")) * number(o.get("p"));
            default:
                return 1.0;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
