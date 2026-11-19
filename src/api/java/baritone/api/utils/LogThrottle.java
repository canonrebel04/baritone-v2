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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Collapses repeated log lines (plan S97): the first occurrence of a key passes, repeats within the window are
 * counted and suppressed, and the next line after the window carries a {@code " (repeated Nx)"} suffix.
 *
 * <p>Thread safe; bounded to {@link #MAX_KEYS} keys (the oldest windows are evicted when full).
 */
public final class LogThrottle {

    public static final int MAX_KEYS = 1024;
    /** Returned by {@link #check} when the line should be dropped. */
    public static final int SUPPRESS = -1;

    private final long windowMs;
    private final LongSupplier clock;
    private final Map<String, long[]> entries = new HashMap<>(); // key -> {windowStart, suppressedCount}

    public LogThrottle(long windowMs) {
        this(windowMs, System::currentTimeMillis);
    }

    public LogThrottle(long windowMs, LongSupplier clock) {
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /**
     * @return {@link #SUPPRESS} to drop this line, otherwise the number of lines suppressed since the last one
     * that passed (0 for none)
     */
    public synchronized int check(String key) {
        if (windowMs <= 0) {
            return 0;
        }
        final long now = clock.getAsLong();
        long[] e = entries.get(key);
        if (e == null) {
            if (entries.size() >= MAX_KEYS) {
                evict(now);
            }
            entries.put(key, new long[]{now, 0});
            return 0;
        }
        if (now - e[0] < windowMs) {
            e[1]++;
            return SUPPRESS;
        }
        int suppressed = (int) Math.min(Integer.MAX_VALUE, e[1]);
        e[0] = now;
        e[1] = 0;
        return suppressed;
    }

    private void evict(long now) {
        Iterator<long[]> it = entries.values().iterator();
        while (it.hasNext()) {
            if (now - it.next()[0] >= windowMs) {
                it.remove();
            }
        }
        if (entries.size() >= MAX_KEYS) {
            entries.clear();
        }
    }

    /** Suffix for a line that passed after {@code suppressed} repeats were dropped. */
    public static String suffix(int suppressed) {
        return suppressed > 0 ? " (repeated " + suppressed + "x)" : "";
    }
}
