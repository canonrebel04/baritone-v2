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

package baritone.api.utils.pacing;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** PS2 shared pacing layer (S36-S41, S50). */
public class PacingTest {
    @Test
    public void paceFromProfileNames() {
        assertEquals(Pace.LEGIT, Pace.fromProfile("LEGIT_SMOOTH"));
        assertEquals(Pace.LEGIT, Pace.fromProfile(null));
        assertEquals(Pace.BALANCED, Pace.fromProfile("balanced"));
        assertEquals(Pace.FAST, Pace.fromProfile("FAST"));
    }

    @Test
    public void clickIntervalsOrderedAndTravelCosts() {
        double far = GuiPacing.slotDistance(0, 26, 9);
        assertTrue(far > 3);
        for (long n = 0; n < 50; n++) {
            int legitNear = GuiPacing.clickInterval(Pace.LEGIT, 0, n, 1.0);
            int legitFar = GuiPacing.clickInterval(Pace.LEGIT, far, n, 1.0);
            assertTrue(legitFar > legitNear);
            assertTrue(legitNear >= 3 && legitNear <= 7);
            assertEquals(1, GuiPacing.clickInterval(Pace.FAST, far, n, 1.0));
            assertTrue(GuiPacing.clickInterval(Pace.BALANCED, far, n, 1.0) <= legitFar);
        }
        assertTrue(GuiPacing.clickInterval(Pace.LEGIT, 0, 3, 2.0) >= 2 * GuiPacing.clickInterval(Pace.LEGIT, 0, 3, 1.0) - 1);
    }

    @Test
    public void openReadScalesWithContents() {
        assertTrue(GuiPacing.openReadDelay(Pace.LEGIT, 54, 1) > GuiPacing.openReadDelay(Pace.LEGIT, 0, 1));
        assertTrue(GuiPacing.openReadDelay(Pace.LEGIT, 0, 1) > GuiPacing.openReadDelay(Pace.BALANCED, 0, 1));
        assertEquals(1, GuiPacing.openReadDelay(Pace.FAST, 54, 1));
        assertEquals(0, GuiPacing.closeDelay(Pace.FAST, 1));
        assertTrue(GuiPacing.closeDelay(Pace.LEGIT, 1) > 0);
    }

    @Test
    public void pacerRespectsIntervalAndSharedBudget() {
        GuiPacer a = new GuiPacer(() -> Pace.FAST, () -> 1.0);
        GuiPacer b = new GuiPacer(() -> Pace.FAST, () -> 1.0);
        GuiPacer.globalTick();
        assertTrue(a.ready());
        assertFalse("only one click per tick across pacers", b.ready());
        GuiPacer.globalTick();
        assertTrue(b.ready());
        GuiPacer legit = new GuiPacer(() -> Pace.LEGIT, () -> 1.0);
        int clicks = 0;
        for (int t = 0; t < 100; t++) {
            GuiPacer.globalTick();
            if (legit.ready()) clicks++;
        }
        assertTrue(clicks >= 100 / 8 && clicks <= 100 / 3 + 1);
        GuiPacer fixed = new GuiPacer(() -> Pace.LEGIT, () -> 1.0).withOverride(() -> 2);
        int f = 0;
        for (int t = 0; t < 20; t++) {
            GuiPacer.globalTick();
            if (fixed.ready()) f++;
        }
        assertEquals(10, f);
    }

    @Test
    public void openedDelaysFirstClick() {
        GuiPacer p = new GuiPacer(() -> Pace.LEGIT, () -> 1.0);
        p.opened(27);
        int waited = 0;
        while (true) {
            GuiPacer.globalTick();
            if (p.ready()) break;
            waited++;
        }
        assertEquals(GuiPacing.openReadDelay(Pace.LEGIT, 27, 1.0), waited);
    }

    @Test
    public void hotbarScrollShortestWay() {
        assertEquals(1, HotbarScroll.delta(0, 1));
        assertEquals(-1, HotbarScroll.delta(0, 8));
        assertEquals(1, HotbarScroll.next(0, 2, Pace.LEGIT)); // scroll one slot per tick
        assertEquals(8, HotbarScroll.next(0, 7, Pace.LEGIT)); // wraps backwards
        assertEquals(5, HotbarScroll.next(1, 5, Pace.LEGIT)); // far: number key
        assertEquals(2, HotbarScroll.next(0, 3, Pace.BALANCED));
        assertEquals(6, HotbarScroll.next(0, 6, Pace.FAST));
        // always terminates
        for (Pace pace : Pace.values()) {
            for (int from = 0; from < 9; from++) {
                for (int to = 0; to < 9; to++) {
                    int cur = from, steps = 0;
                    while (cur != to && steps++ < 9) cur = HotbarScroll.next(cur, to, pace);
                    assertEquals(to, cur);
                    assertTrue(steps <= 3);
                }
            }
        }
    }

    @Test
    public void rhythmVariesButFastIsFixed() {
        Set<Integer> seen = new HashSet<>();
        for (long n = 0; n < 100; n++) {
            int g = InteractionRhythm.breakGap(Pace.LEGIT, 5, n, 1.0);
            assertTrue(g >= 5 && g <= 9);
            seen.add(g);
            assertEquals(5, InteractionRhythm.breakGap(Pace.FAST, 5, n, 1.0));
            int pl = InteractionRhythm.placeGap(Pace.LEGIT, 3, n, 1.0);
            assertTrue(pl >= 2 && pl <= 6);
            assertEquals(3, InteractionRhythm.placeGap(Pace.FAST, 3, n, 1.0));
        }
        assertTrue(seen.size() >= 3);
    }

    @Test
    public void lagModelFactor() {
        LagModel m = new LagModel();
        assertEquals(1.0, m.factor(), 1e-9);
        for (int i = 0; i < 100; i++) m.ping(300);
        assertEquals(2.0, m.factor(), 0.05);
        LagModel t = new LagModel();
        long ns = 0;
        long gt = 0;
        for (int i = 0; i < 60; i++) { // 10 TPS: 20 game ticks every 2 s
            t.timeUpdate(gt, ns);
            gt += 20;
            ns += 2_000_000_000L;
        }
        assertEquals(10, t.tps(), 0.5);
        assertEquals(2.0, t.factor(), 0.1);
        t.reset();
        assertEquals(1.0, t.factor(), 1e-9);
    }
}
