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

package baritone.api.utils;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Plan S97: repeated log lines are collapsed with a count.
 */
public class LogThrottleTest {

    @Test
    public void firstPassesRepeatsAreSuppressedThenCounted() {
        AtomicLong now = new AtomicLong(1000);
        LogThrottle t = new LogThrottle(1000, now::get);
        assertEquals(0, t.check("a"));
        assertEquals(LogThrottle.SUPPRESS, t.check("a"));
        assertEquals(LogThrottle.SUPPRESS, t.check("a"));
        assertEquals(0, t.check("b"));
        now.addAndGet(1000);
        int n = t.check("a");
        assertEquals(2, n);
        assertEquals(" (repeated 2x)", LogThrottle.suffix(n));
        assertEquals("", LogThrottle.suffix(0));
    }

    @Test
    public void zeroWindowDisablesThrottling() {
        LogThrottle t = new LogThrottle(0, () -> 0L);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, t.check("x"));
        }
    }

    @Test
    public void keySpaceIsBounded() {
        AtomicLong now = new AtomicLong();
        LogThrottle t = new LogThrottle(1000, now::get);
        for (int i = 0; i < LogThrottle.MAX_KEYS * 3; i++) {
            assertEquals(0, t.check("k" + i));
        }
        // still works after evictions
        assertEquals(LogThrottle.SUPPRESS, t.check("k" + (LogThrottle.MAX_KEYS * 3 - 1)));
    }
}
