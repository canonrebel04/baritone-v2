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

/**
 * Budget & limits (plan A49): max run time, max distance from where the goal started, minimum Y and allowed
 * dimensions. Zero / sentinel values mean "no limit".
 */
public final class BudgetGuard {
    public static final int NO_MIN_Y = -9999;

    public static final class Limits {
        public int maxMinutes;
        public int maxDistance;
        public int minY = NO_MIN_Y;
        public boolean allowNether = true;
        public boolean allowEnd = true;
    }

    private BudgetGuard() {
    }

    /** @return why the run must stop, or null. */
    public static String check(Limits l, double elapsedSeconds, double horizontalDistance, double y, String dimension) {
        if (l.maxMinutes > 0 && elapsedSeconds > l.maxMinutes * 60.0) return "time budget of " + l.maxMinutes + " min used up";
        if (l.maxDistance > 0 && horizontalDistance > l.maxDistance) return "more than " + l.maxDistance + " blocks from the start";
        if (l.minY != NO_MIN_Y && y < l.minY - 0.5) return "below the minimum Y " + l.minY;
        if (!dimensionAllowed(l, dimension)) return "not allowed in " + dimension;
        return null;
    }

    public static boolean dimensionAllowed(Limits l, String dimension) {
        if (dimension == null) return true;
        if (dimension.endsWith("the_nether")) return l.allowNether;
        if (dimension.endsWith("the_end")) return l.allowEnd;
        return true;
    }
}
