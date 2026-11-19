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

package baritone.behavior.look;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Headless tests for the PS1 smooth-look core (plan S1, S3, S4, S5, S6, S7, S8, S9, S13).
 */
public class SmoothLookCoreTest {

    private static final double MAX_TURN = 95.0;

    @Test
    public void minJerkRestToRestHitsEndpointsAndIsMonotonic() {
        MinJerkTrajectory t = new MinJerkTrajectory();
        t.plan(0, 0, 0, 90, 10);
        assertEquals(0.0, t.position(0), 1e-9);
        assertEquals(90.0, t.position(10), 1e-9);
        assertEquals(0.0, t.velocity(0), 1e-9);
        assertEquals(0.0, t.acceleration(0), 1e-9);
        double prev = -1;
        for (int i = 0; i <= 10; i++) {
            double p = t.position(i);
            assertTrue(p >= prev - 1e-9);
            prev = p;
        }
        assertEquals(MinJerkTrajectory.PEAK_VELOCITY_FACTOR * 90 / 10, t.velocity(5), 1e-9);
    }

    @Test
    public void minJerkStartsFromInitialVelocityAndAcceleration() {
        MinJerkTrajectory t = new MinJerkTrajectory();
        t.plan(10, 4, -1, 30, 8);
        assertEquals(10.0, t.position(0), 1e-9);
        assertEquals(4.0, t.velocity(0), 1e-9);
        assertEquals(-1.0, t.acceleration(0), 1e-9);
        assertEquals(30.0, t.position(7.999999), 1e-4);
        assertEquals(0.0, t.velocity(7.999999), 1e-4);
    }

    @Test
    public void minDurationForPeakRespectsCap() {
        double T = MinJerkTrajectory.minDurationForPeak(120, 20);
        MinJerkTrajectory t = new MinJerkTrajectory();
        t.plan(0, 0, 0, 120, T);
        assertTrue(t.velocity(T / 2) <= 20 + 1e-9);
    }

    @Test
    public void fittsDurationGrowsWithDistanceAndHasFloor() {
        LookProfile p = LookProfile.LEGIT_SMOOTH;
        assertEquals(2.0, p.fittsTicks(0), 1e-9);
        assertTrue(p.fittsTicks(90) > p.fittsTicks(30));
        assertTrue(p.fittsTicks(30) > p.fittsTicks(5));
        assertTrue(LookProfile.BALANCED.fittsTicks(90) < p.fittsTicks(90));
    }

    @Test
    public void profileParsingIsLenient() {
        assertEquals(LookProfile.LEGIT_SMOOTH, LookProfile.parse(null));
        assertEquals(LookProfile.LEGIT_SMOOTH, LookProfile.parse("legit-smooth"));
        assertEquals(LookProfile.LEGIT_SMOOTH, LookProfile.parse("nonsense"));
        assertEquals(LookProfile.BALANCED, LookProfile.parse(" balanced "));
        assertEquals(LookProfile.FAST, LookProfile.parse("Fast"));
        assertTrue(LookProfile.FAST.isLegacy());
        assertFalse(LookProfile.LEGIT_SMOOTH.isLegacy());
    }

    /** Runs a controller to a target, returning per-tick yaw positions. */
    private static double[] run(SaccadeAimController c, double ty, double tp, LookProfile p, int ticks, boolean precise) {
        double[] out = new double[ticks];
        for (int i = 0; i < ticks; i++) {
            c.step(ty, tp, p, MAX_TURN, precise);
            out[i] = c.yaw();
        }
        return out;
    }

    @Test
    public void largeTurnConvergesWithoutVelocityJumps() {
        SaccadeAimController c = new SaccadeAimController(42);
        c.reset(0, 0);
        double[] yaw = run(c, 120, 20, LookProfile.LEGIT_SMOOTH, 80, false);
        assertEquals(120.0, c.yaw(), 0.2);
        assertEquals(20.0, c.pitch(), 0.2);
        double prevVel = 0, prevPos = 0;
        double maxJump = 0;
        for (double p : yaw) {
            double v = p - prevPos;
            maxJump = Math.max(maxJump, Math.abs(v - prevVel));
            prevVel = v;
            prevPos = p;
        }
        assertTrue("velocity jump " + maxJump, maxJump <= SaccadeAimController.maxAcceleration(MAX_TURN) * 1.15);
    }

    @Test
    public void largeTurnTakesSeveralTicks() {
        SaccadeAimController c = new SaccadeAimController(1);
        c.reset(0, 0);
        c.step(90, 0, LookProfile.LEGIT_SMOOTH, MAX_TURN, false);
        assertTrue("no snap on the first tick", c.yaw() < 45);
        assertTrue(c.isSaccading());
    }

    @Test
    public void pitchIsSlowerThanYaw() {
        SaccadeAimController c = new SaccadeAimController(3);
        c.reset(0, 0);
        c.step(60, 60, LookProfile.LEGIT_SMOOTH, MAX_TURN, true);
        c.step(60, 60, LookProfile.LEGIT_SMOOTH, MAX_TURN, true);
        assertTrue(c.yaw() / 60.0 > c.pitch() / 60.0);
    }

    @Test
    public void retargetIsContinuous() {
        SaccadeAimController c = new SaccadeAimController(5);
        c.reset(0, 0);
        double prev = 0, prevVel = 0, maxJump = 0;
        for (int i = 0; i < 60; i++) {
            double target = i < 4 ? 100 : -40; // switch targets mid-saccade
            c.step(target, 0, LookProfile.LEGIT_SMOOTH, MAX_TURN, false);
            double v = c.yaw() - prev;
            if (i > 0) {
                maxJump = Math.max(maxJump, Math.abs(v - prevVel));
            }
            prevVel = v;
            prev = c.yaw();
        }
        assertEquals(-40.0, c.yaw(), 0.2);
        assertTrue("velocity jump " + maxJump, maxJump <= SaccadeAimController.maxAcceleration(MAX_TURN) * 1.15);
    }

    @Test
    public void smallTargetDriftIsTrackedSmoothly() {
        SaccadeAimController c = new SaccadeAimController(7);
        c.reset(0, 0);
        double target = 0;
        for (int i = 0; i < 100; i++) {
            target += 0.5;
            c.step(target, 0, LookProfile.LEGIT_SMOOTH, MAX_TURN, false);
        }
        assertEquals(target, c.yaw(), 3.0);
    }

    @Test
    public void settleNeverOvershootsFromRest() {
        SaccadeAimController c = new SaccadeAimController(9);
        c.reset(0, 0);
        double max = 0;
        for (int i = 0; i < 60; i++) {
            c.step(8, 0, LookProfile.LEGIT_SMOOTH, MAX_TURN, false); // below saccade threshold: settle only
            max = Math.max(max, c.yaw());
        }
        assertTrue(max <= 8.0 + 1e-9);
        assertEquals(8.0, c.yaw(), 1e-9);
    }

    @Test
    public void errorFractionIsDeterministicAndBounded() {
        SaccadeAimController a = new SaccadeAimController(11);
        SaccadeAimController b = new SaccadeAimController(11);
        LookProfile p = LookProfile.LEGIT_SMOOTH;
        for (int i = 0; i < 50; i++) {
            double e = a.errorFraction(i * 7.3, i - 25, p);
            assertEquals(e, b.errorFraction(i * 7.3, i - 25, p), 0.0);
            assertTrue(e >= p.minError && e <= p.maxError);
        }
    }

    @Test
    public void preciseAimLandsExactly() {
        SaccadeAimController c = new SaccadeAimController(13);
        c.reset(0, 0);
        run(c, 70, -30, LookProfile.LEGIT_SMOOTH, 60, true);
        assertEquals(70.0, c.yaw(), 1e-9);
        assertEquals(-30.0, c.pitch(), 1e-9);
    }

    @Test
    public void yawWrapsTheShortWay() {
        SaccadeAimController c = new SaccadeAimController(15);
        c.reset(170, 0);
        run(c, -170, 0, LookProfile.BALANCED, 60, true);
        assertEquals(190.0, c.yaw(), 1e-6);
    }

    @Test
    public void pitchStaysInRange() {
        SaccadeAimController c = new SaccadeAimController(17);
        c.reset(0, 80);
        for (int i = 0; i < 40; i++) {
            c.step(0, 120, LookProfile.LEGIT_SMOOTH, MAX_TURN, false);
            assertTrue(c.pitch() <= 90.0 && c.pitch() >= -90.0);
        }
    }

    @Test
    public void correlatedNoiseIsBoundedSmoothAndSeeded() {
        CorrelatedNoise n = new CorrelatedNoise(99);
        CorrelatedNoise m = new CorrelatedNoise(99);
        double prev = n.sample(0);
        for (int i = 1; i < 2000; i++) {
            double x = i * 0.04;
            double v = n.sample(x);
            assertTrue(v >= -1 && v <= 1);
            assertTrue("smooth", Math.abs(v - prev) < 0.2);
            assertEquals(v, m.sample(x), 0.0);
            prev = v;
        }
        assertEquals(0.0, n.sample(3), 1e-12); // zero at lattice points
    }

    @Test
    public void strideCouplingSpeedsUpNoisePhase() {
        MicroMotion still = new MicroMotion(1);
        MicroMotion walking = new MicroMotion(1);
        for (int i = 0; i < 20; i++) {
            still.tick(0);
            walking.tick(0.2);
        }
        assertTrue(walking.phase() > still.phase() * 2);
        assertTrue(Math.abs(still.idleYaw()) <= MicroMotion.IDLE_YAW_AMPLITUDE);
        assertTrue(Math.abs(still.noiseYaw(0.12)) <= 0.12);
    }

    @Test
    public void gazePitchAndDistanceAreClamped() {
        assertEquals(3.0, MicroMotion.gazeDistance(0), 1e-9);
        assertEquals(9.0, MicroMotion.gazeDistance(5), 1e-9);
        assertTrue(MicroMotion.gazeDistance(0.28) > MicroMotion.gazeDistance(0.21));
        double p = MicroMotion.gazePitch(65.62, 64, 4);
        assertTrue(p > 15 && p < 30);
        assertEquals(35.0, MicroMotion.gazePitch(100, 0, 1), 1e-9);
        assertEquals(-15.0, MicroMotion.gazePitch(0, 100, 1), 1e-9);
    }
}
