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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PartialTakeTest {
    @Test
    public void everyCountIsExact() {
        for (int have = 1; have <= 64; have++) {
            for (int want = 1; want <= have; want++) {
                List<PartialTake.Click> c = PartialTake.clicks(5, 40, have, want);
                int[] r = PartialTake.simulate(c, 5, 40, have);
                assertEquals("have " + have + " want " + want, want, r[1]);
                assertEquals(have - want, r[0]);
                assertEquals("cursor empty", 0, r[2]);
                assertTrue("clicks " + c.size(), c.size() <= Math.min(want, have - want) + 3);
            }
        }
    }

    @Test
    public void halfIsTwoClicks() {
        assertEquals(2, PartialTake.clicks(0, 30, 64, 32).size());
        assertEquals(2, PartialTake.clicks(0, 30, 7, 4).size());
        assertEquals(2, PartialTake.clicks(0, 30, 16, 16).size());
        assertTrue(PartialTake.clicks(0, 30, 16, 0).isEmpty());
    }
}
