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

package baritone.process.getitem.tasks;

import baritone.behavior.look.LookProfile;
import org.junit.Test;

import static org.junit.Assert.*;

/** GUI pacing: per-profile intervals with bounded deterministic jitter. */
public class GuiPacerTest {
    @Test
    public void legitIsSlowerThanFast() {
        int[] legit = GuiPacer.profileDelay(LookProfile.LEGIT_SMOOTH);
        int[] bal = GuiPacer.profileDelay(LookProfile.BALANCED);
        int[] fast = GuiPacer.profileDelay(LookProfile.FAST);
        assertTrue(legit[0] > bal[0] && bal[0] > fast[0]);
        assertEquals(1, GuiPacer.interval(fast[0], fast[1], 7));
    }

    @Test
    public void jitterBoundedAndVaried() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (long n = 0; n < 200; n++) {
            int i = GuiPacer.interval(5, 2, n);
            assertTrue(i >= 3 && i <= 7);
            seen.add(i);
            assertEquals(i, GuiPacer.interval(5, 2, n));
        }
        assertTrue("jitter should vary", seen.size() >= 4);
    }
}
