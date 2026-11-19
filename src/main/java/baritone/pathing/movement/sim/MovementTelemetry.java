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

package baritone.pathing.movement.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe movement telemetry, failure memory (Enhancement #46),
 * debug trajectory deviation logging (Enhancement #49), and
 * adaptive per-movement session cost tuning (Enhancement #50).
 * Pure Java / headless safe.
 */
public final class MovementTelemetry {

    private static final long FAILURE_MEMORY_TTL_MS = 120_000L; // 2 minutes decay window
    private static final int MAX_FAILURE_ENTRIES = 2048;

    private static final class FailureRecord {
        int count;
        long lastFailureMs;
    }

    private static final class MoveStats {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicLong totalActualTicks = new AtomicLong();
        final AtomicLong totalEstimatedTicksMilli = new AtomicLong();
    }

    private static final ConcurrentHashMap<Long, FailureRecord> FAILURE_MEMORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MoveStats> MOVE_STATS = new ConcurrentHashMap<>();

    private MovementTelemetry() {
    }

    /**
     * Hashes an edge (src -> dest) and movement type name into a 64-bit key.
     */
    public static long edgeKey(int srcX, int srcY, int srcZ, int destX, int destY, int destZ, String moveType) {
        long h = 1469598103934665603L;
        h = (h ^ srcX) * 1099511628211L;
        h = (h ^ srcY) * 1099511628211L;
        h = (h ^ srcZ) * 1099511628211L;
        h = (h ^ destX) * 1099511628211L;
        h = (h ^ destY) * 1099511628211L;
        h = (h ^ destZ) * 1099511628211L;
        h = (h ^ (moveType != null ? moveType.hashCode() : 0)) * 1099511628211L;
        return h;
    }

    /**
     * Records a physical execution failure on a specific edge (e.g., missed parkour,
     * stuck circle, unreachable movement).
     */
    public static void recordFailure(int srcX, int srcY, int srcZ, int destX, int destY, int destZ, String moveType) {
        if (FAILURE_MEMORY.size() > MAX_FAILURE_ENTRIES) {
            pruneExpired();
        }
        long key = edgeKey(srcX, srcY, srcZ, destX, destY, destZ, moveType);
        long now = System.currentTimeMillis();
        FAILURE_MEMORY.compute(key, (k, rec) -> {
            if (rec == null || (now - rec.lastFailureMs) > FAILURE_MEMORY_TTL_MS) {
                rec = new FailureRecord();
            }
            rec.count = Math.min(rec.count + 1, 10);
            rec.lastFailureMs = now;
            return rec;
        });
        if (moveType != null) {
            MOVE_STATS.computeIfAbsent(moveType, k -> new MoveStats()).failures.incrementAndGet();
        }
    }

    /**
     * Records a successful movement completion along with observed vs estimated ticks.
     */
    public static void recordSuccess(String moveType, int actualTicks, double estimatedTicks) {
        if (moveType == null) {
            return;
        }
        MoveStats stats = MOVE_STATS.computeIfAbsent(moveType, k -> new MoveStats());
        stats.successes.incrementAndGet();
        stats.totalActualTicks.addAndGet(Math.max(1, actualTicks));
        stats.totalEstimatedTicksMilli.addAndGet(Math.max(1000L, Math.round(estimatedTicks * 1000.0)));
    }

    /**
     * Returns an additive penalty for an edge that recently failed physically.
     * Returns 0.0 if no recent failure is recorded.
     */
    public static double getEdgeFailurePenalty(int srcX, int srcY, int srcZ, int destX, int destY, int destZ, String moveType) {
        if (FAILURE_MEMORY.isEmpty()) {
            return 0.0;
        }
        long key = edgeKey(srcX, srcY, srcZ, destX, destY, destZ, moveType);
        FailureRecord rec = FAILURE_MEMORY.get(key);
        if (rec == null) {
            return 0.0;
        }
        long age = System.currentTimeMillis() - rec.lastFailureMs;
        if (age > FAILURE_MEMORY_TTL_MS) {
            FAILURE_MEMORY.remove(key, rec);
            return 0.0;
        }
        double decay = 1.0 - (age / (double) FAILURE_MEMORY_TTL_MS);
        return rec.count * 12.0 * decay;
    }

    /**
     * Returns an adaptive cost multiplier clamped to [0.85, 1.80] for a movement type
     * based on observed session success rate and execution speed.
     */
    public static double getAdaptiveCostMultiplier(String moveType) {
        if (moveType == null || MOVE_STATS.isEmpty()) {
            return 1.0;
        }
        MoveStats stats = MOVE_STATS.get(moveType);
        if (stats == null) {
            return 1.0;
        }
        int succ = stats.successes.get();
        int fail = stats.failures.get();
        int total = succ + fail;
        if (total < 5) {
            return 1.0;
        }
        double failRate = fail / (double) total;
        double mult = 1.0 + failRate * 0.8;
        if (succ >= 5) {
            double avgActual = stats.totalActualTicks.get() / (double) succ;
            double avgEst = (stats.totalEstimatedTicksMilli.get() / 1000.0) / succ;
            if (avgEst > 0.5) {
                double ratio = avgActual / avgEst;
                // Blend gently (25%) toward observed tick ratio
                mult *= (0.75 + 0.25 * Math.max(0.7, Math.min(1.6, ratio)));
            }
        }
        return Math.max(0.85, Math.min(1.80, mult));
    }

    /**
     * Formats a concise summary of session movement stats for debug telemetry.
     */
    public static String formatSummary() {
        if (MOVE_STATS.isEmpty()) {
            return "MovementTelemetry: no samples recorded yet.";
        }
        StringBuilder sb = new StringBuilder("MovementTelemetry: ");
        for (Map.Entry<String, MoveStats> e : MOVE_STATS.entrySet()) {
            MoveStats s = e.getValue();
            int succ = s.successes.get();
            int fail = s.failures.get();
            sb.append(e.getKey())
              .append("[ok=").append(succ)
              .append(",fail=").append(fail)
              .append(",mult=").append(String.format("%.2f", getAdaptiveCostMultiplier(e.getKey())))
              .append("] ");
        }
        return sb.toString().trim();
    }

    public static void reset() {
        FAILURE_MEMORY.clear();
        MOVE_STATS.clear();
    }

    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        FAILURE_MEMORY.entrySet().removeIf(e -> (now - e.getValue().lastFailureMs) > FAILURE_MEMORY_TTL_MS);
    }
}
