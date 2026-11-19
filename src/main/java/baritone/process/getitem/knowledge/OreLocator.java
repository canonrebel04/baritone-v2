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
 * Ore/block locator (plan A13): clusters known positions (Baritone's cached world + loaded chunks) into veins and
 * scores them by size, distance, closeness to the ore's best Y (A14) and nearby hazards (A20).
 */
public final class OreLocator {
    public static final class Vein {
        public final int x, y, z;
        public final int size;
        public final double score;

        Vein(int x, int y, int z, int size, double score) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.score = score;
        }

        @Override
        public String toString() {
            return size + " at " + x + "," + y + "," + z;
        }
    }

    private OreLocator() {
    }

    /** Union-find clusters of positions within {@code link} blocks (Chebyshev) of each other. */
    public static List<List<int[]>> cluster(List<int[]> positions, int link) {
        int n = positions.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int[] a = positions.get(i), b = positions.get(j);
                if (Math.abs(a[0] - b[0]) <= link && Math.abs(a[1] - b[1]) <= link && Math.abs(a[2] - b[2]) <= link) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<int[]>> groups = new TreeMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(positions.get(i));
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    /** Veins best first. {@code hazards} may be null. */
    public static List<Vein> veins(String block, List<int[]> positions, double px, double py, double pz, HazardMap hazards, String dim) {
        List<int[]> capped = positions.size() > 400 ? positions.subList(0, 400) : positions;
        BiomeHints.Hint hint = BiomeHints.of(block);
        List<Vein> out = new ArrayList<>();
        for (List<int[]> c : cluster(capped, 2)) {
            double sx = 0, sy = 0, sz = 0;
            for (int[] p : c) {
                sx += p[0];
                sy += p[1];
                sz += p[2];
            }
            int cx = (int) Math.round(sx / c.size()), cy = (int) Math.round(sy / c.size()), cz = (int) Math.round(sz / c.size());
            double dist = Math.sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py) + (cz - pz) * (cz - pz));
            double s = c.size() * 6.0 - dist * 0.25;
            if (hint != null) s -= Math.abs(cy - hint.bestY) * 0.05;
            if (hazards != null) s -= hazards.penalty(cx, cy, cz, dim);
            out.add(new Vein(cx, cy, cz, c.size(), s));
        }
        out.sort(Comparator.comparingDouble((Vein v) -> -v.score).thenComparingInt(v -> v.x).thenComparingInt(v -> v.z));
        return out;
    }
}
