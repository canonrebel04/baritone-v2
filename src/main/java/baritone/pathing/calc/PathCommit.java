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

package baritone.pathing.calc;

/**
 * S30 path-commit hysteresis: keep a still-valid committed route unless the
 * replacement is cheaper by a configured tick margin.
 */
public final class PathCommit {

    private PathCommit() {}

    public static boolean shouldKeepCommitted(double committedRemaining, double newCost, double marginTicks) {
        if (!Double.isFinite(committedRemaining) || !Double.isFinite(newCost) || committedRemaining < 0.0) {
            return false;
        }
        return committedRemaining <= newCost + Math.max(0.0, marginTicks);
    }
}
