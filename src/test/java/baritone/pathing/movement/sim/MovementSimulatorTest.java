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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Headless JUnit tests verifying vanilla Minecraft player physics in {@link MovementSimulator}
 * and {@link PlayerPhysics}, plus {@link MovementTelemetry}.
 */
public class MovementSimulatorTest {

    @Test
    public void testVanillaJumpHeight() {
        double peak = PlayerPhysics.maxJumpHeight(0);
        // Vanilla jump height is ~1.2522 blocks
        assertTrue("Vanilla jump peak should be ~1.252, got " + peak, peak > 1.24 && peak < 1.27);

        double peakBoost1 = PlayerPhysics.maxJumpHeight(1);
        assertTrue("Jump Boost I peak should be > 1.8 blocks, got " + peakBoost1, peakBoost1 > 1.8 && peakBoost1 < 2.05);

        double peakBoost2 = PlayerPhysics.maxJumpHeight(2);
        assertTrue("Jump Boost II peak should be > 2.4 blocks, got " + peakBoost2, peakBoost2 > 2.4 && peakBoost2 < 2.7);
    }

    @Test
    public void testVanillaFourBlockSprintJumpReachable() {
        MovementSimulator.SimConfig vanilla = MovementSimulator.SimConfig.vanillaDefault();
        double span = MovementSimulator.maxReachableJumpSpan(6, 0.0, true, vanilla);
        // Vanilla sprint jump with runup clears a 4-block gap (center-to-center ~4.0-4.4)
        assertTrue("4-block sprint jump should be reachable, got span=" + span, span >= 4.0);
        assertTrue("5-block flat jump without speed/ice should NOT be reachable, got span=" + span, span < 5.0);
    }

    @Test
    public void testSpeedAndIceExtendJumpSpan() {
        MovementSimulator.SimConfig speed2 = new MovementSimulator.SimConfig(
                PlayerPhysics.BASE_MOVEMENT_SPEED, 2, 0, 0,
                PlayerPhysics.BASE_GROUND_SLIPPERINESS, 1.0, false, false);
        double spanSpeed2 = MovementSimulator.maxReachableJumpSpan(6, 0.0, true, speed2);
        assertTrue("Speed II sprint jump should exceed 4.8 blocks, got " + spanSpeed2, spanSpeed2 > 4.8);

        MovementSimulator.SimConfig ice = new MovementSimulator.SimConfig(
                PlayerPhysics.BASE_MOVEMENT_SPEED, 0, 0, 0,
                PlayerPhysics.ICE_SLIPPERINESS, 1.0, false, false);
        double spanIce = MovementSimulator.maxReachableJumpSpan(10, 0.0, true, ice);
        assertTrue("Ice sprint jump should exceed vanilla flat jump", spanIce > MovementSimulator.maxReachableJumpSpan(6, 0.0, true, MovementSimulator.SimConfig.vanillaDefault()));
    }

    @Test
    public void testStoppingDistanceIncreasesOnIce() {
        double groundStop = MovementSimulator.predictStoppingDistance(0.0, 0.28, true, PlayerPhysics.BASE_GROUND_SLIPPERINESS);
        double iceStop = MovementSimulator.predictStoppingDistance(0.0, 0.28, true, PlayerPhysics.ICE_SLIPPERINESS);
        assertTrue("Ground stopping distance should be small (<0.8), got " + groundStop, groundStop > 0.2 && groundStop < 0.8);
        assertTrue("Ice stopping distance should be much larger than ground, got " + iceStop, iceStop > groundStop * 3.0);
    }

    @Test
    public void testWillLeaveBlockNextTick() {
        // Player at x=0.85 moving +X at 0.20 -> nextX=1.05 -> forwardNext=0.55 >= 0.45
        assertTrue(MovementSimulator.willLeaveBlockNextTick(0.85, 0.5, 0.20, 0.0, 0, 0, 1, 0, 0.05));
        // Player at x=0.50 moving +X at 0.20 -> nextX=0.70 -> forwardNext=0.20 < 0.45
        assertFalse(MovementSimulator.willLeaveBlockNextTick(0.50, 0.5, 0.20, 0.0, 0, 0, 1, 0, 0.05));
    }

    @Test
    public void testSurfaceCostMultipliers() {
        double normal = PlayerPhysics.surfaceCostMultiplier(PlayerPhysics.BASE_GROUND_SLIPPERINESS, 1.0, false, false);
        double soulSand = PlayerPhysics.surfaceCostMultiplier(PlayerPhysics.BASE_GROUND_SLIPPERINESS, PlayerPhysics.SOUL_SAND_SPEED_FACTOR, false, false);
        double cobweb = PlayerPhysics.surfaceCostMultiplier(PlayerPhysics.BASE_GROUND_SLIPPERINESS, 1.0, true, false);
        double blueIce = PlayerPhysics.surfaceCostMultiplier(PlayerPhysics.BLUE_ICE_SLIPPERINESS, 1.0, false, false);

        assertEquals(1.0, normal, 1e-6);
        assertTrue("Soul sand should cost more than normal", soulSand > 2.0);
        assertTrue("Cobweb should cost much more than normal", cobweb > 4.0);
        assertTrue("Blue ice should be faster than normal", blueIce < 1.0);
    }

    @Test
    public void testMovementTelemetryFailureMemoryAndAdaptiveTuning() {
        MovementTelemetry.reset();
        assertEquals(0.0, MovementTelemetry.getEdgeFailurePenalty(1, 64, 1, 5, 64, 1, "PARKOUR_EAST"), 1e-6);

        MovementTelemetry.recordFailure(1, 64, 1, 5, 64, 1, "PARKOUR_EAST");
        double penalty1 = MovementTelemetry.getEdgeFailurePenalty(1, 64, 1, 5, 64, 1, "PARKOUR_EAST");
        assertTrue("Recorded failure should yield positive penalty", penalty1 > 5.0);

        // Adaptive multiplier stays 1.0 until >= 5 samples
        assertEquals(1.0, MovementTelemetry.getAdaptiveCostMultiplier("PARKOUR_EAST"), 1e-6);
        for (int i = 0; i < 6; i++) {
            MovementTelemetry.recordFailure(1, 64, 1, 5, 64, 1, "PARKOUR_EAST");
        }
        double mult = MovementTelemetry.getAdaptiveCostMultiplier("PARKOUR_EAST");
        assertTrue("High failure rate should raise adaptive cost multiplier, got " + mult, mult > 1.3 && mult <= 1.8);
        MovementTelemetry.reset();
    }

    @Test
    public void testWillStepOffEdgeNextTickAccountsForHitboxOverhang() {
        // Player at x=0.95 moving +X at 0.20 + accel 0.12 -> nextX=1.27 -> rel=+0.77 >= 0.80 - 0.04
        assertTrue(MovementSimulator.willStepOffEdgeNextTick(0.95, 0.5, 0.20, 0.0, 0, 0, 1, 0, 0.12, 0.04));
        // Player at x=0.60 moving +X at 0.20 + accel 0.12 -> nextX=0.92 -> rel=+0.42 < 0.76 (still supported)
        assertFalse(MovementSimulator.willStepOffEdgeNextTick(0.60, 0.5, 0.20, 0.0, 0, 0, 1, 0, 0.12, 0.04));
    }

    @Test
    public void testShouldBrakeBeforeTarget() {
        assertTrue(MovementSimulator.shouldBrakeBeforeTarget(0.3, 0.28, true, PlayerPhysics.BASE_GROUND_SLIPPERINESS, 0.05));
        assertFalse(MovementSimulator.shouldBrakeBeforeTarget(2.0, 0.28, true, PlayerPhysics.BASE_GROUND_SLIPPERINESS, 0.05));
    }

    @Test
    public void testHeadHitterCeilingWorksAtNonZeroY() {
        MovementSimulator.SimConfig ceiling = new MovementSimulator.SimConfig(
                PlayerPhysics.BASE_MOVEMENT_SPEED, 0, 0, 0,
                PlayerPhysics.BASE_GROUND_SLIPPERINESS, 1.0, false, true);
        MovementSimulator.SimState s = new MovementSimulator.SimState(0.0, 64.0, 0.0, 0.0, 0.0, 0.25, true, true);
        MovementSimulator.step(s, MovementSimulator.SimInput.forward(0.0f, true, true), ceiling);
        assertEquals(64.2, s.y, 1e-6);
        assertTrue(s.vy <= 0.0);
    }
}
