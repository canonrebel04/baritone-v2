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

/** A24 chest withdrawal + protection and decision-9 dimension travel in the planner. */
public class GetItemWithdrawPlanTest {
    static RecipeGraph graph;
    static Planner planner;

    @BeforeClass
    public static void load() {
        graph = new RecipeGraph(GetItemDataset.bundled());
        planner = Planner.vanilla(graph);
    }

    static Map<String, Integer> m(Object... kv) {
        return GetItemPlannerTest.inv(kv);
    }

    static Plan plan(String item, int n, Map<String, Integer> inv, Planner.Options o) {
        return planner.plan(Goal.parse(item, n, graph, List.of()), inv, o);
    }

    static List<PlanStep> of(Plan p, PlanStep.Kind k) {
        return p.steps.stream().filter(s -> s.kind == k).collect(Collectors.toList());
    }

    static Planner.Options chests(Object... kv) {
        Planner.Options o = new Planner.Options();
        o.containerStock = m(kv);
        o.defaultContainerDistance = 16;
        return o;
    }

    @Test
    public void takesTheFinishedItemFromAChestInsteadOfCrafting() {
        Plan p = plan("iron_pickaxe", 1, m(), chests("iron_pickaxe", 1));
        assertTrue(p.success);
        assertEquals(1, p.steps.size());
        assertEquals(PlanStep.Kind.WITHDRAW, p.steps.get(0).kind);
        assertTrue(p.steps.get(0).describe().contains("iron_pickaxe"));
    }

    @Test
    public void partialStockIsWithdrawnAndTheRestMined() {
        Plan p = plan("diamond", 5, m("iron_pickaxe", 1), chests("diamond", 2));
        assertTrue(p.success);
        List<PlanStep> w = of(p, PlanStep.Kind.WITHDRAW);
        assertEquals(1, w.size());
        assertEquals(2, w.get(0).count);
        assertEquals(3, of(p, PlanStep.Kind.MINE).stream().mapToInt(s -> s.count).sum());
        assertEquals(PlanStep.Kind.WITHDRAW, p.steps.get(0).kind);
        Map<String, Integer> end = GetItemPlannerTest.simulate(p, m("iron_pickaxe", 1));
        assertTrue(end.get("minecraft:diamond") >= 5);
    }

    @Test
    public void ingredientsComeFromChestsAndWithdrawalsAreHoistedAndMerged() {
        Plan p = plan("iron_pickaxe", 1, m(), chests("iron_ingot", 64, "oak_planks", 64, "crafting_table", 1));
        assertTrue(p.success);
        assertTrue(of(p, PlanStep.Kind.MINE).stream().noneMatch(s -> s.blocks.stream().anyMatch(b -> b.contains("iron"))));
        Set<String> seen = new HashSet<>();
        boolean inPrefix = true;
        for (PlanStep s : p.steps) {
            if (s.kind == PlanStep.Kind.WITHDRAW) {
                assertTrue("withdrawals must come first: " + p.explain(), inPrefix);
                assertTrue("one withdrawal per item", seen.add(s.items.get(0)));
            } else {
                inPrefix = false;
            }
        }
        assertTrue(seen.contains("minecraft:iron_ingot"));
        Map<String, Integer> end = GetItemPlannerTest.simulate(p, m());
        assertTrue(end.get("minecraft:iron_pickaxe") >= 1);
    }

    @Test
    public void protectedItemsInChestsAreNeverPlanned() {
        Planner.Options o = chests("iron_pickaxe", 1);
        o.protectedItems = Set.of("minecraft:iron_pickaxe");
        Plan p = plan("iron_pickaxe", 1, m(), o);
        assertTrue(of(p, PlanStep.Kind.WITHDRAW).isEmpty());
    }

    @Test
    public void cheapStuffFarAwayIsMadeNotFetched() {
        Planner.Options o = chests("stick", 4);
        o.defaultContainerDistance = 500;
        Plan p = plan("stick", 4, m("oak_planks", 2), o);
        assertTrue(p.success);
        assertTrue(of(p, PlanStep.Kind.WITHDRAW).isEmpty());
    }

    @Test
    public void tagGoalsWithdrawAnyMatchingItem() {
        Plan p = plan("#logs", 10, m(), chests("birch_log", 6, "spruce_log", 10));
        assertTrue(p.success);
        assertEquals(10, of(p, PlanStep.Kind.WITHDRAW).stream().mapToInt(s -> s.count).sum());
        assertTrue(of(p, PlanStep.Kind.MINE).isEmpty());
    }

    @Test
    public void chestOnlyItemsArePlannedFromStock() {
        // items with no recipe or mine source (unknown to the graph) can still come from a chest
        Plan p = planner.plan(Goal.item("minecraft:totem_of_undying", 1), m(), chests("totem_of_undying", 1));
        assertTrue(p.success);
        assertEquals(PlanStep.Kind.WITHDRAW, p.steps.get(0).kind);
        Plan none = planner.plan(Goal.item("minecraft:totem_of_undying", 2), m(), chests("totem_of_undying", 1));
        assertFalse(none.success);
    }

    @Test
    public void protectedBlocksAreNeverMineSources() {
        Planner.Options o = new Planner.Options();
        o.protectedBlocks = Set.of("minecraft:oak_log");
        Plan p = plan("oak_log", 3, m(), o);
        assertFalse(p.success);
    }

    @Test
    public void netherBlocksAddATravelStepWhenAllowed() {
        Plan p = plan("quartz", 4, m("wooden_pickaxe", 1), new Planner.Options());
        assertTrue(p.explain().toString(), p.success);
        List<PlanStep> t = of(p, PlanStep.Kind.TRAVEL);
        assertEquals(1, t.size());
        assertEquals(CostModel.NETHER, t.get(0).dimension());
        int ti = p.steps.indexOf(t.get(0));
        PlanStep mine = of(p, PlanStep.Kind.MINE).stream().filter(s -> s.blocks.contains("minecraft:nether_quartz_ore")).findFirst().orElseThrow();
        assertTrue(ti < p.steps.indexOf(mine));
        assertTrue(t.get(0).describe().contains("Nether"));
    }

    @Test
    public void netherBlocksFailWhenTheNetherIsDisallowed() {
        Planner.Options o = new Planner.Options();
        o.allowNether = false;
        Plan p = plan("quartz", 4, m("wooden_pickaxe", 1), o);
        assertFalse(p.success);
        o.containerStock = m("quartz", 4);
        assertTrue(plan("quartz", 4, m(), o).success);
    }

    @Test
    public void inTheNetherOverworldBlocksTravelBack() {
        Planner.Options o = new Planner.Options();
        o.dimension = CostModel.NETHER;
        Plan p = plan("oak_planks", 4, m(), o);
        assertTrue(p.success);
        assertEquals(CostModel.OVERWORLD, of(p, PlanStep.Kind.TRAVEL).get(0).dimension());
    }

    @Test
    public void optionsKeyChangesWithStock() {
        Planner.Options a = chests("diamond", 1), b = chests("diamond", 2);
        PlanCache c = new PlanCache(planner, 8);
        Plan p1 = c.plan(Goal.parse("diamond", 3, graph, List.of()), m("iron_pickaxe", 1), a);
        Plan p2 = c.plan(Goal.parse("diamond", 3, graph, List.of()), m("iron_pickaxe", 1), b);
        assertNotSame(p1, p2);
    }
}
