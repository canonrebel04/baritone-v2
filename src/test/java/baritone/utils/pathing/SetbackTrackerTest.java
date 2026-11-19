/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.pathing;

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SetbackTrackerTest {

    @Test
    public void accumulatesAndDecaysPenalties() {
        SetbackTracker tracker = new SetbackTracker();
        BetterBlockPos pos = new BetterBlockPos(100, 64, -200);
        long hash = BetterBlockPos.longHash(pos);

        tracker.recordSetback(pos, 1_000L);
        tracker.recordSetback(pos, 2_000L);

        Long2DoubleOpenHashMap favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);

        // At t=2_000 (0ms age), hits=2, baseCoeff=5.0 -> 1 + 4 * 2 * 1.0 = 9.0
        tracker.applyTo(favorings, 2_000L, 10_000L, 5.0D);
        assertEquals(9.0D, favorings.get(hash), 1e-6);

        // At t=7_000 (half decayed), hits=2, decay=0.5 -> 1 + 4 * 2 * 0.5 = 5.0
        favorings.clear();
        tracker.applyTo(favorings, 7_000L, 10_000L, 5.0D);
        assertEquals(5.0D, favorings.get(hash), 1e-6);

        // At t=12_000 (expired), entry is evicted and map stays untouched
        favorings.clear();
        tracker.applyTo(favorings, 12_000L, 10_000L, 5.0D);
        assertEquals(1.0D, favorings.get(hash), 1e-6);
        assertEquals(0, tracker.size());
    }

    @Test
    public void capsHitsAtFive() {
        SetbackTracker tracker = new SetbackTracker();
        BetterBlockPos pos = new BetterBlockPos(0, 70, 0);
        long hash = BetterBlockPos.longHash(pos);
        for (int i = 0; i < 10; i++) {
            tracker.recordSetback(pos, 1_000L);
        }
        Long2DoubleOpenHashMap favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        tracker.applyTo(favorings, 1_000L, 60_000L, 5.0D);
        // 1 + (5 - 1) * 5 = 21.0
        assertTrue(favorings.get(hash) <= 21.0001D);
        assertEquals(21.0D, favorings.get(hash), 1e-6);
    }
}
