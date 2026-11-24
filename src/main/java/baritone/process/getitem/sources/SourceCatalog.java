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

package baritone.process.getitem.sources;

import baritone.process.getitem.knowledge.FarmRegistry;
import baritone.process.getitem.knowledge.VillagerMemory;
import baritone.process.getitem.plan.Source;

import java.util.*;

/**
 * Builds the planner's M3 {@link Source}s from what the world knowledge holds (pure; the process supplies the
 * game-side facts). Travel is estimated at walking speed (~4.3 blocks/s); per-batch times are human-paced.
 */
public final class SourceCatalog {
    public static final double WALK = 4.3;

    private SourceCatalog() {
    }

    static double travel(double dist) {
        return dist / WALK;
    }

    static double dist(int x, int y, int z, int px, int py, int pz) {
        return Math.sqrt((double) (x - px) * (x - px) + (double) (y - py) * (y - py) + (double) (z - pz) * (z - pz));
    }

    /** A28: one source per remembered, in-stock offer of villagers in this dimension within {@code radius}. */
    public static List<Source> trades(List<VillagerMemory.Villager> villagers, String dim, int px, int py, int pz, int radius) {
        List<Source> out = new ArrayList<>();
        for (VillagerMemory.Villager v : villagers) {
            if (!Objects.equals(v.dim, dim)) continue;
            double d = dist(v.x, v.y, v.z, px, py, pz);
            if (d > radius) continue;
            for (int i = 0; i < v.offers.size(); i++) {
                VillagerMemory.Offer o = v.offers.get(i);
                if (o.outOfStock() || o.result == null || o.resultCount <= 0) continue;
                Map<String, Integer> in = new LinkedHashMap<>();
                in.merge(o.costA, o.countA, Integer::sum);
                if (o.costB != null && o.countB > 0) in.merge(o.costB, o.countB, Integer::sum);
                int left = o.maxUses > 0 ? o.maxUses - o.uses : 1;
                out.add(new Source(Source.Kind.TRADE, o.result, o.resultCount, 1.5, travel(d) + 4, left, in, null,
                        v.uuid + "|" + i + "|" + v.x + "," + v.y + "," + v.z,
                        "Trade with " + v.profession + " at " + v.x + "," + v.y + "," + v.z + " (" + o.describe() + ")"));
            }
        }
        return out;
    }

    /** A33/A39: crop farms with mature plants (only what is ripe now; growth isn't waited for). */
    public static List<Source> farms(List<FarmRegistry.Farm> farms, String dim, int px, int py, int pz, int radius) {
        List<Source> out = new ArrayList<>();
        for (FarmRegistry.Farm f : farms) {
            if (f.animals || !Objects.equals(f.dim, dim) || f.ready <= 0) continue;
            Object[] crop = Yields.CROPS.get(f.type);
            if (crop == null) continue;
            double d = dist(f.x, f.y, f.z, px, py, pz);
            if (d > radius) continue;
            out.add(new Source(Source.Kind.HARVEST, (String) crop[0], (double) crop[1], 1.2, travel(d) + 2, f.ready,
                    Collections.emptyMap(), null, f.x + "," + f.y + "," + f.z + "," + Math.max(4, (int) Math.ceil(Math.sqrt(f.size)) + 4),
                    "Harvest " + f.ready + " ripe " + f.type.replace("minecraft:", "") + " at " + f.x + "," + f.y + "," + f.z));
        }
        return out;
    }

    /** A29: fishing in water {@code waterDist} blocks away (negative = no water known). */
    public static List<Source> fishing(double waterDist) {
        List<Source> out = new ArrayList<>();
        if (waterDist < 0) return out;
        for (Map.Entry<String, Double> e : Yields.FISH.entrySet()) {
            out.add(new Source(Source.Kind.FISH, e.getKey(), e.getValue(), 22, travel(waterDist) + 3, Integer.MAX_VALUE,
                    Collections.emptyMap(), "minecraft:fishing_rod", "water", "Fish"));
        }
        return out;
    }

    /**
     * A30: hunt mobs seen nearby. {@code seen}: entity type -> {count, distance}. Only with a kill handler
     * (Meteor); Baritone itself never attacks.
     */
    public static List<Source> hunts(Map<String, double[]> seen, boolean allowHostile) {
        List<Source> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : new TreeMap<>(seen).entrySet()) {
            int count = (int) e.getValue()[0];
            if (count <= 0) continue;
            for (Yields.Drop d : Yields.dropsOf(e.getKey())) {
                if (d.hostile && !allowHostile) continue;
                out.add(new Source(Source.Kind.HUNT, d.item, d.perKill, d.hostile ? 12 : 7, travel(e.getValue()[1]) + 2, count,
                        Collections.emptyMap(), null, e.getKey(), "Hunt " + e.getKey().replace("minecraft:", "")));
            }
        }
        return out;
    }

    /** A35: shear sheep ({@code woolColor -> {count, distance}}) and milk cows ({count, distance} or null). */
    public static List<Source> animals(Map<String, double[]> sheepByColor, double[] cows) {
        List<Source> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : new TreeMap<>(sheepByColor).entrySet()) {
            int count = (int) e.getValue()[0];
            if (count <= 0) continue;
            out.add(new Source(Source.Kind.SHEAR, "minecraft:" + e.getKey() + "_wool", 2.0, 4, travel(e.getValue()[1]) + 2, count,
                    Collections.emptyMap(), "minecraft:shears", e.getKey(), "Shear " + e.getKey() + " sheep"));
        }
        if (cows != null && cows[0] > 0) {
            out.add(new Source(Source.Kind.MILK, "minecraft:milk_bucket", 1, 3, travel(cows[1]) + 2, Integer.MAX_VALUE,
                    Collections.singletonMap("minecraft:bucket", 1), null, "minecraft:cow", "Milk a cow"));
        }
        return out;
    }

    /** A34: grow trees from saplings (with bone meal when allowed, else waiting for growth). */
    public static List<Source> trees(boolean boneMeal) {
        List<Source> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : Yields.TREES.entrySet()) {
            Map<String, Integer> in = new LinkedHashMap<>();
            in.put(e.getKey(), 1);
            if (boneMeal) in.put("minecraft:bone_meal", Yields.BONE_MEAL_PER_TREE);
            out.add(new Source(Source.Kind.TREE, (String) e.getValue()[0], (double) e.getValue()[1], boneMeal ? 25 : 900, 5,
                    Integer.MAX_VALUE, in, null, e.getKey(), "Grow a " + e.getKey().replace("minecraft:", "").replace("_sapling", "") + " tree"));
        }
        return out;
    }

    /**
     * A38: user mob farms from {@code getItemMobFarms} entries "x,y,z=item|item[@secondsPerItem]" (dimension:
     * the current one).
     */
    public static List<Source> mobFarms(List<String> specs, int px, int py, int pz) {
        List<Source> out = new ArrayList<>();
        for (String spec : specs) {
            MobFarm f = MobFarm.parse(spec);
            if (f == null) continue;
            double d = dist(f.x, f.y, f.z, px, py, pz);
            for (String item : f.items) {
                out.add(new Source(Source.Kind.MOB_FARM, item, 1, f.secondsPerItem, travel(d) + 3, Integer.MAX_VALUE,
                        Collections.emptyMap(), null, f.x + "," + f.y + "," + f.z, "Collect at mob farm " + f.x + "," + f.y + "," + f.z));
            }
        }
        return out;
    }

    public static final class MobFarm {
        public final int x, y, z;
        public final List<String> items;
        public final double secondsPerItem;

        MobFarm(int x, int y, int z, List<String> items, double secondsPerItem) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.items = items;
            this.secondsPerItem = secondsPerItem;
        }

        public static MobFarm parse(String spec) {
            try {
                String[] kv = spec.split("=", 2);
                if (kv.length != 2) return null;
                String[] xyz = kv[0].trim().split(",");
                String rest = kv[1].trim();
                double rate = 6;
                int at = rest.indexOf('@');
                if (at >= 0) {
                    rate = Double.parseDouble(rest.substring(at + 1).trim());
                    rest = rest.substring(0, at);
                }
                List<String> items = new ArrayList<>();
                for (String s : rest.split("\\|")) {
                    s = s.trim();
                    if (!s.isEmpty()) items.add(s.contains(":") ? s : "minecraft:" + s);
                }
                if (items.isEmpty() || rate <= 0) return null;
                return new MobFarm(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()), items, rate);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
