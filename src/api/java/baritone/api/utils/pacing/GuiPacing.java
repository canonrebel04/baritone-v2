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

package baritone.api.utils.pacing;

/**
 * GUI / inventory pacing model (plan S40/S41), shared by Get Item, Meteor regear and anything else that clicks
 * in containers. Click intervals = reaction base + deterministic jitter + cursor travel time (Fitts' law over the
 * slot grid distance), stretched by the server-lag factor (S50). Containers get a "read" delay after opening
 * (more items take longer to scan) and a short delay before closing.
 */
public final class GuiPacing {
    private GuiPacing() {
    }

    /** {base, jitter} ticks between clicks. */
    public static int[] base(Pace pace) {
        switch (pace) {
            case FAST:
                return new int[]{1, 0};
            case BALANCED:
                return new int[]{3, 1};
            default:
                return new int[]{5, 2};
        }
    }

    /** Grid distance (slots) between two container/inventory slot indices laid out {@code columns} wide. */
    public static double slotDistance(int from, int to, int columns) {
        if (from < 0 || to < 0 || columns <= 0) return 0;
        int dx = (from % columns) - (to % columns);
        int dy = (from / columns) - (to / columns);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Extra ticks the cursor needs to travel {@code distance} slots (Fitts: k * log2(1 + d)). */
    public static int travelTicks(Pace pace, double distance) {
        double k = pace == Pace.LEGIT ? 1.1 : pace == Pace.BALANCED ? 0.6 : 0;
        return (int) Math.round(k * Math.log(1 + Math.max(0, distance)) / Math.log(2));
    }

    /** Ticks before click number {@code n}, moving the cursor {@code distance} slots. Always at least 1. */
    public static int clickInterval(Pace pace, double distance, long n, double lagFactor) {
        int[] bj = base(pace);
        int t = bj[0] + Jitter.of(n, 17, bj[1]) + travelTicks(pace, distance);
        return Math.max(1, (int) Math.round(Math.max(1, t) * Math.max(1.0, lagFactor)));
    }

    /** Ticks to "read" a freshly opened container holding {@code stacks} stacks before the first click. */
    public static int openReadDelay(Pace pace, int stacks, double lagFactor) {
        int t;
        switch (pace) {
            case FAST:
                t = 1;
                break;
            case BALANCED:
                t = 3 + Math.min(2, stacks / 18);
                break;
            default:
                t = 6 + Math.min(4, stacks / 9);
        }
        return (int) Math.round(t * Math.max(1.0, lagFactor));
    }

    /** Ticks between the last click and closing the screen. */
    public static int closeDelay(Pace pace, double lagFactor) {
        int t = pace == Pace.FAST ? 0 : pace == Pace.BALANCED ? 2 : 3;
        return (int) Math.round(t * Math.max(1.0, lagFactor));
    }
}
