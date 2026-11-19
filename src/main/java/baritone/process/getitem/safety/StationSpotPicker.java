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

package baritone.process.getitem.safety;

import java.util.*;

/**
 * Station auto-placement spot (plan A32): a free 2-high spot on solid ground next to the player that isn't in a
 * hazard, isn't the block the player is walking into and is visible; nearer and off the walking line is better.
 */
public final class StationSpotPicker {
    /** World probe for the candidate cell (feet level). */
    public interface Probe {
        boolean free(int x, int y, int z);

        boolean solid(int x, int y, int z);

        double hazard(int x, int y, int z);
    }

    private StationSpotPicker() {
    }

    /** Best spot {x,y,z} around {@code feet}, or null. {@code facing} = unit {dx,dz} the player looks/walks. */
    public static int[] pick(int fx, int fy, int fz, int[] facing, Probe p) {
        int[] best = null;
        double bestScore = -1e9;
        for (int ring = 1; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy = 0; dy >= -1; dy--) {
                        int x = fx + dx, y = fy + dy, z = fz + dz;
                        if (!p.free(x, y, z) || !p.free(x, y + 1, z) || !p.solid(x, y - 1, z)) continue;
                        double s = -ring * 1.0 - Math.abs(dy) * 0.5 - p.hazard(x, y, z) * 3;
                        if (facing != null && dx == facing[0] && dz == facing[1]) s -= 2; // don't block the way ahead
                        if (Math.abs(dx) + Math.abs(dz) == 1) s += 0.3; // orthogonal: easiest to look at
                        if (s > bestScore) {
                            bestScore = s;
                            best = new int[]{x, y, z};
                        }
                    }
                }
            }
        }
        return best;
    }
}
