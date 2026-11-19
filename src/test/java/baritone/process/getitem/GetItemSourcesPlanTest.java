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
import baritone.process.getitem.knowledge.FarmRegistry;
import baritone.process.getitem.knowledge.VillagerMemory;
import baritone.process.getitem.plan.*;
import baritone.process.getitem.sources.SourceCatalog;
import baritone.process.getitem.sources.Yields;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** M3 world sources in the planner: trades (A28), fishing (A29), hunting (A30), farms (A33/A39), trees (A34), animals (A35), mob farms (A38), yield-vs-time (A37). */
public class GetItemSourcesPlanTest {
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

    static Plan plan(String item, int n, Map<String, Integer> inv, List<Source> sources) {
        Planner.Options o = new Planner.Options();
        o.sources = sources;
        return planner.plan(Goal.parse(item, n, graph, List.of()), inv, o);
    }

    static List<PlanStep> gathers(Plan p) {
        return p.steps.stream().filter(s -> s.kind == PlanStep.Kind.GATHER).collect(Collectors.toList());
    }

    static VillagerMemory.Villager villager(String uuid, int x, VillagerMemory.Offer... offers) {
        VillagerMemory.Villager v = new VillagerMemory.Villager();
        v.uuid = uuid;
        v.profession = "librarian";
        v.level = 2;
        v.x = x;
        v.y = 64;
        v.z = 0;
        v.dim = "minecraft:overworld";
        v.offers.addAll(Arrays.asList(offers));
        return v;
    }

    @Test
    public void buysFromAVillagerWithEmeraldsInHand() {
        List<Source> src = SourceCatalog.trades(List.of(villager("u1", 10,
                new VillagerMemory.Offer("minecraft:emerald", 9, "minecraft:book", 1, "minecraft:enchanted_book", 1, 0, 12),
                new VillagerMemory.Offer("minecraft:emerald", 1, null, 0, "minecraft:bookshelf", 1, 0, 12))), "minecraft:overworld", 0, 64, 0, 64);
        Plan p = plan("bookshelf", 3, m("emerald", 5), src);
        assertTrue(p.failure, p.success);
        List<PlanStep> g = gathers(p);
        assertEquals(1, g.size());
        assertEquals(Source.Kind.TRADE, g.get(0).source.kind);
        assertEquals(3, g.get(0).batches);
        assertEquals(Integer.valueOf(3), g.get(0).inputs.get("minecraft:emerald"));
        assertTrue(g.get(0).source.ref.startsWith("u1|1|"));
        Map<String, Integer> end = GetItemPlannerTest.simulate(p, m("emerald", 5));
        assertEquals(2, (int) end.get("minecraft:emerald"));
    }

    @Test
    public void soldOutAndOtherDimensionTradesAreIgnored() {
        VillagerMemory.Villager v = villager("u2", 5, new VillagerMemory.Offer("minecraft:emerald", 1, null, 0, "minecraft:bookshelf", 1, 12, 12));
        assertTrue(SourceCatalog.trades(List.of(v), "minecraft:overworld", 0, 64, 0, 64).isEmpty());
        v.offers.get(0).uses = 0;
        assertTrue(SourceCatalog.trades(List.of(v), "minecraft:the_nether", 0, 64, 0, 64).isEmpty());
    }

    @Test
    public void tradeStockCapsAndTheRestIsMadeOtherwise() {
        List<Source> src = SourceCatalog.trades(List.of(villager("u3", 4,
                new VillagerMemory.Offer("minecraft:emerald", 1, null, 0, "minecraft:bread", 4, 10, 12))), "minecraft:overworld", 0, 64, 0, 64);
        // 2 trades left = 8 bread; the other 4 are crafted from wheat in hand
        Plan p = plan("bread", 12, m("emerald", 10, "wheat", 12), src);
        assertTrue(p.failure, p.success);
        assertEquals(8, gathers(p).stream().mapToInt(s -> s.count).sum());
        Map<String, Integer> end = GetItemPlannerTest.simulate(p, m("emerald", 10, "wheat", 12));
        assertTrue(end.get("minecraft:bread") >= 12);
    }

    @Test
    public void ripeFarmBeatsUnseenMiningAndUnripeIsIgnored() {
        FarmRegistry.Farm f = new FarmRegistry.Farm();
        f.type = "minecraft:wheat";
        f.x = 20;
        f.y = 64;
        f.z = 0;
        f.dim = "minecraft:overworld";
        f.size = 30;
        f.ready = 20;
        List<Source> src = SourceCatalog.farms(List.of(f), "minecraft:overworld", 0, 64, 0, 64);
        Plan p = plan("wheat", 10, m(), src);
        assertTrue(p.failure, p.success);
        assertEquals(Source.Kind.HARVEST, gathers(p).get(0).source.kind);
        f.ready = 0;
        assertTrue(SourceCatalog.farms(List.of(f), "minecraft:overworld", 0, 64, 0, 64).isEmpty());
    }

    @Test
    public void fishingNeedsARodAndPlansOne() {
        Plan p = plan("cod", 2, m("stick", 3, "string", 2), SourceCatalog.fishing(10));
        assertTrue(p.failure, p.success);
        assertTrue(p.steps.stream().anyMatch(s -> s.kind == PlanStep.Kind.CRAFT && s.recipe.output.equals("minecraft:fishing_rod")));
        PlanStep fish = gathers(p).get(0);
        assertEquals("minecraft:fishing_rod", fish.tool);
        GetItemPlannerTest.simulate(p, m("stick", 3, "string", 2));
        assertTrue(plan("cod", 2, m(), SourceCatalog.fishing(-1)).steps.stream().noneMatch(s -> s.kind == PlanStep.Kind.GATHER));
    }

    @Test
    public void huntsMobsSeenNearbyOnlyWhenAllowed() {
        Map<String, double[]> seen = new HashMap<>();
        seen.put("minecraft:enderman", new double[]{4, 20});
        Plan p = plan("ender_pearl", 2, m(), SourceCatalog.hunts(seen, true));
        assertTrue(p.failure, p.success);
        PlanStep g = gathers(p).get(0);
        assertEquals(Source.Kind.HUNT, g.source.kind);
        assertEquals(4, g.batches);
        assertTrue(p.risks.stream().anyMatch(r -> r.contains("Meteor")));
        assertTrue(SourceCatalog.hunts(seen, false).isEmpty());
        // more pearls than 4 endermen can give: fails rather than inventing kills
        assertFalse(plan("ender_pearl", 9, m(), SourceCatalog.hunts(seen, true)).success);
    }

    @Test
    public void shearsSheepForWool() {
        Map<String, double[]> sheep = new HashMap<>();
        sheep.put("white", new double[]{3, 10});
        Plan p = plan("white_wool", 4, m("shears", 1), SourceCatalog.animals(sheep, null));
        assertTrue(p.failure, p.success);
        assertEquals(Source.Kind.SHEAR, gathers(p).get(0).source.kind);
        assertEquals(2, gathers(p).get(0).batches);
    }

    @Test
    public void growsTreesWithBoneMealWhenNoWoodIsAround() {
        Planner.Options o = new Planner.Options();
        o.sources = SourceCatalog.trees(true);
        o.seenBlocks = new HashSet<>(); // no trees seen nearby
        Plan p = planner.plan(Goal.parse("oak_log", 5, graph, List.of()), m("oak_sapling", 1, "bone_meal", 8), o);
        assertTrue(p.failure, p.success);
        PlanStep g = gathers(p).get(0);
        assertEquals(Source.Kind.TREE, g.source.kind);
        assertEquals(Integer.valueOf(Yields.BONE_MEAL_PER_TREE), g.inputs.get("minecraft:bone_meal"));
        // with oak trees in sight and no sapling / bone meal in hand, chopping them wins (A37 yield vs time)
        o.seenBlocks = new HashSet<>(List.of("minecraft:oak_log"));
        Plan q = planner.plan(Goal.parse("oak_log", 5, graph, List.of()), m(), o);
        assertTrue(q.success);
        assertTrue(gathers(q).isEmpty());
    }

    @Test
    public void mobFarmSpecParsing() {
        SourceCatalog.MobFarm f = SourceCatalog.MobFarm.parse("100, 64, -20 = gunpowder|bone @3");
        assertNotNull(f);
        assertEquals(-20, f.z);
        assertEquals(List.of("minecraft:gunpowder", "minecraft:bone"), f.items);
        assertEquals(3, f.secondsPerItem, 1e-9);
        assertNull(SourceCatalog.MobFarm.parse("garbage"));
        Plan p = plan("gunpowder", 5, m(), SourceCatalog.mobFarms(List.of("10,64,0=gunpowder"), 0, 64, 0));
        assertTrue(p.failure, p.success);
        assertEquals(Source.Kind.MOB_FARM, gathers(p).get(0).source.kind);
    }

    @Test
    public void ripeCountsOnlyCountClusterMembers() {
        FarmRegistry r = new FarmRegistry();
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < 8; i++) pts.add(new int[]{i, 64, 0});
        r.update("minecraft:carrots", false, pts, List.of(new int[]{1, 64, 0}, new int[]{2, 64, 0}, new int[]{500, 64, 0}), "minecraft:overworld", 0, 0, 32, 1);
        assertEquals(2, r.all().get(0).ready);
    }
}
