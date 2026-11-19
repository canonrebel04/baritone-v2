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

/**
 * Server-lag adaptation (plan S50): smoothed ping and server TPS give a factor that stretches GUI/interaction
 * pacing on laggy servers, so actions don't arrive at the server as desynced bursts. 1.0 = no lag.
 */
public final class LagModel {
    private static final LagModel GLOBAL = new LagModel();
    private double pingMs = -1;
    private double tps = 20;
    private long lastTimePacketNanos = -1;
    private long lastGameTime = -1;

    public static LagModel global() {
        return GLOBAL;
    }

    /** Feed the latency the server reports for us (ms). */
    public synchronized void ping(int ms) {
        if (ms <= 0) return;
        pingMs = pingMs < 0 ? ms : pingMs * 0.9 + ms * 0.1;
    }

    /** Feed a world-time update: server game time and the local receive time. */
    public synchronized void timeUpdate(long gameTime, long nanos) {
        if (lastTimePacketNanos > 0 && gameTime > lastGameTime) {
            double seconds = (nanos - lastTimePacketNanos) / 1e9;
            if (seconds > 0.2 && seconds < 30) {
                double measured = Math.min(20, (gameTime - lastGameTime) / seconds);
                tps = tps * 0.8 + measured * 0.2;
            }
        }
        lastTimePacketNanos = nanos;
        lastGameTime = gameTime;
    }

    public synchronized double pingMs() {
        return pingMs;
    }

    public synchronized double tps() {
        return tps;
    }

    /** Pacing multiplier in [1, 3]: +1 per 200 ms of ping above 100 ms, times 20/TPS. */
    public synchronized double factor() {
        double p = pingMs <= 100 ? 1.0 : 1.0 + (pingMs - 100) / 200.0;
        double t = 20.0 / Math.max(5.0, Math.min(20.0, tps));
        return Math.max(1.0, Math.min(3.0, p * t));
    }

    public synchronized void reset() {
        pingMs = -1;
        tps = 20;
        lastTimePacketNanos = -1;
        lastGameTime = -1;
    }
}
