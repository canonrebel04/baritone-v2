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

package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementDiagonalTest {

    /**
     * Verifies that the signed projection formula used in MovementDiagonal:
     *   (playerCoord - midCoord) * dirStep >= -0.2
     * correctly detects whether the waypoint has been reached regardless of whether
     * moving positive (+1: East/South) or negative (-1: West/North).
     */
    @Test
    public void testWaypointSignedProjection() {
        int[] deltas = new int[]{-1, 1};

        for (int step : deltas) {
            double start = 10.5;
            double dest = start + step;
            double mid = start + 0.5 * step;

            // At start position (before crossing midpoint), reachedWaypoint must be false
            boolean atStart = (start - mid) * step >= -0.2;
            assertFalse("Start position must not trigger reachedWaypoint for step=" + step, atStart);

            // Slightly before the waypoint tolerance
            double justBefore = mid - 0.25 * step;
            boolean before = (justBefore - mid) * step >= -0.2;
            assertFalse("Position before tolerance must not trigger reachedWaypoint for step=" + step, before);

            // Within tolerance
            double inTolerance = mid - 0.15 * step;
            boolean reached = (inTolerance - mid) * step >= -0.2;
            assertTrue("Position within tolerance must trigger reachedWaypoint for step=" + step, reached);

            // Exactly at the waypoint
            boolean atMid = (mid - mid) * step >= -0.2;
            assertTrue("Midpoint must trigger reachedWaypoint for step=" + step, atMid);

            // Past the waypoint toward destination
            double pastMid = mid + 0.3 * step;
            boolean past = (pastMid - mid) * step >= -0.2;
            assertTrue("Position past midpoint must trigger reachedWaypoint for step=" + step, past);
        }
    }
}
