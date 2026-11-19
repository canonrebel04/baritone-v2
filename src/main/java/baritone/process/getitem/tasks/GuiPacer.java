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

import baritone.Baritone;
import baritone.behavior.look.LookProfile;

/**
 * Paces inventory / container clicks (plan S40/S41 pacing for A22/A23): one click per interval, interval from
 * {@code getItemClickDelayTicks} or the look profile (LEGIT_SMOOTH 5 +/- 2 ticks, BALANCED 3 +/- 1, FAST 1),
 * with deterministic per-click jitter so bursts never look machine-regular.
 */
public final class GuiPacer {
    private int wait;
    private long clicks;

    /** Base/jitter for a profile (pure, for tests). */
    static int[] profileDelay(LookProfile p) {
        switch (p) {
            case FAST:
                return new int[]{1, 0};
            case BALANCED:
                return new int[]{3, 1};
            default:
                return new int[]{5, 2};
        }
    }

    /** Interval before click number {@code n}, given base and jitter (pure, deterministic). */
    static int interval(int base, int jitter, long n) {
        if (jitter <= 0) return Math.max(1, base);
        long h = n * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 31);
        int j = (int) Math.floorMod(h, 2L * jitter + 1) - jitter;
        return Math.max(1, base + j);
    }

    /** Call once per tick; true when a click may be sent now (and starts the next interval). */
    public boolean ready() {
        if (wait > 0) {
            wait--;
            return false;
        }
        int set = Baritone.settings().getItemClickDelayTicks.value;
        int[] bj = set >= 0 ? new int[]{set, 0} : profileDelay(LookProfile.parse(Baritone.settings().lookProfile.value));
        wait = interval(bj[0], bj[1], clicks++) - 1;
        return true;
    }

    /** Delay the next click (e.g. after opening a screen, a "reaction" pause). */
    public void delay(int ticks) {
        wait = Math.max(wait, ticks);
    }
}
