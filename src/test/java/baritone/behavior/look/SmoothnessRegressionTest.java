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

import baritone.api.utils.smooth.SmoothnessMetrics;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Plan S100: smoothness regression harness. Drives the look controller through fixed scenarios (big turn,
 * target switch, tracking a strafing target) and fails when angular jerk, velocity spikes or snaps exceed
 * thresholds. Thresholds sit ~1.5x above today's measured maxima so regressions show up in CI.
 */
public class SmoothnessRegressionTest {
    static final boolean PRINT = Boolean.getBoolean("smooth.print");

    static SmoothnessMetrics run(LookProfile profile, long seed, java.util.function.IntFunction<double[]> target, int ticks) {
        SaccadeAimController c = new SaccadeAimController(seed);
        c.reset(0, 0);
        SmoothnessMetrics m = new SmoothnessMetrics();
        for (int t = 0; t < ticks; t++) {
            double[] goal = target.apply(t);
            c.step(goal[0], goal[1], profile, 95.0, false);
            m.sample(c.yaw(), c.pitch(), 0, 0);
        }
        if (PRINT) System.out.println(profile + " " + m);
        return m;
    }

    static double[] limits(LookProfile p) {
        // {max deg/tick, max accel, max jerk}
        return LIMITS[p.ordinal()];
    }

    static final double[][] LIMITS = new double[LookProfile.values().length][];

    static {
        // measured 2026-11 (seeds 1-5): LEGIT 36.2/13.8/9.2, BALANCED 34.8/13.1/8.4, FAST 50.1/18.7/6.5
        LIMITS[LookProfile.LEGIT_SMOOTH.ordinal()] = new double[]{55, 21, 14};
        LIMITS[LookProfile.BALANCED.ordinal()] = new double[]{52, 20, 13};
        LIMITS[LookProfile.FAST.ordinal()] = new double[]{75, 28, 10};
    }

    @Test
    public void bigTurnHasNoSnapAndBoundedJerk() {
        for (LookProfile p : LookProfile.values()) {
            for (long seed = 1; seed <= 5; seed++) {
                SmoothnessMetrics m = run(p, seed, t -> new double[]{150, 25}, 80);
                double[] l = limits(p);
                assertTrue(p + " " + m, m.maxVelocity() <= l[0]);
                assertTrue(p + " " + m, m.maxAcceleration() <= l[1]);
                assertTrue(p + " " + m, m.maxJerk() <= l[2]);
            }
        }
    }

    @Test
    public void legitSmoothIsGentlerThanTheLimits() {
        for (long seed = 1; seed <= 5; seed++) {
            SmoothnessMetrics m = run(LookProfile.LEGIT_SMOOTH, seed, t -> new double[]{120, 10}, 80);
            assertEquals(m.toString(), 0, m.snapThreshold(60).snaps());
            assertTrue(m.toString(), m.maxVelocity() < LEGIT_MAX_VEL);
            assertTrue(m.toString(), m.maxJerk() < LEGIT_MAX_JERK);
        }
    }

    static final double LEGIT_MAX_VEL = 55;
    static final double LEGIT_MAX_JERK = 14;

    @Test
    public void targetSwitchAndTrackingStaySmooth() {
        for (long seed = 1; seed <= 5; seed++) {
            SmoothnessMetrics sw = run(LookProfile.LEGIT_SMOOTH, seed, t -> t < 30 ? new double[]{40, 0} : new double[]{-60, 15}, 90);
            assertTrue(sw.toString(), sw.maxJerk() < LEGIT_MAX_JERK);
            SmoothnessMetrics tr = run(LookProfile.LEGIT_SMOOTH, seed, t -> new double[]{30 * Math.sin(t / 8.0), 5 * Math.cos(t / 11.0)}, 120);
            assertTrue(tr.toString(), tr.maxVelocity() < LEGIT_MAX_VEL);
        }
    }

    @Test
    public void metricsCountFlipsAndSnaps() {
        SmoothnessMetrics m = new SmoothnessMetrics();
        int[] inputs = {0, 1, 0, 1, 1, 1, 1, 1, 0};
        for (int i = 0; i < inputs.length; i++) m.sample(i == 5 ? 90 : 0, 0, i * 0.1, inputs[i]);
        assertEquals(2, m.inputFlips());
        assertEquals(2, m.snaps()); // 0->90 and 90->0
        assertEquals(90, m.maxVelocity(), 1e-9);
        assertEquals(0.1, m.maxSpeedJump(), 1e-9);
        assertEquals(-170, SmoothnessMetrics.wrap(190), 1e-9);
    }
}
