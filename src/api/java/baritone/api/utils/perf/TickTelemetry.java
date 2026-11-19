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

package baritone.api.utils.perf;

import java.util.*;

/**
 * Plan S93/S94: per-key tick time telemetry (p50/p95/p99 over the last {@link #WINDOW} samples) with an
 * auto-degrade flag: a key is "degraded" once its p95 exceeds its budget and recovers when p95 falls below 60% of
 * it (hysteresis, so quality doesn't flap). Callers use {@link #degraded} to shrink scan radii and such.
 * Thread-safe; allocation-free on the hot path (fixed ring buffers).
 */
public final class TickTelemetry {
    public static final int WINDOW = 200;
    private static final Map<String, TickTelemetry> ALL = new LinkedHashMap<>();

    private final String key;
    private final long[] samples = new long[WINDOW];
    private int count;
    private int next;
    private long max;
    private long total;
    private boolean degraded;
    private long budgetNanos;

    public TickTelemetry(String key, long budgetNanos) {
        this.key = key;
        this.budgetNanos = budgetNanos;
    }

    /** Shared telemetry for a key (created with the given budget on first use). */
    public static synchronized TickTelemetry of(String key, long budgetNanos) {
        return ALL.computeIfAbsent(key, k -> new TickTelemetry(k, budgetNanos));
    }

    public static synchronized List<TickTelemetry> all() {
        return new ArrayList<>(ALL.values());
    }

    public static synchronized void resetAll() {
        for (TickTelemetry t : ALL.values()) t.reset();
    }

    /** True when the key exists and is over budget. */
    public static synchronized boolean degraded(String key) {
        TickTelemetry t = ALL.get(key);
        return t != null && t.degraded();
    }

    public synchronized void record(long nanos) {
        samples[next] = nanos;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        total++;
        if (nanos > max) max = nanos;
        if (count >= 20 && (total % 10 == 0)) {
            long p95 = percentile(95);
            if (!degraded && p95 > budgetNanos) degraded = true;
            else if (degraded && p95 < budgetNanos * 0.6) degraded = false;
        }
    }

    public synchronized void setBudget(long nanos) {
        budgetNanos = nanos;
    }

    public synchronized long budget() {
        return budgetNanos;
    }

    public synchronized boolean degraded() {
        return degraded;
    }

    public synchronized int count() {
        return count;
    }

    public synchronized long max() {
        return max;
    }

    /** Nearest-rank percentile of the window, in nanoseconds (0 when empty). */
    public synchronized long percentile(int p) {
        if (count == 0) return 0;
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        int rank = (int) Math.ceil(p / 100.0 * count) - 1;
        return copy[Math.max(0, Math.min(count - 1, rank))];
    }

    public synchronized void reset() {
        Arrays.fill(samples, 0);
        count = next = 0;
        max = total = 0;
        degraded = false;
    }

    public String key() {
        return key;
    }

    /** "GetItem: p50 0.12 ms, p95 0.40, p99 0.90, max 2.10 (budget 1.00) [degraded]". */
    public synchronized String summary() {
        return String.format(Locale.ROOT, "%s: p50 %.2f ms, p95 %.2f, p99 %.2f, max %.2f (budget %.2f)%s", key,
                percentile(50) / 1e6, percentile(95) / 1e6, percentile(99) / 1e6, max / 1e6, budgetNanos / 1e6,
                degraded ? " [degraded]" : "");
    }
}
