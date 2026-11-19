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

import baritone.process.getitem.inventory.InventoryPlanner;
import baritone.process.getitem.inventory.ToolDurability;
import baritone.process.getitem.knowledge.*;
import baritone.process.getitem.safety.BudgetGuard;
import baritone.process.getitem.safety.SafetyMonitor;
import baritone.process.getitem.safety.StationSpotPicker;
import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

import static org.junit.Assert.*;

/** A11-A20, A24, A32, A43-A46, A49: headless world-knowledge, protection and safety logic. */
public class GetItemKnowledgeTest {
    static final String OW = "minecraft:overworld";

    static ContainerIndex.Container chest(int x, String name, Object... kv) {
        Map<String, Integer> items = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) items.put("minecraft:" + kv[i], (Integer) kv[i + 1]);
        return new ContainerIndex.Container(x, 64, 0, OW, ContainerIndex.CHEST, name, items, 10, 27, 1);
    }

    static ProtectPolicy policy() {
        return new ProtectPolicy(List.of("protect", "Do Not Touch"), List.of("minecraft:elytra"), List.of(), false);
    }

    @Test
    public void protectedChestNamesAreCaseInsensitiveSubstrings() {
        ProtectPolicy p = policy();
        assertTrue(p.nameProtected("My PROTECTED loot"));
        assertTrue(p.nameProtected("do not touch!"));
        assertFalse(p.nameProtected("Storage"));
        assertFalse(p.nameProtected(null));
    }

    @Test
    public void usableSkipsProtectedNamesPositionsEnderChestsAndOtherDimensions() {
        ContainerIndex idx = new ContainerIndex();
        idx.record(chest(5, null, "iron_ingot", 10));
        idx.record(chest(8, "protect me", "iron_ingot", 64));
        ContainerIndex.Container ender = new ContainerIndex.Container(9, 64, 0, OW, ContainerIndex.ENDER_CHEST, null, Map.of("minecraft:iron_ingot", 5), 0, 27, 1);
        idx.record(ender);
        idx.record(new ContainerIndex.Container(2, 64, 0, "minecraft:the_nether", ContainerIndex.CHEST, null, Map.of("minecraft:iron_ingot", 7), 0, 27, 1));
        idx.record(chest(3, null, "iron_ingot", 3));
        ProtectPolicy pos = new ProtectPolicy(List.of("protect"), List.of(), List.of(ProtectPolicy.posKey(3, 64, 0)), false);
        Map<String, Integer> stock = idx.stock(pos, OW, 0, 64, 0, 64);
        assertEquals(Integer.valueOf(10), stock.get("minecraft:iron_ingot"));
        ProtectPolicy withEnder = new ProtectPolicy(List.of("protect"), List.of(), List.of(), true);
        assertEquals(Integer.valueOf(18), idx.stock(withEnder, OW, 0, 64, 0, 64).get("minecraft:iron_ingot"));
    }

    @Test
    public void protectedItemsNeverCountAsStock() {
        ContainerIndex idx = new ContainerIndex();
        idx.record(chest(1, null, "elytra", 1, "diamond", 4));
        Map<String, Integer> s = idx.stock(policy(), OW, 0, 64, 0, 64);
        assertNull(s.get("minecraft:elytra"));
        assertEquals(Integer.valueOf(4), s.get("minecraft:diamond"));
    }

    @Test
    public void radiusLimitsAndNearestChestWins() {
        ContainerIndex idx = new ContainerIndex();
        idx.record(chest(40, null, "coal", 5));
        idx.record(chest(10, null, "coal", 5));
        idx.record(chest(200, null, "coal", 50));
        assertEquals(Integer.valueOf(10), idx.stock(policy(), OW, 0, 64, 0, 64).get("minecraft:coal"));
        assertEquals(10, idx.holding("minecraft:coal", policy(), OW, 0, 64, 0, 64).get(0).x);
        assertEquals(10.0, idx.nearest(policy(), OW, 0, 64, 0, 64).get("minecraft:coal"), 1.0);
        assertFalse(idx.find("minecraft:coal", OW, 0, 64, 0).isEmpty());
    }

    @Test
    public void reRecordingReplacesContents() {
        ContainerIndex idx = new ContainerIndex();
        idx.record(chest(1, null, "coal", 5));
        idx.record(chest(1, null, "stick", 2));
        assertEquals(1, idx.size());
        assertNull(idx.stock(policy(), OW, 0, 64, 0, 64).get("minecraft:coal"));
    }

    @Test
    public void containerIndexIsBounded() {
        ContainerIndex idx = new ContainerIndex();
        for (int i = 0; i < ContainerIndex.MAX + 50; i++) idx.record(chest(i, null, "coal", 1));
        assertEquals(ContainerIndex.MAX, idx.size());
    }

    @Test
    public void stationRegistryKeepsPlacedFlag() {
        StationRegistry r = new StationRegistry();
        r.markPlaced("minecraft:crafting_table", 1, 64, 1, OW, 1);
        r.record("minecraft:crafting_table", 1, 64, 1, OW, 2);
        r.record("minecraft:furnace", 30, 64, 0, OW, 2);
        assertEquals(1, r.placedByUs(OW, 0, 64, 0, 16).size());
        assertEquals(Set.of("minecraft:crafting_table"), r.known(OW, 0, 64, 0, 16));
        assertEquals(1, r.near("minecraft:furnace", OW, 0, 64, 0, 64).size());
        r.remove(OW, 30, 64, 0);
        assertTrue(r.near("minecraft:furnace", OW, 0, 64, 0, 64).isEmpty());
    }

    @Test
    public void oreLocatorPrefersBigCloseVeins() {
        List<int[]> pos = new ArrayList<>();
        pos.add(new int[]{100, -58, 0});
        for (int i = 0; i < 5; i++) pos.add(new int[]{20 + i, -58, 0});
        List<OreLocator.Vein> v = OreLocator.veins("minecraft:diamond_ore", pos, 0, -58, 0, null, OW);
        assertEquals(2, v.size());
        assertEquals(5, v.get(0).size);
        HazardMap h = new HazardMap();
        h.add(HazardMap.Type.LAVA, 22, -58, 0, OW, 8, 100, 0);
        assertEquals(1, OreLocator.veins("minecraft:diamond_ore", pos, 0, -58, 0, h, OW).get(0).size);
    }

    @Test
    public void biomeHintsKnowDebrisAndDiamonds() {
        assertEquals("minecraft:the_nether", BiomeHints.of("minecraft:ancient_debris").dimension);
        assertEquals(-58, BiomeHints.exploreY(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), 64));
        assertEquals(64, BiomeHints.exploreY(List.of("minecraft:oak_log"), 64));
        assertTrue(BiomeHints.describe(List.of("minecraft:ancient_debris")).get(0).contains("Nether"));
    }

    @Test
    public void spiralVisitsDistinctRingsOutward() {
        Set<String> seen = new HashSet<>();
        int lastRing = 0;
        for (int i = 0; i < 24; i++) {
            int[] p = ExplorationPlanner.spiral(0, 0, 32, i);
            assertTrue(seen.add(p[0] + "," + p[1]));
            int ring = Math.max(Math.abs(p[0]), Math.abs(p[1])) / 32;
            assertTrue(ring >= lastRing - 0 || ring == lastRing);
            assertTrue(ring <= 3);
            lastRing = Math.max(lastRing, ring);
        }
        int[] w = ExplorationPlanner.waypoint(List.of("minecraft:diamond_ore"), 0, 0, 0, 32, -60);
        assertEquals(-58, w[1]);
        assertEquals(Integer.MIN_VALUE, ExplorationPlanner.waypoint(List.of("minecraft:oak_log"), 0, 0, 0, 32, -60)[1]);
        assertEquals(-50, ExplorationPlanner.waypoint(List.of("minecraft:diamond_ore"), 0, 0, 0, 32, -50)[1]);
    }

    @Test
    public void dropPickerNearestNeeded() {
        List<DropPicker.Drop> d = List.of(
                new DropPicker.Drop("minecraft:cobblestone", 2, 64, 0, 1, 0),
                new DropPicker.Drop("minecraft:diamond", 6, 64, 0, 1, 0),
                new DropPicker.Drop("minecraft:diamond", 4, 80, 0, 1, 0),
                new DropPicker.Drop("minecraft:diamond", 30, 64, 0, 1, 0));
        DropPicker.Drop p = DropPicker.pick(d, Set.of("minecraft:diamond"), 0, 64, 0, 12, 4);
        assertEquals(6, p.x, 1e-9);
        assertNull(DropPicker.pick(d, Set.of("minecraft:stick"), 0, 64, 0, 12, 4));
    }

    @Test
    public void villagerSellersCheapestFirst() {
        VillagerMemory m = new VillagerMemory();
        VillagerMemory.Villager a = new VillagerMemory.Villager();
        a.uuid = "a";
        a.profession = "librarian";
        a.offers.add(new VillagerMemory.Offer("minecraft:emerald", 20, "minecraft:book", 1, "minecraft:enchanted_book", 1, 0, 12));
        VillagerMemory.Villager b = new VillagerMemory.Villager();
        b.uuid = "b";
        b.profession = "librarian";
        b.offers.add(new VillagerMemory.Offer("minecraft:emerald", 9, "minecraft:book", 1, "minecraft:enchanted_book", 1, 12, 12));
        m.record(a);
        m.record(b);
        List<String> s = m.sellers("minecraft:enchanted_book");
        assertEquals(2, s.size());
        assertTrue(s.get(0).contains("9x emerald") && s.get(0).contains("sold out"));
    }

    @Test
    public void farmsNeedMinimumClusterSize() {
        FarmRegistry f = new FarmRegistry();
        List<int[]> wheat = new ArrayList<>();
        for (int i = 0; i < 8; i++) wheat.add(new int[]{i, 64, 0});
        wheat.add(new int[]{50, 64, 50});
        f.update("minecraft:wheat", false, wheat, OW, 0, 0, 64, 1);
        assertEquals(1, f.all().size());
        assertEquals(8, f.all().get(0).size);
        f.update("minecraft:wheat", false, List.of(), OW, 0, 0, 64, 2);
        assertTrue(f.all().isEmpty());
    }

    @Test
    public void hazardPenaltyFallsOffAndMobsExpire() {
        HazardMap h = new HazardMap();
        h.add(HazardMap.Type.LAVA, 0, 64, 0, OW, 10, 10, 0);
        h.add(HazardMap.Type.MOBS, 0, 64, 0, OW, 10, 5, 0);
        assertEquals(15, h.penalty(0, 64, 0, OW), 1e-9);
        assertTrue(h.penalty(5, 64, 0, OW) < 15);
        assertEquals(0, h.penalty(20, 64, 0, OW), 1e-9);
        assertEquals(0, h.penalty(0, 64, 0, "minecraft:the_nether"), 1e-9);
        h.expire(10_000, 1000);
        assertEquals(10, h.penalty(0, 64, 0, OW), 1e-9);
    }

    @Test
    public void knowledgeRoundTripsAndToleratesGarbage() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.containers.record(chest(4, "Storage", "coal", 12));
        kb.stations.markPlaced("minecraft:furnace", 1, 2, 3, OW, 5);
        kb.hazards.add(HazardMap.Type.SPAWNER, 9, 9, 9, OW, 12, 5, 0);
        kb.hazards.add(HazardMap.Type.MOBS, 1, 1, 1, OW, 12, 5, 0);
        kb.pendingGoal = "minecraft:iron_pickaxe";
        kb.pendingAmount = 1;
        StringWriter w = new StringWriter();
        kb.write(w);
        KnowledgeBase back = new KnowledgeBase();
        assertTrue(back.read(new StringReader(w.toString())));
        assertEquals(Integer.valueOf(12), back.containers.get(OW, 4, 64, 0).items.get("minecraft:coal"));
        assertEquals("Storage", back.containers.get(OW, 4, 64, 0).name);
        assertTrue(back.stations.all().get(0).placedByUs);
        assertEquals(1, back.hazards.all().size());
        assertEquals("minecraft:iron_pickaxe", back.pendingGoal);
        assertFalse(new KnowledgeBase().read(new StringReader("{not json")));
        assertFalse(new KnowledgeBase().read(new StringReader("{\"version\":99}")));
        assertEquals("mc.example.org_25565.json", KnowledgeBase.fileName("mc.example.org:25565"));
    }

    @Test
    public void inventoryDropsJunkThenStashesNeverNeededOrProtected() {
        List<InventoryPlanner.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 33; i++) slots.add(new InventoryPlanner.Slot(i, "minecraft:dirt", 64 - i, false));
        slots.add(new InventoryPlanner.Slot(33, "minecraft:granite", 10, false));
        slots.add(new InventoryPlanner.Slot(34, "minecraft:iron_ore", 3, false));
        slots.add(new InventoryPlanner.Slot(35, "minecraft:diamond_pickaxe", 1, true));
        InventoryPlanner.Decision d = InventoryPlanner.decide(slots, 36, 3, Set.of("minecraft:granite", "minecraft:iron_ore"),
                Set.of("minecraft:iron_ore"), Set.of("minecraft:dirt"), true, true);
        assertEquals(List.of(33), d.drop);
        assertTrue(d.stash.isEmpty()); // dirt protected, iron needed, pickaxe a tool
        InventoryPlanner.Decision d2 = InventoryPlanner.decide(slots, 36, 3, Set.of("minecraft:granite"), Set.of(), Set.of(), true, true);
        assertEquals(List.of(33), d2.drop);
        assertEquals(List.of(0, 1), d2.stash);
        assertTrue(InventoryPlanner.decide(slots.subList(0, 10), 36, 3, Set.of(), Set.of(), Set.of(), true, true).isEmpty());
    }

    @Test
    public void nearlyBrokenAndMendingToolsAreSpared() {
        assertTrue(ToolDurability.usable(0, 250, false, 8, 0.15));
        assertFalse(ToolDurability.usable(245, 250, false, 8, 0.15));
        assertFalse(ToolDurability.usable(1500, 1561, true, 8, 0.15));
        assertTrue(ToolDurability.usable(1500, 1561, false, 8, 0.15));
        assertTrue(ToolDurability.usable(0, 0, false, 8, 0.15));
    }

    @Test
    public void safetyMonitorPriorities() {
        SafetyMonitor.Settings s = new SafetyMonitor.Settings();
        SafetyMonitor.Inputs in = new SafetyMonitor.Inputs();
        assertTrue(SafetyMonitor.evaluate(in, s).ok());
        in.hostilesNear = 2;
        in.nearestHostile = 5;
        assertEquals(SafetyMonitor.Action.FLEE, SafetyMonitor.evaluate(in, s).action);
        s.hostileAction = SafetyMonitor.HostileAction.parse("Pause");
        assertEquals(SafetyMonitor.Action.PAUSE, SafetyMonitor.evaluate(in, s).action);
        s.hostileAction = SafetyMonitor.HostileAction.parse("ignore");
        in.food = 4;
        in.hasFood = true;
        assertEquals(SafetyMonitor.Action.EAT, SafetyMonitor.evaluate(in, s).action);
        in.hasFood = false;
        assertTrue(SafetyMonitor.evaluate(in, s).ok());
        in.food = 20;
        in.health = 5;
        assertEquals(SafetyMonitor.Action.PAUSE, SafetyMonitor.evaluate(in, s).action);
        in.health = 20;
        s.avoidNight = true;
        in.night = true;
        in.skyVisible = true;
        assertEquals(SafetyMonitor.Action.PAUSE, SafetyMonitor.evaluate(in, s).action);
        in.skyVisible = false;
        assertTrue(SafetyMonitor.evaluate(in, s).ok());
        assertEquals(SafetyMonitor.HostileAction.FLEE, SafetyMonitor.HostileAction.parse("whatever"));
    }

    @Test
    public void budgetGuardLimits() {
        BudgetGuard.Limits l = new BudgetGuard.Limits();
        assertNull(BudgetGuard.check(l, 1e6, 1e6, -100, "minecraft:the_end"));
        l.maxMinutes = 5;
        assertNotNull(BudgetGuard.check(l, 301, 0, 64, OW));
        l.maxMinutes = 0;
        l.maxDistance = 100;
        assertNotNull(BudgetGuard.check(l, 0, 101, 64, OW));
        l.minY = 0;
        assertNotNull(BudgetGuard.check(l, 0, 0, -5, OW));
        l.allowNether = false;
        assertNotNull(BudgetGuard.check(l, 0, 0, 64, "minecraft:the_nether"));
        assertNull(BudgetGuard.check(l, 0, 0, 64, OW));
    }

    @Test
    public void stationSpotAvoidsHazardsAndTheWayAhead() {
        StationSpotPicker.Probe flat = new StationSpotPicker.Probe() {
            public boolean free(int x, int y, int z) { return y >= 64; }
            public boolean solid(int x, int y, int z) { return y < 64; }
            public double hazard(int x, int y, int z) { return x < 0 ? 10 : 0; }
        };
        int[] s = StationSpotPicker.pick(0, 64, 0, new int[]{1, 0}, flat);
        assertNotNull(s);
        assertEquals(64, s[1]);
        assertTrue(s[0] >= 0);
        assertFalse(s[0] == 1 && s[2] == 0);
        assertEquals(1, Math.abs(s[0]) + Math.abs(s[2]));
        StationSpotPicker.Probe none = new StationSpotPicker.Probe() {
            public boolean free(int x, int y, int z) { return false; }
            public boolean solid(int x, int y, int z) { return true; }
            public double hazard(int x, int y, int z) { return 0; }
        };
        assertNull(StationSpotPicker.pick(0, 64, 0, null, none));
    }
}
