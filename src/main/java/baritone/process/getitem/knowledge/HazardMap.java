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
 * Hazard map (plan A20): lava, spawners, mob-dense spots and void edges seen nearby, with a distance-weighted
 * penalty used when picking veins / containers / station spots and by the safety monitor.
 */
public final class HazardMap {
    public enum Type { LAVA, SPAWNER, MOBS, VOID }

    public static final int MAX = 512;

    public static final class Hazard {
        public Type type;
        public int x, y, z;
        public String dim;
        public double radius;
        public double weight;
        public long seen;
    }

    private final List<Hazard> hazards = new ArrayList<>();

    public synchronized void add(Type type, int x, int y, int z, String dim, double radius, double weight, long now) {
        for (Hazard h : hazards) {
            if (h.type == type && Objects.equals(h.dim, dim) && Math.abs(h.x - x) <= 2 && Math.abs(h.y - y) <= 2 && Math.abs(h.z - z) <= 2) {
                h.seen = now;
                h.weight = Math.max(h.weight, weight);
                return;
            }
        }
        Hazard h = new Hazard();
        h.type = type;
        h.x = x;
        h.y = y;
        h.z = z;
        h.dim = dim;
        h.radius = radius;
        h.weight = weight;
        h.seen = now;
        hazards.add(h);
        while (hazards.size() > MAX) hazards.remove(0);
    }

    /** Mob hotspots fade: drop MOBS entries older than {@code maxAge}. */
    public synchronized void expire(long now, long maxAge) {
        hazards.removeIf(h -> h.type == Type.MOBS && now - h.seen > maxAge);
    }

    /** Penalty at a point: sum of weight * (1 - d / radius) over hazards in range. */
    public synchronized double penalty(int x, int y, int z, String dim) {
        double p = 0;
        for (Hazard h : hazards) {
            if (!Objects.equals(h.dim, dim)) continue;
            double d = Math.sqrt((double) (h.x - x) * (h.x - x) + (double) (h.y - y) * (h.y - y) + (double) (h.z - z) * (h.z - z));
            if (d < h.radius) p += h.weight * (1 - d / h.radius);
        }
        return p;
    }

    public synchronized List<Hazard> near(int x, int y, int z, String dim, double r) {
        List<Hazard> out = new ArrayList<>();
        for (Hazard h : hazards) {
            if (Objects.equals(h.dim, dim) && Math.abs(h.x - x) <= r && Math.abs(h.y - y) <= r && Math.abs(h.z - z) <= r) out.add(h);
        }
        return out;
    }

    public synchronized List<Hazard> all() {
        return new ArrayList<>(hazards);
    }

    public synchronized void replaceAll(Collection<Hazard> hs) {
        hazards.clear();
        hazards.addAll(hs);
    }
}
