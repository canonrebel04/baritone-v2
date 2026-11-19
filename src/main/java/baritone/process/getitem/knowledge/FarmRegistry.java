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
 * Farm / animal pen registry (plan A18): clusters of crops and of passive animals seen nearby. Feeds the planner's
 * "seen blocks" now; full farming tasks are milestone M3 (A33-A42).
 */
public final class FarmRegistry {
    public static final int MAX = 128;
    public static final int MIN_CROPS = 6, MIN_ANIMALS = 3;

    public static final class Farm {
        public String type;
        public boolean animals;
        public int x, y, z;
        public String dim;
        public int size;
        /** Plants ready to harvest at the last scan (A39 growth-stage awareness). */
        public int ready;
        public long seen;
    }

    private final List<Farm> farms = new ArrayList<>();

    /**
     * Replace what was known about {@code type} within {@code radius} of the scan centre with the clusters found
     * in {@code points} ({x,y,z}).
     */
    public synchronized void update(String type, boolean animals, List<int[]> points, String dim, int cx, int cz, int radius, long now) {
        update(type, animals, points, null, dim, cx, cz, radius, now);
    }

    /** As above; {@code ripe} (same shape as points, may be null) are the positions ready to harvest. */
    public synchronized void update(String type, boolean animals, List<int[]> points, List<int[]> ripe, String dim, int cx, int cz, int radius, long now) {
        farms.removeIf(f -> f.type.equals(type) && Objects.equals(f.dim, dim) && Math.abs(f.x - cx) <= radius && Math.abs(f.z - cz) <= radius);
        int link = animals ? 6 : 2;
        int min = animals ? MIN_ANIMALS : MIN_CROPS;
        for (List<int[]> c : OreLocator.cluster(points, link)) {
            if (c.size() < min) continue;
            Farm f = new Farm();
            f.type = type;
            f.animals = animals;
            long sx = 0, sy = 0, sz = 0;
            for (int[] p : c) {
                sx += p[0];
                sy += p[1];
                sz += p[2];
            }
            f.x = (int) (sx / c.size());
            f.y = (int) (sy / c.size());
            f.z = (int) (sz / c.size());
            f.dim = dim;
            f.size = c.size();
            if (ripe != null) {
                Set<String> members = new HashSet<>();
                for (int[] p : c) members.add(p[0] + "," + p[1] + "," + p[2]);
                for (int[] p : ripe) if (members.contains(p[0] + "," + p[1] + "," + p[2])) f.ready++;
            }
            f.seen = now;
            farms.add(f);
        }
        while (farms.size() > MAX) farms.remove(0);
    }

    public synchronized List<Farm> all() {
        return new ArrayList<>(farms);
    }

    public synchronized void replaceAll(Collection<Farm> fs) {
        farms.clear();
        farms.addAll(fs);
    }

    public synchronized Set<String> types(String dim) {
        Set<String> out = new TreeSet<>();
        for (Farm f : farms) if (Objects.equals(f.dim, dim)) out.add(f.type);
        return out;
    }
}
