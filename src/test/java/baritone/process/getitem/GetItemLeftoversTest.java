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

import baritone.process.getitem.advanced.BookshelfPower;
import baritone.process.getitem.data.GetItemDataset;
import baritone.process.getitem.graph.FuelTable;
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.knowledge.FarmRegistry;
import baritone.process.getitem.plan.Goal;
import baritone.process.getitem.plan.Plan;
import baritone.process.getitem.plan.PlanStep;
import baritone.process.getitem.plan.Planner;
import baritone.process.getitem.plan.Source;
import baritone.process.getitem.sources.Compost;
import baritone.process.getitem.sources.SourceCatalog;
import baritone.process.getitem.sources.Yields;
import baritone.process.getitem.tasks.HarvestTask;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Leftover Get Item coverage: bookshelf power, fuel table, berries/kelp sources, compost placement, hoe hydration. */
public class GetItemLeftoversTest {
    static RecipeGraph graph;
    static Planner planner;

    @BeforeClass
    public static void load() {
        graph = new RecipeGraph(GetItemDataset.bundled());
        planner = Planner.vanilla(graph);
    }

    @Test
    public void bookshelfOffsetsAreTheVanillaRing() {
        List<BlockPos> offs = BookshelfPower.offsets();
        assertEquals(32, offs.size());
        assertEquals(32, new HashSet<>(offs).size());
        for (BlockPos p : offs) {
            assertTrue(Math.abs(p.getX()) == 2 || Math.abs(p.getZ()) == 2);
            assertTrue(p.getY() == 0 || p.getY() == 1);
            assertTrue(Math.abs(p.getX()) <= 2 && Math.abs(p.getZ()) <= 2);
        }
        BlockPos table = new BlockPos(10, 64, 10);
        BlockPos off = new BlockPos(2, 0, 0);
        assertEquals(new BlockPos(11, 64, 10), BookshelfPower.gap(table, off));
    }

    @Test
    public void bookshelfCountCapsAtFifteenAndNeedsAGap() {
        BlockPos table = BlockPos.ZERO;
        Set<BlockPos> shelves = new HashSet<>();
        List<BlockPos> offs = BookshelfPower.offsets();
        for (int i = 0; i < 15; i++) shelves.add(table.offset(offs.get(i)));
        assertEquals(15, BookshelfPower.count(shelves::contains, p -> true, table));
        shelves.add(table.offset(offs.get(15)));
        assertEquals(BookshelfPower.MAX, BookshelfPower.count(shelves::contains, p -> true, table));
        // 15 shelves, one with a blocked gap
        shelves.clear();
        for (int i = 0; i < 15; i++) shelves.add(table.offset(offs.get(i)));
        BlockPos first = offs.get(0);
        BlockPos blocked = BookshelfPower.gap(table, first);
        assertEquals(14, BookshelfPower.count(shelves::contains, p -> !p.equals(blocked), table));
        // a block that is not a valid offset does not count
        assertEquals(0, BookshelfPower.count(p -> p.equals(new BlockPos(1, 0, 0)), p -> true, table));
    }

    @Test
    public void fuelTableIncludesLavaBucketAndWood() {
        FuelTable fuel = FuelTable.vanilla(graph);
        assertEquals(20000, fuel.burnTicks("minecraft:lava_bucket"));
        assertEquals(1600, fuel.burnTicks("minecraft:coal"));
        assertEquals(4000, fuel.burnTicks("minecraft:dried_kelp_block"));
        assertTrue(fuel.burnTicks("minecraft:oak_planks") > 0);
        assertFalse(fuel.isFuel("minecraft:cobblestone"));
    }

    @Test
    public void farmsCatalogIncludesBerriesAndKelp() {
        assertEquals("minecraft:sweet_berries", Yields.CROPS.get("minecraft:sweet_berry_bush")[0]);
        assertEquals("minecraft:kelp", Yields.CROPS.get("minecraft:kelp")[0]);
        List<FarmRegistry.Farm> farms = new ArrayList<>();
        FarmRegistry.Farm berries = new FarmRegistry.Farm();
        berries.type = "minecraft:sweet_berry_bush";
        berries.dim = "minecraft:overworld";
        berries.x = 8;
        berries.y = 64;
        berries.z = 8;
        berries.ready = 6;
        berries.size = 8;
        farms.add(berries);
        FarmRegistry.Farm kelp = new FarmRegistry.Farm();
        kelp.type = "minecraft:kelp";
        kelp.dim = "minecraft:overworld";
        kelp.x = 20;
        kelp.y = 50;
        kelp.z = 0;
        kelp.ready = 10;
        kelp.size = 12;
        farms.add(kelp);
        List<Source> src = SourceCatalog.farms(farms, "minecraft:overworld", 0, 64, 0, 96);
        assertEquals(2, src.size());
        assertEquals("minecraft:sweet_berries", src.get(0).item);
        assertEquals(Source.Kind.HARVEST, src.get(0).kind);
        assertEquals("minecraft:kelp", src.get(1).item);
    }

    @Test
    public void compostPlaceSourceAsksForAComposter() {
        Map<String, Integer> inv = GetItemPlannerTest.inv("wheat_seeds", 48);
        List<Source> src = Compost.sources(inv, Collections.emptySet(), 6, "place", "minecraft:composter");
        assertEquals(1, src.size());
        assertEquals("minecraft:composter", src.get(0).tool);
        Planner.Options o = new Planner.Options();
        o.allowPlaceStations = true;
        o.sources = src;
        // already have a composter: plan should compost, not fail for lack of a station
        Map<String, Integer> with = GetItemPlannerTest.inv("wheat_seeds", 48, "composter", 1);
        Plan p = planner.plan(Goal.item("minecraft:bone_meal", 1), with, o);
        assertTrue(p.explain().toString(), p.success);
        boolean composts = false;
        for (PlanStep s : p.steps) {
            if (s.kind == PlanStep.Kind.GATHER && s.source != null && s.source.kind == Source.Kind.COMPOST) composts = true;
        }
        assertTrue(p.explain().toString(), composts);
    }

    @Test
    public void waterHydrationMatchesFarmlandRule() {
        BlockPos soil = new BlockPos(0, 64, 0);
        assertTrue(HarvestTask.waterCanHydrate(p -> p.equals(new BlockPos(4, 64, 0)), soil));
        assertTrue(HarvestTask.waterCanHydrate(p -> p.equals(new BlockPos(0, 65, 4)), soil));
        assertFalse(HarvestTask.waterCanHydrate(p -> p.equals(new BlockPos(5, 64, 0)), soil));
        assertFalse(HarvestTask.waterCanHydrate(p -> p.equals(new BlockPos(0, 63, 0)), soil));
    }
}
