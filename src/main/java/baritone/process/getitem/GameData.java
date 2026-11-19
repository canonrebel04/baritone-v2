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

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.process.getitem.data.GetItemDataset;
import baritone.process.getitem.data.Ingredient;
import baritone.process.getitem.data.Recipe;
import baritone.process.getitem.data.RecipeType;
import baritone.process.getitem.data.TagLookup;
import baritone.process.getitem.graph.FuelTable;
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.graph.ToolRules;
import baritone.process.getitem.plan.CostModel;
import baritone.process.getitem.plan.PlanCache;
import baritone.process.getitem.plan.Planner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;

/**
 * Runtime side of Get Item: the recipe graph with the game's (server-synced) tags, server recipes merged from the
 * client recipe book (plan A1), real tool rules, the player's inventory, nearby stations and nearby natural blocks.
 */
public final class GameData {
    private final IPlayerContext ctx;
    private RecipeGraph graph;
    private Planner planner;
    private PlanCache cache;
    private int mergedBookSize = -1;
    private Object levelRef;

    public GameData(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
    }

    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
    }

    /** Graph/planner for the current level, rebuilt when the level changes; merges new recipe-book entries. */
    public synchronized Planner planner() {
        if (graph == null || levelRef != ctx.world()) {
            levelRef = ctx.world();
            GetItemDataset d = GetItemDataset.bundled();
            TagLookup game = tag -> {
                List<String> out = new ArrayList<>();
                try {
                    for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, Identifier.parse(tag)))) {
                        out.add(id(h.value()));
                    }
                } catch (RuntimeException ignored) {
                    // bad tag id
                }
                return out;
            };
            graph = new RecipeGraph(d, game.orElse(TagLookup.of(d.itemTags)));
            RecipeGraph g = graph;
            ToolRules dataRules = ToolRules.fromTags(g);
            ToolRules tools = new ToolRules(
                    b -> {
                        Block blk = block(b);
                        return blk != null ? blk.defaultBlockState().requiresCorrectToolForDrops() : dataRules.requiresTool(b);
                    },
                    (tool, b) -> {
                        Block blk = block(b);
                        Item it = item(tool);
                        if (blk == null || it == Items.AIR) return dataRules.canHarvest(tool, b);
                        return new ItemStack(it).isCorrectToolForDrops(blk.defaultBlockState());
                    });
            planner = new Planner(g, tools, FuelTable.vanilla(g), new CostModel(g));
            cache = new PlanCache(planner, 32);
            mergedBookSize = -1;
        }
        mergeRecipeBook();
        return planner;
    }

    public synchronized PlanCache cache() {
        planner();
        return cache;
    }

    /** Plan A1 runtime merge: recipes the server sent to the recipe book that the dataset doesn't have. */
    private void mergeRecipeBook() {
        if (ctx.player() == null || ctx.world() == null) return;
        List<RecipeDisplayEntry> entries = recipeBookEntries();
        if (entries.size() == mergedBookSize) return;
        mergedBookSize = entries.size();
        ContextMap context = SlotDisplayContext.fromLevel(ctx.world());
        boolean added = false;
        for (RecipeDisplayEntry e : entries) {
            Recipe r = convert(e, context);
            if (r != null && graph.addServerRecipe(r)) added = true;
        }
        if (added) {
            planner.invalidate();
            cache.clear();
        }
    }

    public List<RecipeDisplayEntry> recipeBookEntries() {
        List<RecipeDisplayEntry> out = new ArrayList<>();
        if (ctx.player() == null) return out;
        ctx.player().getRecipeBook().getCollections().forEach(c -> out.addAll(c.getRecipes()));
        return out;
    }

    private static List<String> options(SlotDisplay d, ContextMap context) {
        List<String> out = new ArrayList<>();
        for (ItemStack s : d.resolveForStacks(context)) {
            if (s.isEmpty()) continue;
            String id = id(s.getItem());
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    static Recipe convert(RecipeDisplayEntry e, ContextMap context) {
        RecipeDisplay display = e.display();
        List<SlotDisplay> cells;
        RecipeType type;
        int w = 0, h = 0, cook = 0;
        double xp = 0;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            type = RecipeType.SHAPED;
            cells = shaped.ingredients();
            w = shaped.width();
            h = shaped.height();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            type = RecipeType.SHAPELESS;
            cells = shapeless.ingredients();
            w = h = cells.size() <= 4 ? 2 : 3;
        } else if (display instanceof FurnaceRecipeDisplay furnace) {
            ItemStack station = furnace.craftingStation().resolveForFirstStack(context);
            type = station.is(Items.BLAST_FURNACE) ? RecipeType.BLASTING : station.is(Items.SMOKER) ? RecipeType.SMOKING
                    : station.is(Items.CAMPFIRE) || station.is(Items.SOUL_CAMPFIRE) ? RecipeType.CAMPFIRE_COOKING : RecipeType.SMELTING;
            cells = Collections.singletonList(furnace.ingredient());
            cook = furnace.duration();
            xp = furnace.experience();
        } else {
            return null;
        }
        ItemStack result = display.result().resolveForFirstStack(context);
        if (result.isEmpty()) return null;
        boolean components = !result.getComponentsPatch().isEmpty();
        LinkedHashMap<List<String>, Integer> counts = new LinkedHashMap<>();
        List<List<String>> order = new ArrayList<>();
        List<List<String>> perCell = new ArrayList<>();
        for (SlotDisplay cell : cells) {
            List<String> opts = options(cell, context);
            perCell.add(opts);
            if (opts.isEmpty()) continue;
            if (!counts.containsKey(opts)) order.add(opts);
            counts.merge(opts, 1, Integer::sum);
        }
        if (counts.isEmpty()) return null;
        List<Ingredient> ings = new ArrayList<>();
        for (List<String> o : order) ings.add(new Ingredient(o, null, counts.get(o)));
        int[] pattern = null;
        if (type == RecipeType.SHAPED) {
            pattern = new int[perCell.size()];
            for (int i = 0; i < pattern.length; i++) pattern[i] = perCell.get(i).isEmpty() ? -1 : order.indexOf(perCell.get(i));
        }
        return new Recipe("server:" + e.id().index(), type, w, h, ings, id(result.getItem()), result.getCount(), cook, xp,
                components, Recipe.Source.SERVER, pattern);
    }

    // ---------------------------------------------------------------- inventory

    /** Player main inventory (36 slots) + offhand, as item id -> count. */
    public Map<String, Integer> inventory() {
        Map<String, Integer> out = new HashMap<>();
        if (ctx.player() == null) return out;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) out.merge(id(s.getItem()), s.getCount(), Integer::sum);
        }
        ItemStack off = ctx.player().getOffhandItem();
        if (!off.isEmpty()) out.merge(id(off.getItem()), off.getCount(), Integer::sum);
        return out;
    }

    public int count(Collection<String> items) {
        Map<String, Integer> inv = inventory();
        int n = 0;
        for (String i : items) n += inv.getOrDefault(i, 0);
        return n;
    }

    public Set<String> protectedItems() {
        Set<String> out = new HashSet<>();
        for (Item i : Baritone.settings().getItemProtectItems.value) out.add(id(i));
        return out;
    }

    public String dimension() {
        return ctx.world() == null ? CostModel.OVERWORLD : ctx.world().dimension().identifier().toString();
    }

    // ---------------------------------------------------------------- world scans

    /** Positions of the given blocks within {@code radius}, nearest first (chunk palettes skip empty sections). */
    public List<BlockPos> findBlocks(Set<Block> blocks, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (ctx.player() == null || ctx.world() == null) return out;
        BlockPos center = ctx.playerFeet();
        int minY = Math.max(ctx.world().getMinY(), center.getY() - radius);
        int maxY = Math.min(ctx.world().getMaxY(), center.getY() + radius);
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = (LevelChunk) ctx.world().getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection s = sections[si];
                    int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
                    if (s == null || s.hasOnlyAir() || baseY + 15 < minY || baseY > maxY) continue;
                    if (!s.maybeHas(st -> blocks.contains(st.getBlock()))) continue;
                    for (int y = 0; y < 16; y++) {
                        int wy = baseY + y;
                        if (wy < minY || wy > maxY) continue;
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (blocks.contains(s.getBlockState(x, y, z).getBlock())) {
                                    BlockPos p = new BlockPos((cx << 4) + x, wy, (cz << 4) + z);
                                    if (p.distSqr(center) <= (double) radius * radius) out.add(p);
                                }
                            }
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return out;
    }

    /** Station block ids present within the search radius (crafting table, furnaces...), excluding blacklisted spots. */
    public Set<String> nearbyStations(Set<BlockPos> blacklist) {
        Set<String> out = new TreeSet<>();
        Set<Block> stations = new HashSet<>();
        for (String s : STATIONS) stations.add(block(s));
        for (BlockPos p : findBlocks(stations, Baritone.settings().getItemStationSearchRadius.value)) {
            if (!blacklist.contains(p)) out.add(id(ctx.world().getBlockState(p).getBlock()));
        }
        return out;
    }

    public static final String[] STATIONS = {"minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker"};

    /** Natural block ids present in loaded chunks around the player (palette scan; cheap). */
    public Set<String> seenNaturalBlocks(CostModel costs, int chunkRadius) {
        Set<String> out = new HashSet<>();
        if (ctx.player() == null || ctx.world() == null) return out;
        Set<BlockState> states = new HashSet<>();
        BlockPos center = ctx.playerFeet();
        for (int cx = (center.getX() >> 4) - chunkRadius; cx <= (center.getX() >> 4) + chunkRadius; cx++) {
            for (int cz = (center.getZ() >> 4) - chunkRadius; cz <= (center.getZ() >> 4) + chunkRadius; cz++) {
                LevelChunk chunk = (LevelChunk) ctx.world().getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (LevelChunkSection s : chunk.getSections()) {
                    if (s == null || s.hasOnlyAir()) continue;
                    s.maybeHas(st -> {
                        states.add(st);
                        return false;
                    });
                }
            }
        }
        for (BlockState st : states) {
            String id = id(st.getBlock());
            if (costs.isNatural(id)) out.add(id);
        }
        return out;
    }
}
