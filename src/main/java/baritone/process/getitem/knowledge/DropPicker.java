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

/** Dropped-item tracking (plan A16): which needed item entity to walk over next. */
public final class DropPicker {
    public static final class Drop {
        public final String item;
        public final double x, y, z;
        public final int count;
        public final int age;

        public Drop(String item, double x, double y, double z, int count, int age) {
            this.item = item;
            this.x = x;
            this.y = y;
            this.z = z;
            this.count = count;
            this.age = age;
        }
    }

    private DropPicker() {
    }

    /** Nearest needed drop within {@code radius} and {@code maxDy} blocks vertically; drops about to despawn skipped. */
    public static Drop pick(List<Drop> drops, Set<String> needed, double px, double py, double pz, double radius, double maxDy) {
        Drop best = null;
        double bestD = Double.MAX_VALUE;
        for (Drop d : drops) {
            if (!needed.contains(d.item) || Math.abs(d.y - py) > maxDy || d.age > 5800) continue;
            double dist = Math.sqrt((d.x - px) * (d.x - px) + (d.y - py) * (d.y - py) + (d.z - pz) * (d.z - pz));
            if (dist > radius) continue;
            double score = dist - Math.min(8, d.count) * 0.25;
            if (score < bestD) {
                bestD = score;
                best = d;
            }
        }
        return best;
    }
}
