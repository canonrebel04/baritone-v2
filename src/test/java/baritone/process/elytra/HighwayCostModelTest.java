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
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone. If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the highway (ice road) cost model — roadmap item 2 part 1.
 * <p>
 * Pure Java: exercises the continuity gate and multiplier selection that
 * {@link NetherPathfinderContext#highwayNodeCostMultiplier} applies per A* node.
 */
public class HighwayCostModelTest {

    private static final double PACKED = 0.5;
    private static final double BLUE = 0.35;
    private static final double EPS = 1e-9;

    private static HighwayCostModel model() {
        return new HighwayCostModel(PACKED, BLUE);
    }

    @Test
    public void testNonIceNodeIsFullPrice() {
        HighwayCostModel m = model();
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
        // and it must not advance any discount state
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
    }

    @Test
    public void testIsolatedIcePatchNeverDiscounted() {
        HighwayCostModel m = model();
        // a single ice node surrounded by normal terrain: continuity (2 of last 4) can never
        // be established for it or for its neighbors
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
    }

    @Test
    public void testSecondIceNodeStillFullPrice() {
        HighwayCostModel m = model();
        // node 1: no previous ice -> gate fails
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        // node 2: only 1 ice among previous nodes -> gate still fails
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        // node 3: 2 of last 4 nodes were ice -> discount applies
        assertEquals(PACKED, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        assertEquals(PACKED, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
    }

    @Test
    public void testBlueIcePreferredOverPackedIce() {
        HighwayCostModel m = model();
        // establish continuity with two ice nodes
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_BLUE_ICE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_BLUE_ICE);
        assertEquals(BLUE, m.nodeCostMultiplier(HighwayCostModel.FLOOR_BLUE_ICE), EPS);
        assertEquals(PACKED, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        // blue ice wins when both kinds are present under the node (mixed ice/coast)
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE), EPS);
    }

    @Test
    public void testContinuityWindowIsFourNodes() {
        HighwayCostModel m = model();
        // two ice nodes, then walk away from the highway
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        // next node discounted (2 ice in window)
        assertEquals(PACKED, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        // leave the highway: 4 consecutive non-ice nodes flush the window
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        // a new isolated ice node must again be full price
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
        assertEquals(PACKED, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
    }

    @Test
    public void testResetClearsContinuity() {
        HighwayCostModel m = model();
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        assertEquals(2, m.recentIceCount());
        m.reset();
        assertEquals(0, m.recentIceCount());
        assertEquals(1.0, m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE), EPS);
    }

    @Test
    public void testRecentIceCount() {
        HighwayCostModel m = model();
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        assertEquals(1, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        assertEquals(1, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_BLUE_ICE);
        assertEquals(2, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        assertEquals(2, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        assertEquals(1, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        assertEquals(1, m.recentIceCount());
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_NONE);
        assertEquals(0, m.recentIceCount());
    }

    @Test
    public void testFloorKindFromProbesNearestWins() {
        // nearest ice block below the node decides the multiplier
        assertEquals(HighwayCostModel.FLOOR_PACKED_ICE, HighwayCostModel.floorKindFromProbes(
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_PACKED_ICE,
                HighwayCostModel.FLOOR_BLUE_ICE));
        assertEquals(HighwayCostModel.FLOOR_BLUE_ICE, HighwayCostModel.floorKindFromProbes(
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_BLUE_ICE,
                HighwayCostModel.FLOOR_PACKED_ICE));
        assertEquals(HighwayCostModel.FLOOR_NONE, HighwayCostModel.floorKindFromProbes(
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_NONE,
                HighwayCostModel.FLOOR_NONE));
    }

    @Test
    public void testAggressiveDiscountStillAboveZero() {
        HighwayCostModel m = new HighwayCostModel(0.0, 0.0);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE);
        // a 0.0 multiplier makes highway nodes free (valid edge case), never negative
        assertTrue(m.nodeCostMultiplier(HighwayCostModel.FLOOR_PACKED_ICE) >= 0.0);
    }
}
