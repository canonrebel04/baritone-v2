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
 * Hotbar swap model (plan S39): a human scrolls through the slots between the current and the wanted one
 * (shortest way round), or presses a number key. LEGIT scrolls one slot per tick for short hops and uses the
 * number key for long ones; BALANCED moves two slots per tick; FAST jumps.
 */
public final class HotbarScroll {
    private HotbarScroll() {
    }

    /** Signed shortest step count from {@code from} to {@code to} on the 9-slot ring. */
    public static int delta(int from, int to) {
        int d = Math.floorMod(to - from, 9);
        return d > 4 ? d - 9 : d;
    }

    /** The slot to select this tick when heading from {@code current} to {@code target}. */
    public static int next(int current, int target, Pace pace) {
        if (current == target || target < 0 || target > 8) return target < 0 || target > 8 ? current : target;
        int d = delta(current, target);
        switch (pace) {
            case FAST:
                return target;
            case BALANCED:
                return Math.abs(d) <= 2 ? target : Math.floorMod(current + Integer.signum(d) * 2, 9);
            default:
                if (Math.abs(d) >= 4) return target; // number key: nobody scrolls 4 slots
                return Math.floorMod(current + Integer.signum(d), 9);
        }
    }
}
