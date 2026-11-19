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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks walking path nodes where the server recently issued a position setback (rubberband).
 * Applying decaying cost multipliers into {@link Favoring} prevents A* from repeatedly
 * selecting the same anticheat-flagged movement in a loop.
 */
public final class SetbackTracker {

    private static final int MAX_HITS = 5;
    private static final int MAX_ENTRIES = 1024;

    private record Entry(int hits, long lastSeenMs) {}

    private final ConcurrentHashMap<Long, Entry> setbacks = new ConcurrentHashMap<>();

    public void recordSetback(BetterBlockPos pos, long nowMs) {
        if (pos == null) {
            return;
        }
        if (setbacks.size() >= MAX_ENTRIES) {
            setbacks.clear();
        }
        long hash = BetterBlockPos.longHash(pos);
        setbacks.compute(hash, (k, existing) -> {
            if (existing == null) {
                return new Entry(1, nowMs);
            }
            return new Entry(Math.min(MAX_HITS, existing.hits() + 1), nowMs);
        });
    }

    public void applyTo(Long2DoubleOpenHashMap favorings, long nowMs, long decayMs, double baseCoeff) {
        if (setbacks.isEmpty() || decayMs <= 0L || baseCoeff <= 1.0D) {
            return;
        }
        Iterator<Map.Entry<Long, Entry>> it = setbacks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Entry> mapEntry = it.next();
            Entry entry = mapEntry.getValue();
            long age = nowMs - entry.lastSeenMs();
            if (age >= decayMs) {
                it.remove();
                continue;
            }
            double decayFactor = age <= 0L ? 1.0D : 1.0D - ((double) age / (double) decayMs);
            double multiplier = 1.0D + (baseCoeff - 1.0D) * entry.hits() * decayFactor;
            long hash = mapEntry.getKey();
            favorings.put(hash, favorings.get(hash) * multiplier);
        }
    }

    public int size() {
        return setbacks.size();
    }

    public void clear() {
        setbacks.clear();
    }
}
