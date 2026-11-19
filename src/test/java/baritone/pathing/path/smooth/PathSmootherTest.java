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

package baritone.pathing.path.smooth;

import org.junit.Test;

import static org.junit.Assert.*;

/** PS2 locomotion: path smoothing, speed profile, sprint hysteresis, crowd steering (S16-S20, S34). */
public class PathSmootherTest {
    @Test
    public void lookaheadScalesWithSpeed() {
        assertEquals(1.2, PathSmoother.lookahead(0), 1e-9);
        assertTrue(PathSmoother.lookahead(0.28) > PathSmoother.lookahead(0.1));
        assertEquals(3.5, PathSmoother.lookahead(5), 1e-9);
    }

    @Test
    public void straightRunLooksAhead() {
        double[] nodes = {1.5, 0.5, 2.5, 0.5, 3.5, 0.5, 4.5, 0.5};
        double[] c = PathSmoother.carrot(0.5, 0.5, nodes, 2.5);
        assertEquals(3.0, c[0], 1e-6);
        assertEquals(0.5, c[1], 1e-6);
    }

    @Test
    public void cornerIsNeverCutBeyondTolerance() {
        // east to x=3.5, then north (an L); node 0 is always the current destination ahead of the player
        double[] all = {1.5, 0.5, 2.5, 0.5, 3.5, 0.5, 3.5, -0.5, 3.5, -1.5, 3.5, -2.5};
        for (double px = 0.5; px < 3.45; px += 0.05) {
            int first = 0;
            while (all[2 * first] < px + 1e-9 && first < 2) first++;
            double[] nodes = java.util.Arrays.copyOfRange(all, 2 * first, all.length);
            for (double L = 1.2; L <= 3.5; L += 0.1) {
                double[] c = PathSmoother.carrot(px, 0.5, nodes, L);
                if (c[1] < 0.5 - 1e-9) { // carrot is round the corner: the corner node must be honoured
                    assertTrue(PathSmoother.segmentDistance(px, 0.5, c[0], c[1], 3.5, 0.5) <= PathSmoother.TOLERANCE + 1e-3);
                }
            }
        }
        // close to the corner the carrot swings round it (pre-turn) instead of aiming at the center
        double[] c = PathSmoother.carrot(3.2, 0.5, new double[]{3.5, 0.5, 3.5, -0.5, 3.5, -1.5}, 2.0);
        assertTrue(c[1] < 0.5);
        // far from the corner it stays on the straight
        double[] far = PathSmoother.carrot(0.5, 0.5, all, 2.0);
        assertEquals(0.5, far[1], 1e-9);
    }

    @Test
    public void carrotNeverBehindFirstNode() {
        double[] nodes = {1.5, 0.5, 1.5, 1.5};
        double[] c = PathSmoother.carrot(0.5, 0.5, nodes, 0.5);
        assertEquals(1.0, c[0], 1e-6);
        assertEquals(0.5, c[1], 1e-6);
    }

    @Test
    public void speedProfileFindsSharpTurns() {
        double[] straight = {1.5, 0.5, 2.5, 0.5, 3.5, 0.5};
        assertTrue(Double.isInfinite(PathSmoother.distanceToTurn(0.5, 0.5, straight, 60)));
        double[] l = {1.5, 0.5, 2.5, 0.5, 2.5, 1.5};
        assertEquals(2.0, PathSmoother.distanceToTurn(0.5, 0.5, l, 60), 1e-6);
        double[] gentle = {1.5, 0.5, 2.5, 1.5, 3.5, 2.5}; // 45 degrees
        assertTrue(Double.isInfinite(PathSmoother.distanceToTurn(0.5, 0.5, gentle, 60)));
        assertTrue(PathSmoother.releaseSprintForTurn(1.5, 0.28));
        assertFalse(PathSmoother.releaseSprintForTurn(4, 0.28));
    }

    @Test
    public void sprintHysteresis() {
        boolean l = true;
        l = PathSmoother.sprintLatch(l, 25);
        assertTrue(l);
        l = PathSmoother.sprintLatch(l, 30);
        assertFalse(l);
        l = PathSmoother.sprintLatch(l, 22);
        assertFalse("stays off in the band", l);
        l = PathSmoother.sprintLatch(l, 15);
        assertTrue(l);
    }

    @Test
    public void crowdSide() {
        // heading east (+x); right-hand side is +z (south)
        assertEquals(-1, PathSmoother.crowdSide(1, 0, new double[]{1.5, 0.3})); // mob slightly right -> go left
        assertEquals(1, PathSmoother.crowdSide(1, 0, new double[]{1.5, -0.3}));
        assertEquals(0, PathSmoother.crowdSide(1, 0, new double[]{-1.5, 0}));
        assertEquals(0, PathSmoother.crowdSide(1, 0, new double[]{1.5, 1.5}));
    }
}
