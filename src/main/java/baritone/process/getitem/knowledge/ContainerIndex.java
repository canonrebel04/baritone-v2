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

package baritone.process.getitem.knowledge;

import java.util.*;

/**
 * Container memory (plan A11): what was inside every chest / barrel / shulker box / ender chest the player opened
 * (by Get Item or by hand), with custom names for the protect list. Bounded (least recently seen dropped first).
 */
public final class ContainerIndex {
    public static final String CHEST = "chest", BARREL = "barrel", SHULKER = "shulker", ENDER_CHEST = "ender_chest", OTHER = "other";
    public static final int MAX = 512;

    /** One remembered container (public fields for JSON). */
    public static final class Container {
        public int x, y, z;
        public String dim;
        public String type;
        public String name;
        public Map<String, Integer> items = new TreeMap<>();
        public int freeSlots;
        public int slots;
        public long seen;

        public Container() {
        }

        public Container(int x, int y, int z, String dim, String type, String name, Map<String, Integer> items, int freeSlots, int slots, long seen) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.type = type;
            this.name = name;
            this.items = new TreeMap<>(items);
            this.freeSlots = freeSlots;
            this.slots = slots;
            this.seen = seen;
        }

        public double dist2(double px, double py, double pz) {
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        public String key() {
            return dim + "|" + x + "," + y + "," + z;
        }
    }

    private final LinkedHashMap<String, Container> byKey = new LinkedHashMap<>(64, 0.75f, true);

    public synchronized void record(Container c) {
        byKey.remove(c.key());
        byKey.put(c.key(), c);
        while (byKey.size() > MAX) {
            Iterator<String> it = byKey.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized void remove(String dim, int x, int y, int z) {
        byKey.remove(dim + "|" + x + "," + y + "," + z);
    }

    public synchronized Container get(String dim, int x, int y, int z) {
        return byKey.get(dim + "|" + x + "," + y + "," + z);
    }

    public synchronized List<Container> all() {
        return new ArrayList<>(byKey.values());
    }

    public synchronized int size() {
        return byKey.size();
    }

    /** Containers usable under the policy, in {@code dim}, within {@code radius} of the point. */
    public synchronized List<Container> usable(ProtectPolicy policy, String dim, double px, double py, double pz, double radius) {
        List<Container> out = new ArrayList<>();
        for (Container c : byKey.values()) {
            if (!Objects.equals(c.dim, dim) && !ENDER_CHEST.equals(c.type)) continue;
            if (policy.containerProtected(c)) continue;
            if (!ENDER_CHEST.equals(c.type) && radius > 0 && c.dist2(px, py, pz) > radius * radius) continue;
            out.add(c);
        }
        out.sort(Comparator.comparingDouble((Container c) -> c.dist2(px, py, pz)).thenComparing(Container::key));
        return out;
    }

    /** Total stock per item across usable containers (protected items left out: they're never touched). */
    public Map<String, Integer> stock(ProtectPolicy policy, String dim, double px, double py, double pz, double radius) {
        Map<String, Integer> out = new TreeMap<>();
        for (Container c : usable(policy, dim, px, py, pz, radius)) {
            c.items.forEach((item, n) -> {
                if (!policy.itemProtected(item)) out.merge(item, n, Integer::sum);
            });
        }
        return out;
    }

    /** Distance (blocks) to the nearest usable container holding each item. */
    public Map<String, Double> nearest(ProtectPolicy policy, String dim, double px, double py, double pz, double radius) {
        Map<String, Double> out = new TreeMap<>();
        for (Container c : usable(policy, dim, px, py, pz, radius)) {
            double d = ENDER_CHEST.equals(c.type) && !Objects.equals(c.dim, dim) ? radius : Math.sqrt(c.dist2(px, py, pz));
            for (String item : c.items.keySet()) out.merge(item, d, Math::min);
        }
        return out;
    }

    /** Usable containers holding {@code item}, nearest first. */
    public List<Container> holding(String item, ProtectPolicy policy, String dim, double px, double py, double pz, double radius) {
        List<Container> out = new ArrayList<>();
        if (policy.itemProtected(item)) return out;
        for (Container c : usable(policy, dim, px, py, pz, radius)) if (c.items.getOrDefault(item, 0) > 0) out.add(c);
        return out;
    }

    /** Where to stash {@code item}: a usable container that already holds it, else one with the most free slots. */
    public Container stashFor(String item, ProtectPolicy policy, String dim, double px, double py, double pz, double radius) {
        Container best = null;
        for (Container c : usable(policy, dim, px, py, pz, radius)) {
            if (c.freeSlots <= 0 || SHULKER.equals(c.type) && item.endsWith("shulker_box")) continue;
            if (c.items.containsKey(item)) return c;
            if (best == null || c.freeSlots > best.freeSlots) best = c;
        }
        return best;
    }

    /** Text search for {@code #get where}. */
    public List<String> find(String item, String dim, double px, double py, double pz) {
        List<String> out = new ArrayList<>();
        List<Container> cs = all();
        cs.sort(Comparator.comparingDouble(c -> c.dist2(px, py, pz)));
        for (Container c : cs) {
            Integer n = c.items.get(item);
            if (n == null) continue;
            out.add(n + "x in " + c.type + (c.name != null ? " \"" + c.name + "\"" : "") + " at " + c.x + "," + c.y + "," + c.z
                    + (Objects.equals(c.dim, dim) ? " (" + Math.round(Math.sqrt(c.dist2(px, py, pz))) + "m)" : " [" + c.dim + "]"));
        }
        return out;
    }
}
