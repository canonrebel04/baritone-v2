/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HighwaySnapCalculatorTest {

    @Test
    public void snapsLongParallelFlightOntoAxis() {
        // Flying from (300, 1200) to (350, 12000) should snap onto +Z axis (x=0)
        List<HighwaySnapCalculator.Waypoint> route = HighwaySnapCalculator.planRoute(
                300, 1200, 350, 12000, HighwaySnapCalculator.DEFAULT_RING_ROADS);
        assertTrue("Expected multi-leg highway route, got " + route, route.size() >= 2);
        assertEquals(0, route.get(0).x());
        HighwaySnapCalculator.Waypoint last = route.get(route.size() - 1);
        assertEquals(350, last.x());
        assertEquals(12000, last.z());
    }

    @Test
    public void shortFlightGoesDirect() {
        List<HighwaySnapCalculator.Waypoint> route = HighwaySnapCalculator.planRoute(
                10, 20, 80, 90, HighwaySnapCalculator.DEFAULT_RING_ROADS);
        assertEquals(1, route.size());
        assertEquals(80, route.get(0).x());
        assertEquals(90, route.get(0).z());
    }
}
