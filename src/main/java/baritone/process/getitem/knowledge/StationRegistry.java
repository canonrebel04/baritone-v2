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
 * Station registry (plan A12): crafting tables, furnaces, anvils... seen or placed by Get Item, so known ones are
 * reused before placing a new one; "placed by us" stations can be picked up again (A32).
 */
public final class StationRegistry {
    public static final int MAX = 256;

    public static final class Station {
        public String block;
        public int x, y, z;
        public String dim;
        public boolean placedByUs;
        public long seen;

        public Station() {
        }

        public Station(String block, int x, int y, int z, String dim, boolean placedByUs, long seen) {
            this.block = block;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.placedByUs = placedByUs;
            this.seen = seen;
        }

        String key() {
            return dim + "|" + x + "," + y + "," + z;
        }

        public double dist2(double px, double py, double pz) {
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final LinkedHashMap<String, Station> byKey = new LinkedHashMap<>(32, 0.75f, true);

    public synchronized void record(String block, int x, int y, int z, String dim, long now) {
        String k = dim + "|" + x + "," + y + "," + z;
        Station old = byKey.get(k);
        boolean ours = old != null && old.placedByUs && Objects.equals(old.block, block);
        byKey.put(k, new Station(block, x, y, z, dim, ours, now));
        trim();
    }

    public synchronized void markPlaced(String block, int x, int y, int z, String dim, long now) {
        Station s = new Station(block, x, y, z, dim, true, now);
        byKey.put(s.key(), s);
        trim();
    }

    public synchronized void remove(String dim, int x, int y, int z) {
        byKey.remove(dim + "|" + x + "," + y + "," + z);
    }

    private void trim() {
        while (byKey.size() > MAX) {
            Iterator<String> it = byKey.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** Known stations of this block within {@code radius}, nearest first. */
    public synchronized List<Station> near(String block, String dim, double px, double py, double pz, double radius) {
        List<Station> out = new ArrayList<>();
        for (Station s : byKey.values()) {
            if (Objects.equals(s.block, block) && Objects.equals(s.dim, dim) && s.dist2(px, py, pz) <= radius * radius) out.add(s);
        }
        out.sort(Comparator.comparingDouble(s -> s.dist2(px, py, pz)));
        return out;
    }

    /** Distinct station block ids known within radius (feeds the planner's "stations nearby"). */
    public synchronized Set<String> known(String dim, double px, double py, double pz, double radius) {
        Set<String> out = new TreeSet<>();
        for (Station s : byKey.values()) {
            if (Objects.equals(s.dim, dim) && s.dist2(px, py, pz) <= radius * radius) out.add(s.block);
        }
        return out;
    }

    public synchronized List<Station> placedByUs(String dim, double px, double py, double pz, double radius) {
        List<Station> out = new ArrayList<>();
        for (Station s : byKey.values()) {
            if (s.placedByUs && Objects.equals(s.dim, dim) && s.dist2(px, py, pz) <= radius * radius) out.add(s);
        }
        return out;
    }

    public synchronized List<Station> all() {
        return new ArrayList<>(byKey.values());
    }

    public synchronized int size() {
        return byKey.size();
    }
}
