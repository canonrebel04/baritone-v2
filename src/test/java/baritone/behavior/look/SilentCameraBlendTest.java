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

/** Silent -> camera hand-off: no single-packet snap of the server rotation, and the two legs converge. */
public class SilentCameraBlendTest {
    @Test
    public void serverLegNeverSnapsAndConverges() {
        for (LookProfile profile : new LookProfile[]{LookProfile.LEGIT_SMOOTH, LookProfile.BALANCED}) {
            SaccadeAimController server = new SaccadeAimController(1);
            SaccadeAimController camera = new SaccadeAimController(2);
            server.reset(90, 10); // silent walking yaw
            camera.reset(0, 0);   // camera untouched
            double targetYaw = 20, targetPitch = 40; // block to mine
            double maxTurn = 25;
            assertTrue(SilentCameraBlend.needed(90, 10, 0, 0, 0.15));
            int ticks = 0;
            double prevYaw = server.yaw(), prevPitch = server.pitch();
            while (!SilentCameraBlend.converged(server.yaw(), server.pitch(), camera.yaw(), camera.pitch(), 0.15) && ticks < 40) {
                server.step(targetYaw, targetPitch, profile, maxTurn, true);
                camera.step(targetYaw, targetPitch, profile, maxTurn, true);
                double d = SilentCameraBlend.distance(prevYaw, prevPitch, server.yaw(), server.pitch());
                assertTrue(profile + " server step " + d, d <= maxTurn * 1.5 + 1e-6);
                prevYaw = server.yaw();
                prevPitch = server.pitch();
                ticks++;
            }
            assertTrue(profile + " converged in " + ticks, ticks < 40);
            // a direct switch would have been a 90+ degree single-packet jump
            assertTrue(SilentCameraBlend.distance(90, 10, 0, 0) > 80);
        }
    }

    @Test
    public void thresholds() {
        assertFalse(SilentCameraBlend.needed(0, 0, 1, 0, 0.15));
        assertTrue(SilentCameraBlend.converged(359.8, 0, 0, 0, 0.15));
        assertEquals(2.0, SilentCameraBlend.distance(359, 0, 1, 0), 1e-9);
    }
}
