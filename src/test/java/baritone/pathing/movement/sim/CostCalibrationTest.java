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

package baritone.pathing.movement.sim;

import baritone.api.pathing.movement.ActionCosts;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Enhancement #40: Cost calibration test harness comparing Baritone's planned {@link ActionCosts}
 * against simulated vanilla physics ticks from {@link MovementSimulator}.
 */
public class CostCalibrationTest {

    @Test
    public void testWalkAndSprintCostsMatchSimulatorSteadyState() {
        MovementSimulator.SimConfig vanilla = MovementSimulator.SimConfig.vanillaDefault();
        // Measure steady-state ticks per block over 10 blocks (after initial acceleration)
        double walk10 = MovementSimulator.simulateFlatTravelTicks(10.0, 0.215, false, false, vanilla) / 10.0;
        double sprint10 = MovementSimulator.simulateFlatTravelTicks(10.0, 0.28, true, false, vanilla) / 10.0;
        double sneak10 = MovementSimulator.simulateFlatTravelTicks(5.0, 0.065, false, true, vanilla) / 5.0;

        assertEquals("Walk 1-block cost vs simulator", ActionCosts.WALK_ONE_BLOCK_COST, walk10, 0.6);
        assertEquals("Sprint 1-block cost vs simulator", ActionCosts.SPRINT_ONE_BLOCK_COST, sprint10, 0.5);
        assertEquals("Sneak 1-block cost vs simulator", ActionCosts.SNEAK_ONE_BLOCK_COST, sneak10, 1.5);
    }

    @Test
    public void testParkourCostsMonotonicWithGap() {
        MovementSimulator.SimConfig vanilla = MovementSimulator.SimConfig.vanillaDefault();
        double cost2 = MovementSimulator.simulatedParkourCost(2.0, 0, true, vanilla);
        double cost3 = MovementSimulator.simulatedParkourCost(3.0, 0, true, vanilla);
        double cost4 = MovementSimulator.simulatedParkourCost(4.0, 0, true, vanilla);

        assertTrue("2-gap parkour cost reasonable: " + cost2, cost2 > 8.0 && cost2 < 25.0);
        assertTrue("3-gap parkour cost reasonable: " + cost3, cost3 > 8.0 && cost3 < 25.0);
        assertTrue("4-gap parkour cost >= 3-gap: " + cost4, cost4 >= cost3);
    }

    @Test
    public void testAscendJumpCostVsSimulator() {
        MovementSimulator.SimConfig vanilla = MovementSimulator.SimConfig.vanillaDefault();
        MovementSimulator.JumpResult ascend = MovementSimulator.simulateRunAndJump(2, 1.0, false, vanilla);
        assertTrue("Ascend +1 jump must land", ascend.landed);
        // Ascending +1 takes ~9-11 air ticks
        assertTrue("Ascend air ticks reasonable: " + ascend.ticks, ascend.ticks >= 7 && ascend.ticks <= 13);
    }
}
