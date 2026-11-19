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

package baritone;

import baritone.api.utils.perf.TickTelemetry;
import org.junit.Test;

import static org.junit.Assert.*;

/** S93/S94: percentiles and degrade hysteresis. */
public class TickTelemetryTest {
    @Test
    public void percentiles() {
        TickTelemetry t = new TickTelemetry("x", 1_000_000);
        for (int i = 1; i <= 100; i++) t.record(i * 1000L);
        assertEquals(50_000, t.percentile(50));
        assertEquals(95_000, t.percentile(95));
        assertEquals(99_000, t.percentile(99));
        assertEquals(100_000, t.max());
        assertTrue(t.summary().startsWith("x: p50 0.05 ms"));
    }

    @Test
    public void windowRollsOver() {
        TickTelemetry t = new TickTelemetry("y", 1_000_000);
        for (int i = 0; i < TickTelemetry.WINDOW; i++) t.record(5_000_000);
        for (int i = 0; i < TickTelemetry.WINDOW; i++) t.record(1000);
        assertEquals(1000, t.percentile(99));
        assertEquals(TickTelemetry.WINDOW, t.count());
    }

    @Test
    public void degradeHasHysteresis() {
        TickTelemetry t = new TickTelemetry("z", 1_000_000);
        for (int i = 0; i < 40; i++) t.record(2_000_000);
        assertTrue(t.degraded());
        // just under budget: still degraded (needs < 60%)
        for (int i = 0; i < TickTelemetry.WINDOW; i++) t.record(900_000);
        assertTrue(t.degraded());
        for (int i = 0; i < TickTelemetry.WINDOW; i++) t.record(100_000);
        assertFalse(t.degraded());
        t.reset();
        assertEquals(0, t.count());
    }

    @Test
    public void sharedRegistry() {
        TickTelemetry a = TickTelemetry.of("TestProc", 1_000_000);
        assertSame(a, TickTelemetry.of("TestProc", 5));
        assertFalse(TickTelemetry.degraded("NoSuchProc"));
    }
}
