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
 * Exploration when a resource isn't known nearby (plan A15): a square spiral of waypoints around the start at the
 * resource's best Y (A14). Surface resources explore at surface height (y = Integer.MIN_VALUE means "any Y").
 */
public final class ExplorationPlanner {
    private ExplorationPlanner() {
    }

    /** Waypoint {@code index} (0-based) of a square spiral with {@code step} spacing. */
    public static int[] spiral(int startX, int startZ, int step, int index) {
        int x = 0, z = 0, dx = 1, dz = 0, len = 1, walked = 0, turns = 0;
        for (int i = 0; i <= index; i++) {
            x += dx;
            z += dz;
            if (++walked == len) {
                walked = 0;
                int t = dx;
                dx = -dz;
                dz = t;
                if (++turns % 2 == 0) len++;
            }
        }
        return new int[]{startX + x * step, startZ + z * step};
    }

    /** Waypoint for the resource: {x, y, z}; y = Integer.MIN_VALUE for surface (XZ goal). */
    public static int[] waypoint(List<String> blocks, int startX, int startZ, int index, int step, int minY) {
        int[] xz = spiral(startX, startZ, step, index);
        BiomeHints.Hint h = null;
        for (String b : blocks) {
            h = BiomeHints.of(b);
            if (h != null) break;
        }
        int y = h != null && h.underground() ? Math.max(minY, h.bestY) : Integer.MIN_VALUE;
        return new int[]{xz[0], y, xz[1]};
    }
}
