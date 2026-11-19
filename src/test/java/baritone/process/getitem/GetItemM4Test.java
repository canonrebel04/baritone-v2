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

import baritone.process.getitem.advanced.*;
import baritone.process.getitem.data.GetItemDataset;
import baritone.process.getitem.data.RecipeType;
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.inventory.HotbarLayout;
import baritone.process.getitem.plan.*;
import baritone.process.getitem.sources.Compost;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** M4: stonecutter / smithing / campfire planning, advanced goals (brewing, enchanting), compost, hotbar layout. */
public class GetItemM4Test {
    static RecipeGraph graph;
    static Planner planner;

    @BeforeClass
    public static void load() {
        graph = new RecipeGraph(GetItemDataset.bundled());
        planner = Planner.vanilla(graph);
    }

    static Plan plan(String item, int n, Map<String, Integer> inv, String... stations) {
        Planner.Options o = new Planner.Options();
        o.stations = new HashSet<>(Arrays.asList(stations));
        return planner.plan(Goal.parse(item, n, graph, List.of()), inv, o);
    }

    static PlanStep producing(Plan p, String item) {
        for (PlanStep s : p.steps) if (s.items.contains(item) && s.recipe != null) return s;
        return null;
    }

    // ------------------------------------------------------------ A26 planner

    @Test
    public void stairsAreCutOnANearbyStonecutter() {
        Plan p = plan("stone_brick_stairs", 4, GetItemPlannerTest.inv("stone_bricks", 4), "minecraft:stonecutter", "minecraft:crafting_table");
        assertTrue(p.failure, p.success);
        PlanStep s = producing(p, "minecraft:stone_brick_stairs");
        assertNotNull(p.explain().toString(), s);
        assertEquals(RecipeType.STONECUTTING, s.recipe.type);
        assertEquals(4, (int) s.inputs.get("minecraft:stone_bricks"));
        assertTrue(s.describe(), s.describe().startsWith("Cut 4x stone_brick_stairs"));
        GetItemPlannerTest.simulate(p, GetItemPlannerTest.inv("stone_bricks", 4));
    }

    @Test
    public void m1TypesNeverUseTheStonecutter() {
        Planner.Options o = new Planner.Options();
        o.types = Planner.M1_TYPES;
        o.stations = new HashSet<>(Arrays.asList("minecraft:stonecutter", "minecraft:crafting_table"));
        Plan p = planner.plan(Goal.parse("stone_brick_stairs", 4, graph, List.of()), GetItemPlannerTest.inv("stone_bricks", 6), o);
        assertTrue(p.failure, p.success);
        for (PlanStep s : p.steps) if (s.recipe != null) assertNotEquals(RecipeType.STONECUTTING, s.recipe.type);
    }

    @Test
    public void netheriteUpgradeUsesTheSmithingTable() {
        Map<String, Integer> inv = GetItemPlannerTest.inv("diamond_pickaxe", 1, "netherite_ingot", 1, "netherite_upgrade_smithing_template", 1);
        Plan p = plan("netherite_pickaxe", 1, inv, "minecraft:smithing_table");
        assertTrue(p.failure, p.success);
        PlanStep s = producing(p, "minecraft:netherite_pickaxe");
        assertNotNull(s);
        assertEquals(RecipeType.SMITHING, s.recipe.type);
        assertEquals("minecraft:smithing_table", s.station());
        assertEquals(3, s.inputs.size());
        // slot order for the executor: template, base, material
        assertTrue(s.recipe.ingredients.get(0).accepts("minecraft:netherite_upgrade_smithing_template"));
        assertTrue(s.recipe.ingredients.get(1).accepts("minecraft:diamond_pickaxe"));
        assertTrue(s.recipe.ingredients.get(2).accepts("minecraft:netherite_ingot"));
        assertTrue(s.describe().startsWith("Smith 1x netherite_pickaxe"));
        GetItemPlannerTest.simulate(p, inv);
    }

    @Test
    public void campfireCooksWithoutFuel() {
        Planner.Options o = new Planner.Options();
        o.stations = Collections.singleton("minecraft:campfire");
        o.allowPlaceStations = false;
        Plan p = planner.plan(Goal.item("minecraft:cooked_beef", 4), GetItemPlannerTest.inv("beef", 4), o);
        assertTrue(p.failure, p.success);
        PlanStep s = producing(p, "minecraft:cooked_beef");
        assertEquals(RecipeType.CAMPFIRE_COOKING, s.recipe.type);
        assertEquals(PlanStep.Kind.SMELT, s.kind);
        assertNull(s.fuel);
        assertTrue(s.describe().startsWith("Cook 4x cooked_beef"));
        GetItemPlannerTest.simulate(p, GetItemPlannerTest.inv("beef", 4));
    }

    @Test
    public void campfireCooksFourAtATime() {
        assertEquals(600, RecipeType.CAMPFIRE_COOKING.cookTicks(4, 600));
        assertEquals(1200, RecipeType.CAMPFIRE_COOKING.cookTicks(5, 600));
        assertEquals(800, RecipeType.SMELTING.cookTicks(4, 200));
        assertFalse(RecipeType.CAMPFIRE_COOKING.needsFuel());
        assertTrue(RecipeType.SMOKING.needsFuel());
    }

    // ------------------------------------------------------------ A8/A25/A27 specs

    @Test
    public void specParsing() {
        ItemSpec p = ItemSpec.parse("splash_potion:strong_healing");
        assertEquals("minecraft:splash_potion", p.item);
        assertEquals("strong_healing", p.potion);
        ItemSpec e = ItemSpec.parse("diamond_pickaxe@fortune:III,unbreaking:3");
        assertEquals("minecraft:diamond_pickaxe", e.item);
        assertEquals(3, (int) e.enchants.get("fortune"));
        assertEquals(3, (int) e.enchants.get("unbreaking"));
        ItemSpec n = ItemSpec.parse("diamond_sword@sharpness:5@name:Big Stick");
        assertEquals("Big Stick", n.name);
        assertEquals(5, (int) n.enchants.get("sharpness"));
        assertTrue(ItemSpec.parse("iron_pickaxe@clean").clean);
        assertNull(ItemSpec.parse("diamond_pickaxe@fortune:9x"));
        assertFalse(ItemSpec.looksAdvanced("minecraft:oak_log"));
        assertFalse(ItemSpec.looksAdvanced("potion"));
        assertTrue(ItemSpec.looksAdvanced("minecraft:potion:swiftness"));
        assertFalse(ItemSpec.looksAdvanced("#logs"));
    }

    @Test
    public void brewingPaths() {
        List<BrewPlanner.Brew> path = BrewPlanner.path(BrewPlanner.SPLASH, "strong_harming");
        assertNotNull(path);
        // water -> awkward -> (healing|poison) -> strong_x -> strong_harming -> splash
        assertEquals("water", path.get(0).fromPotion);
        assertEquals("minecraft:nether_wart", path.get(0).ingredient);
        assertEquals("minecraft:gunpowder", path.get(path.size() - 1).ingredient);
        assertEquals("strong_harming", path.get(path.size() - 1).toPotion);
        assertEquals(5, path.size());
        assertEquals(3, BrewPlanner.path(BrewPlanner.POTION, "long_swiftness").size());
        List<BrewPlanner.Brew> ling = BrewPlanner.path(BrewPlanner.LINGERING, "slow_falling");
        assertEquals("minecraft:dragon_breath", ling.get(ling.size() - 1).ingredient);
        assertEquals("weakness", BrewPlanner.path(BrewPlanner.POTION, "weakness").get(0).toPotion);
        assertNull(BrewPlanner.path(BrewPlanner.POTION, "luck"));
    }

    @Test
    public void brewingNextActions() {
        ItemSpec sp = ItemSpec.parse("potion:swiftness");
        List<StackDesc> inv = new ArrayList<>();
        assertEquals(Next.Kind.NEED, BrewPlanner.next(sp, 3, inv, false).kind);
        assertEquals("minecraft:glass_bottle", BrewPlanner.next(sp, 3, inv, false).item);
        inv.add(StackDesc.of("minecraft:glass_bottle", 3));
        Next fill = BrewPlanner.next(sp, 3, inv, false);
        assertEquals(Next.Kind.FILL, fill.kind);
        assertEquals(3, fill.count);
        inv.clear();
        inv.add(StackDesc.potion(BrewPlanner.POTION, "water", 3));
        assertEquals("minecraft:nether_wart", BrewPlanner.next(sp, 3, inv, false).item);
        inv.add(StackDesc.of("minecraft:nether_wart", 1));
        Next fuel = BrewPlanner.next(sp, 3, inv, false);
        assertEquals(BrewPlanner.FUEL, fuel.item);
        Next brew = BrewPlanner.next(sp, 3, inv, true);
        assertEquals(Next.Kind.BREW, brew.kind);
        assertEquals("minecraft:nether_wart", brew.item);
        assertEquals(3, brew.count);
        // later stage first when it holds as many bottles
        inv.clear();
        inv.add(StackDesc.potion(BrewPlanner.POTION, "awkward", 3));
        inv.add(StackDesc.of("minecraft:sugar", 2));
        inv.add(StackDesc.of(BrewPlanner.FUEL, 1));
        Next b2 = BrewPlanner.next(sp, 3, inv, false);
        assertEquals(Next.Kind.BREW, b2.kind);
        assertEquals("minecraft:sugar", b2.item);
        inv.clear();
        inv.add(StackDesc.potion(BrewPlanner.POTION, "swiftness", 3));
        assertEquals(Next.Kind.DONE, BrewPlanner.next(sp, 3, inv, false).kind);
        // blaze powder as ingredient keeps one for fuel
        ItemSpec str = ItemSpec.parse("potion:strength");
        inv.clear();
        inv.add(StackDesc.potion(BrewPlanner.POTION, "awkward", 1));
        inv.add(StackDesc.of(BrewPlanner.FUEL, 1));
        assertEquals(Next.Kind.NEED, BrewPlanner.next(str, 1, inv, false).kind);
    }

    @Test
    public void enchantRoutes() {
        ItemSpec sp = ItemSpec.parse("diamond_pickaxe@fortune:3");
        EnchantPlanner.Options o = new EnchantPlanner.Options();
        o.levels = 30;
        List<StackDesc> inv = new ArrayList<>();
        assertEquals(Next.Kind.NEED, EnchantPlanner.next(sp, 1, inv, o).kind);
        inv.add(new StackDesc(0, "minecraft:diamond_pickaxe", 1, null, Map.of(), null, 0));
        // no book: enchanting table, needs lapis first
        Next n = EnchantPlanner.next(sp, 1, inv, o);
        assertEquals(Next.Kind.NEED, n.kind);
        assertEquals(EnchantPlanner.LAPIS, n.item);
        inv.add(new StackDesc(1, EnchantPlanner.LAPIS, 5, null, Map.of(), null, 0));
        assertEquals(Next.Kind.ENCHANT, EnchantPlanner.next(sp, 1, inv, o).kind);
        o.levels = 10;
        assertEquals(Next.Kind.XP, EnchantPlanner.next(sp, 1, inv, o).kind);
        o.collectXp = false;
        assertEquals(Next.Kind.FAIL, EnchantPlanner.next(sp, 1, inv, o).kind);
        o.levels = 30;
        // a book wins over the table
        inv.add(new StackDesc(2, EnchantPlanner.BOOK, 1, null, Map.of("fortune", 3), null, 0));
        Next a = EnchantPlanner.next(sp, 1, inv, o);
        assertEquals(Next.Kind.ANVIL, a.kind);
        assertEquals(0, a.slot);
        assertEquals(2, a.other);
        // silk touch on the pick conflicts: clean it first
        inv.set(0, new StackDesc(0, "minecraft:diamond_pickaxe", 1, null, Map.of("silk_touch", 1), null, 0));
        assertEquals(Next.Kind.GRINDSTONE, EnchantPlanner.next(sp, 1, inv, o).kind);
        // too expensive
        inv.set(0, new StackDesc(0, "minecraft:diamond_pickaxe", 1, null, Map.of(), null, 31));
        inv.set(2, new StackDesc(2, EnchantPlanner.BOOK, 1, null, Map.of("fortune", 3), null, 7));
        assertEquals(Next.Kind.FAIL, EnchantPlanner.next(sp, 1, inv, o).kind);
        // done
        inv.set(0, new StackDesc(0, "minecraft:diamond_pickaxe", 1, null, Map.of("fortune", 3, "efficiency", 5), null, 1));
        assertEquals(Next.Kind.DONE, EnchantPlanner.next(sp, 1, inv, o).kind);
    }

    @Test
    public void enchantRerollsAreBounded() {
        ItemSpec sp = ItemSpec.parse("bow@infinity");
        EnchantPlanner.Options o = new EnchantPlanner.Options();
        o.levels = 30;
        o.anvil = false;
        o.rerolls = 2;
        List<StackDesc> inv = new ArrayList<>();
        inv.add(new StackDesc(0, "minecraft:bow", 1, null, Map.of("power", 2), null, 0));
        inv.add(new StackDesc(1, EnchantPlanner.LAPIS, 9, null, Map.of(), null, 0));
        assertEquals(Next.Kind.GRINDSTONE, EnchantPlanner.next(sp, 1, inv, o).kind);
        o.rerollsUsed = 2;
        assertEquals(Next.Kind.FAIL, EnchantPlanner.next(sp, 1, inv, o).kind);
    }

    @Test
    public void renameAndOffers() {
        ItemSpec sp = ItemSpec.parse("diamond_sword@name:Excalibur");
        EnchantPlanner.Options o = new EnchantPlanner.Options();
        o.levels = 5;
        List<StackDesc> inv = List.of(new StackDesc(3, "minecraft:diamond_sword", 1, null, Map.of(), null, 0));
        Next r = EnchantPlanner.next(sp, 1, inv, o);
        assertEquals(Next.Kind.RENAME, r.kind);
        assertEquals("Excalibur", r.name);
        assertEquals(3, r.slot);
        Map<String, Integer> want = Map.of("fortune", 3);
        assertEquals(2, EnchantPlanner.pickOffer(want, new String[]{"efficiency", "unbreaking", "fortune"}, new int[]{1, 2, 3}, new int[]{5, 17, 30}, 30, 3));
        assertEquals(-1, EnchantPlanner.pickOffer(want, new String[]{"efficiency", "unbreaking", "fortune"}, new int[]{1, 2, 2}, new int[]{5, 17, 30}, 30, 3));
        assertEquals(-1, EnchantPlanner.pickOffer(want, new String[]{null, null, "fortune"}, new int[]{0, 0, 3}, new int[]{5, 17, 30}, 20, 3));
        assertEquals(0, EnchantPlanner.cheapestOffer(new int[]{5, 17, 30}, 20, 3));
        assertEquals(7, EnchantPlanner.penaltyAfter(3));
        assertTrue(EnchantPlanner.conflicts("fortune", "silk_touch"));
        assertFalse(EnchantPlanner.conflicts("fortune", "unbreaking"));
    }

    // ------------------------------------------------------------ A31 / A43

    @Test
    public void compostSources() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("minecraft:wheat_seeds", 50);
        inv.put("minecraft:bread", 10); // food: kept
        inv.put("minecraft:oak_sapling", 3); // too few for one bone meal
        List<Source> src = Compost.sources(inv, Collections.emptySet(), 5, "1,2,3");
        assertEquals(1, src.size());
        Source s = src.get(0);
        assertEquals(Source.Kind.COMPOST, s.kind);
        assertEquals("minecraft:bone_meal", s.item);
        assertEquals(24, (int) s.inputs.get("minecraft:wheat_seeds"));
        assertEquals(2, s.maxBatches);
        assertTrue(Compost.sources(inv, Set.of("minecraft:wheat_seeds"), 5, "x").isEmpty());
    }

    @Test
    public void planUsesCompostForBoneMeal() {
        Map<String, Integer> inv = GetItemPlannerTest.inv("wheat_seeds", 48);
        Planner.Options o = new Planner.Options();
        o.sources = Compost.sources(inv, Collections.emptySet(), 3, "0,64,0");
        Plan p = planner.plan(Goal.item("minecraft:bone_meal", 2), inv, o);
        assertTrue(p.failure, p.success);
        boolean composts = false;
        for (PlanStep s : p.steps) if (s.kind == PlanStep.Kind.GATHER && s.source.kind == Source.Kind.COMPOST) composts = true;
        assertTrue(p.explain().toString(), composts);
    }

    @Test
    public void hotbarLayout() {
        List<String> layout = Arrays.asList("pickaxe", "axe", "-", "food");
        List<HotbarLayout.Item> items = new ArrayList<>();
        items.add(new HotbarLayout.Item(0, "minecraft:stone_pickaxe", false, false));
        items.add(new HotbarLayout.Item(12, "minecraft:diamond_pickaxe", false, false));
        items.add(new HotbarLayout.Item(20, "minecraft:iron_axe", false, false));
        items.add(new HotbarLayout.Item(3, "minecraft:bread", true, false));
        assertArrayEquals(new int[]{12, 0}, HotbarLayout.nextSwap(layout, items));
        items.set(0, new HotbarLayout.Item(0, "minecraft:diamond_pickaxe", false, false));
        items.set(1, new HotbarLayout.Item(12, "minecraft:stone_pickaxe", false, false));
        assertArrayEquals(new int[]{20, 1}, HotbarLayout.nextSwap(layout, items));
        items.set(2, new HotbarLayout.Item(1, "minecraft:iron_axe", false, false));
        assertNull(HotbarLayout.nextSwap(layout, items));
        assertFalse(HotbarLayout.matches("axe", new HotbarLayout.Item(0, "minecraft:iron_pickaxe", false, false)));
        assertEquals("pickaxe", HotbarLayout.normalize("Pickaxes"));
        assertNull(HotbarLayout.normalize("-"));
    }
}
