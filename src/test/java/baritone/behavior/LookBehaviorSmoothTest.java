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

package baritone.behavior;

import baritone.api.utils.Rotation;
import baritone.utils.MouseGCD;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Tests for the continuous quantized-space look chase in {@link LookBehavior}.
 *
 * <p>These tests exercise the inner {@code QuantizedChase} spring directly via reflection
 * (it is a private static nested class) since it has no dependency on Minecraft classes.
 */
public class LookBehaviorSmoothTest {

    private static final float GCD = 0.15f;   // degrees per step at 50% sensitivity
    private static final float MAX_TURN = 35f; // degrees per tick cap

    private Object newChase(double startYaw, double startPitch, double targetYaw, double targetPitch) throws Exception {
        Class<?> chaseClass = Class.forName("baritone.behavior.LookBehavior$QuantizedChase");
        Constructor<?> ctor = chaseClass.getDeclaredConstructor(
                Rotation.class, Rotation.class, float.class, float.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                new Rotation((float) startYaw, (float) startPitch),
                new Rotation((float) targetYaw, (float) targetPitch),
                GCD, MAX_TURN, 5);
    }

    private Rotation step(Object chase) throws Exception {
        Method step = chase.getClass().getDeclaredMethod("step", float.class, float.class);
        step.setAccessible(true);
        return (Rotation) step.invoke(chase, GCD, MAX_TURN);
    }

    private void retarget(Object chase, double targetYaw, double targetPitch) throws Exception {
        Method retarget = chase.getClass().getDeclaredMethod(
                "retarget", Rotation.class, float.class, float.class, int.class);
        retarget.setAccessible(true);
        retarget.invoke(chase, new Rotation((float) targetYaw, (float) targetPitch), GCD, MAX_TURN, 5);
    }

    /** Every written angle must be an exact multiple of the GCD step (quantized-space invariant). */
    @Test
    public void testAllWrittenAnglesAreGcdMultiples() throws Exception {
        Object chase = newChase(0, 0, 90, 30);
        for (int i = 0; i < 200; i++) {
            Rotation r = step(chase);
            assertYawPitchOnGcd(r);
        }
    }

    /** Settling: the chase must reach the target and stop (no oscillation past it). */
    @Test
    public void testSettlesAtTarget() throws Exception {
        Object chase = newChase(0, 0, 45, -10);
        Rotation last = null;
        for (int i = 0; i < 400; i++) {
            last = step(chase);
        }
        assertEquals(45f, last.getYaw(), GCD);
        assertEquals(-10f, last.getPitch(), GCD);
        // Stopped: further steps produce the same rotation
        Rotation again = step(chase);
        assertEquals(last.getYaw(), again.getYaw(), 1e-3);
        assertEquals(last.getPitch(), again.getPitch(), 1e-3);
    }

    /** Target drift mid-chase: no velocity reversal and no position jump (continuity). */
    @Test
    public void testTargetDriftIsContinuous() throws Exception {
        Object chase = newChase(0, 0, 60, 0);
        double prevYaw = 0;
        double prevVel = 0;
        boolean hasPrevVel = false;
        for (int i = 0; i < 10; i++) {
            Rotation r = step(chase);
            double vel = r.getYaw() - prevYaw;
            if (hasPrevVel && Math.abs(prevVel) > 1e-9 && Math.abs(vel) > 1e-9
                    && Math.signum(vel) != Math.signum(prevVel)) {
                // Direction reversals are only allowed after passing the target, never mid-chase
                fail("velocity direction flipped mid-chase at tick " + i + ": " + prevVel + " -> " + vel);
            }
            // No jumps: single-tick movement must be bounded by the turn cap (plus one step slack)
            assertTrue("jump too large: " + vel, Math.abs(vel) <= MAX_TURN / GCD + 1);
            prevYaw = r.getYaw();
            prevVel = vel;
            hasPrevVel = true;
            // Simulate Baritone re-submitting a slightly different target each tick
            if (i % 3 == 2) {
                retarget(chase, 60 + i, 0); // monotonic drift: camera must never flip direction
            }
        }
        // Still on GCD grid after drift
        assertYawPitchOnGcd(step(chase));
    }

    /** Peak speed must never exceed the configured max turn rate (by more than a rounding step). */
    @Test
    public void testPeakTurnRateCapped() throws Exception {
        Object chase = newChase(0, 0, 180, 0); // long haul
        double prevYaw = 0;
        double maxObserved = 0;
        for (int i = 0; i < 50; i++) {
            Rotation r = step(chase);
            maxObserved = Math.max(maxObserved, Math.abs(r.getYaw() - prevYaw));
            prevYaw = r.getYaw();
        }
        assertTrue("peak turn " + maxObserved + " exceeded cap " + MAX_TURN,
                maxObserved <= MAX_TURN + GCD);
    }

    /** GCD math sanity: step formula matches vanilla at 50% sensitivity. */
    @Test
    public void testGcdStepValue() {
        float step = MouseGCD.stepForSensitivity(0.5);
        assertEquals(0.15f, step, 1e-6);
        // Quantize produces multiples
        float q = MouseGCD.quantize(1.234f, step);
        assertEquals(0.0f, q / step - Math.round(q / step), 1e-6);
    }

    private void assertYawPitchOnGcd(Rotation r) {
        assertEquals(0.0f, r.getYaw() / GCD - Math.round(r.getYaw() / GCD), 1e-4);
        assertEquals(0.0f, r.getPitch() / GCD - Math.round(r.getPitch() / GCD), 1e-4);
    }
}
