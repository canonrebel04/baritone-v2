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
 * Live (not persisted) census of what's around the player, refreshed by the knowledge scan (M3): huntable mobs,
 * shearable sheep by colour, milkable cows and the nearest open water. Values are {count, nearest distance}.
 */
public final class Census {
    public final Map<String, double[]> mobs;
    public final Map<String, double[]> sheep;
    public final double[] cows;
    /** Nearest open water surface, -1 = none in range. */
    public final double waterDist;
    public final long time;

    public static final Census EMPTY = new Census(Collections.emptyMap(), Collections.emptyMap(), null, -1, 0);

    public Census(Map<String, double[]> mobs, Map<String, double[]> sheep, double[] cows, double waterDist, long time) {
        this.mobs = Collections.unmodifiableMap(new TreeMap<>(mobs));
        this.sheep = Collections.unmodifiableMap(new TreeMap<>(sheep));
        this.cows = cows;
        this.waterDist = waterDist;
        this.time = time;
    }

    /** Add one sighting at {@code dist} to a {count, nearest} table. */
    public static void see(Map<String, double[]> table, String key, double dist) {
        double[] v = table.computeIfAbsent(key, k -> new double[]{0, Double.MAX_VALUE});
        v[0]++;
        v[1] = Math.min(v[1], dist);
    }
}
