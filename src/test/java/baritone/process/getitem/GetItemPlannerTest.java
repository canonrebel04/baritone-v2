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
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.plan.*;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** A2-A7, A9, A10: headless planner behaviour over the real vanilla dataset. */
public class GetItemPlannerTest {
    static RecipeGraph graph;
    static Planner planner;

    @BeforeClass
    public static void load() {
        graph = new RecipeGraph(GetItemDataset.bundled());
        planner = Planner.vanilla(graph);
    }

    static Map<String, Integer> inv(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(Goal.withNamespace((String) kv[i]), (Integer) kv[i + 1]);
        return m;
    }

    static Plan plan(String item, int n, Map<String, Integer> inv) {
        return planner.plan(Goal.parse(item, n, graph, List.of()), inv, new Planner.Options());
    }

    static List<PlanStep> of(Plan p, PlanStep.Kind kind) {
        return p.steps.stream().filter(s -> s.kind == kind).collect(Collectors.toList());
    }

    /** Replays a plan over an inventory, checking every step only uses what exists, and returns the result. */
    static Map<String, Integer> simulate(Plan p, Map<String, Integer> start) {
        Map<String, Integer> inv = new HashMap<>(start);
        for (PlanStep s : p.steps) {
            switch (s.kind) {
                case MINE:
                    if (s.tool != null) assertTrue("tool " + s.tool + " missing for " + s, inv.getOrDefault(s.tool, 0) > 0);
                    inv.merge(s.items.get(0), s.count, Integer::sum);
                    break;
                case PLACE_STATION:
                    take(inv, s.blocks.get(0), 1, s);
                    break;
                case WITHDRAW:
                    inv.merge(s.items.get(0), s.count, Integer::sum);
                    break;
                case TRAVEL:
                    break;
                default:
                    s.inputs.forEach((k, v) -> take(inv, k, v, s));
                    if (s.fuel != null) take(inv, s.fuel, s.fuelCount, s);
                    inv.merge(s.recipe.output, s.count, Integer::sum);
            }
        }
        return inv;
    }

    static void take(Map<String, Integer> inv, String item, int n, PlanStep s) {
        int have = inv.getOrDefault(item, 0);
        assertTrue("step '" + s + "' needs " + n + "x " + item + " but only " + have + " exist", have >= n);
        inv.put(item, have - n);
    }

    @Test
    public void alreadyHaveIsEmptyPlan() {
        Plan p = plan("stick", 4, inv("stick", 10));
        assertTrue(p.isDone());
    }

    @Test
    public void logsAreMinedAsAnyLog() {
        Plan p = plan("#logs", 5, inv());
        assertTrue(p.success);
        assertEquals(1, p.steps.size());
        PlanStep s = p.steps.get(0);
        assertEquals(PlanStep.Kind.MINE, s.kind);
        assertEquals(5, s.count);
        assertTrue(s.blocks.contains("minecraft:oak_log") && s.blocks.contains("minecraft:birch_log"));
        assertNull(s.tool);
    }

    @Test
    public void woodenPickaxeFromNothing() {
        Plan p = plan("wooden_pickaxe", 1, inv());
        assertTrue(p.explain().toString(), p.success);
        Map<String, Integer> end = simulate(p, inv());
        assertEquals(1, (int) end.get("minecraft:wooden_pickaxe"));
        assertEquals(1, of(p, PlanStep.Kind.PLACE_STATION).size());
        // 3 planks + 2 sticks (1 craft = 4 sticks from 2 planks) + table 4 planks = 9 planks = 3 logs
        int logs = of(p, PlanStep.Kind.MINE).stream().mapToInt(s -> s.count).sum();
        assertEquals(3, logs);
    }

    @Test
    public void diamondPickaxeFromNothingNeedsIronTier() {
        Plan p = plan("diamond_pickaxe", 1, inv());
        assertTrue(p.explain().toString(), p.success);
        Map<String, Integer> end = simulate(p, inv());
        assertEquals(1, (int) end.get("minecraft:diamond_pickaxe"));
        PlanStep diamonds = p.steps.stream().filter(s -> s.kind == PlanStep.Kind.MINE && s.items.contains("minecraft:diamond")).findFirst().orElseThrow();
        assertEquals("minecraft:iron_pickaxe", diamonds.tool);
        assertTrue(p.steps.stream().anyMatch(s -> s.kind == PlanStep.Kind.SMELT && s.recipe.output.equals("minecraft:iron_ingot")));
        assertTrue(p.risks.stream().anyMatch(r -> r.contains("diamond")));
        assertTrue(p.seconds > 300);
    }

    @Test
    public void ironIngotsGetFurnaceAndFuel() {
        Plan p = plan("iron_ingot", 3, inv("stone_pickaxe", 1, "coal", 5));
        assertTrue(p.explain().toString(), p.success);
        PlanStep smelt = of(p, PlanStep.Kind.SMELT).get(0);
        assertEquals("minecraft:coal", smelt.fuel);
        assertEquals(1, smelt.fuelCount);
        assertEquals(3, smelt.batches);
        assertTrue(of(p, PlanStep.Kind.PLACE_STATION).stream().anyMatch(s -> s.blocks.contains("minecraft:furnace")));
        simulate(p, inv("stone_pickaxe", 1, "coal", 5));
    }

    @Test
    public void usesNearbyStationAndInventory() {
        Planner.Options o = new Planner.Options();
        o.stations = Set.of("minecraft:crafting_table");
        Map<String, Integer> start = inv("oak_planks", 3, "stick", 2);
        Plan p = planner.plan(Goal.item("minecraft:wooden_pickaxe", 1), start, o);
        assertTrue(p.success);
        assertEquals(1, p.steps.size());
        assertEquals(PlanStep.Kind.CRAFT, p.steps.get(0).kind);
        simulate(p, start);
    }

    @Test
    public void sticksReuseExtraPlanks() {
        // 1 log -> 4 planks; 2 for sticks; the other 2 stay for the next craft (A6)
        Map<String, Integer> start = inv("oak_log", 1);
        Plan p = plan("stick", 4, start);
        assertTrue(p.success);
        assertEquals(2, p.steps.size());
        assertEquals(2, (int) p.leftovers.get("minecraft:oak_planks"));
        Map<String, Integer> end = simulate(p, start);
        assertEquals(2, (int) end.get("minecraft:oak_planks"));
    }

    @Test
    public void batchingAvoidsOvershoot() {
        Plan p = plan("stick", 10, inv("oak_planks", 20));
        PlanStep s = p.steps.get(0);
        assertEquals(3, s.batches); // 12 sticks, not 10 crafts
        assertEquals(6, (int) s.inputs.get("minecraft:oak_planks"));
    }

    @Test
    public void mixedPlankTypesAreUsed() {
        Map<String, Integer> start = inv("oak_planks", 2, "birch_planks", 2);
        Plan p = plan("crafting_table", 1, start);
        assertTrue(p.success);
        assertEquals(1, p.steps.size());
        simulate(p, start);
    }

    @Test
    public void protectedItemsAreNeverConsumed() {
        Planner.Options o = new Planner.Options();
        o.protectedItems = Set.of("minecraft:diamond");
        Map<String, Integer> start = inv("diamond", 10, "stick", 2, "iron_pickaxe", 1);
        o.stations = Set.of("minecraft:crafting_table");
        Plan p = planner.plan(Goal.item("minecraft:diamond_pickaxe", 1), start, o);
        assertTrue(p.success);
        PlanStep mine = p.steps.stream().filter(s -> s.kind == PlanStep.Kind.MINE).findFirst().orElseThrow();
        assertTrue(mine.items.contains("minecraft:diamond"));
        assertEquals(3, mine.count);
        // the protected diamonds stay
        Map<String, Integer> end = simulate(p, start);
        assertTrue(end.get("minecraft:diamond") >= 10);
    }

    @Test
    public void protectedToolIsNotUsed() {
        Planner.Options o = new Planner.Options();
        o.protectedItems = Set.of("minecraft:netherite_pickaxe");
        Plan p = planner.plan(Goal.item("minecraft:cobblestone", 4), inv("netherite_pickaxe", 1), o);
        assertTrue(p.success);
        PlanStep mine = of(p, PlanStep.Kind.MINE).get(of(p, PlanStep.Kind.MINE).size() - 1);
        assertNotEquals("minecraft:netherite_pickaxe", mine.tool);
    }

    @Test
    public void ownedToolIsReused() {
        Plan p = plan("cobblestone", 16, inv("stone_pickaxe", 1));
        assertEquals(1, p.steps.size());
        assertEquals("minecraft:stone_pickaxe", p.steps.get(0).tool);
    }

    @Test
    public void stoneComesFromSmeltingCobble() {
        Plan p = plan("stone", 8, inv("wooden_pickaxe", 1, "coal", 1, "furnace", 1));
        assertTrue(p.explain().toString(), p.success);
        PlanStep smelt = of(p, PlanStep.Kind.SMELT).get(0);
        assertEquals(8, smelt.batches);
        assertEquals("minecraft:coal", smelt.fuel);
        assertEquals(1, smelt.fuelCount); // 8 smelts = 1600 ticks = exactly one coal
    }

    @Test
    public void unknownOrUnobtainableFailsCleanly() {
        assertNull(Goal.parse("not_an_item", 1, graph, List.of()));
        Plan p = planner.plan(Goal.item("minecraft:bedrock", 1), inv(), new Planner.Options());
        assertFalse(p.success);
        assertNotNull(p.failure);
        assertTrue(p.explain().get(0).startsWith("Can't get"));
    }

    @Test
    public void cyclesTerminate() {
        // iron ingot <-> nugget <-> block cycles must not loop
        for (String item : new String[]{"iron_block", "iron_nugget", "gold_block", "copper_block", "coal_block", "redstone_block"}) {
            Plan p = plan(item, 2, inv());
            assertTrue(item + ": " + p.explain(), p.success);
        }
    }

    @Test
    public void netherAllowedButPenalised() {
        Plan p = plan("quartz", 4, inv("wooden_pickaxe", 1));
        assertTrue(p.explain().toString(), p.success);
        assertTrue(p.risks.stream().anyMatch(r -> r.contains("Nether")));
        Planner.Options o = new Planner.Options();
        o.dimension = CostModel.NETHER;
        Plan inNether = planner.plan(Goal.item("minecraft:quartz", 4), inv("wooden_pickaxe", 1), o);
        assertTrue(inNether.risks.stream().noneMatch(r -> r.contains("Nether")));
        assertTrue(inNether.seconds < p.seconds);
    }

    @Test
    public void tagGoalPrefersOwnedAndPreference() {
        Goal g = Goal.parse("#planks", 4, graph, List.of("minecraft:spruce_planks"));
        assertEquals("minecraft:spruce_planks", g.options.get(0));
        Plan p = planner.plan(g, inv("birch_log", 1), new Planner.Options());
        assertTrue(p.success);
        assertEquals(1, p.steps.size());
        assertEquals("minecraft:birch_planks", p.steps.get(0).recipe.output);
    }

    @Test
    public void deterministic() {
        Plan a = plan("iron_pickaxe", 1, inv());
        for (int i = 0; i < 3; i++) {
            Plan b = plan("iron_pickaxe", 1, inv());
            assertEquals(a.explain(), b.explain());
        }
    }

    @Test
    public void explainerListsStepsAndEta() {
        Plan p = plan("furnace", 1, inv());
        List<String> lines = p.explain();
        assertTrue(lines.get(0).contains("ETA"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Craft 1x furnace")));
    }

    @Test
    public void planCacheHitsOnSameState() {
        PlanCache cache = new PlanCache(planner, 8);
        Map<String, Integer> start = inv("oak_log", 2);
        Planner.Options o = new Planner.Options();
        Plan a = cache.plan(Goal.item("minecraft:stick", 8), start, o);
        Plan b = cache.plan(Goal.item("minecraft:stick", 8), new HashMap<>(start), o);
        assertSame(a, b);
        Plan c = cache.plan(Goal.item("minecraft:stick", 8), inv("oak_log", 3), o);
        assertNotSame(a, c);
        assertEquals(1, cache.hits());
    }

    @Test
    public void seenBlocksSteerWoodChoice() {
        Planner.Options o = new Planner.Options();
        o.seenBlocks = Set.of("minecraft:birch_log", "minecraft:stone");
        Plan p = planner.plan(Goal.item("minecraft:stick", 4), inv(), o);
        assertTrue(p.success);
        PlanStep mine = of(p, PlanStep.Kind.MINE).get(0);
        assertEquals("minecraft:birch_log", mine.blocks.get(0));
    }

    @Test
    public void playerMadeBlocksAreNotMineTargets() {
        CostModel c = new CostModel(graph);
        assertTrue(c.isNatural("minecraft:oak_log"));
        assertFalse(c.isNatural("minecraft:stripped_oak_log"));
        assertFalse(c.isNatural("minecraft:oak_wood"));
        assertFalse(c.isNatural("minecraft:oak_planks"));
        assertFalse(c.isNatural("minecraft:furnace"));
        assertFalse(c.isNatural("minecraft:glass"));
    }
}
