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

/** Deterministic, well-mixed jitter so paced actions are never machine-regular yet reproducible in tests. */
public final class Jitter {
    private Jitter() {
    }

    /** Uniform integer in [-amp, amp] for event number {@code n} on channel {@code salt}. */
    public static int of(long n, long salt, int amp) {
        if (amp <= 0) return 0;
        long h = (n + salt * 0x632BE59BD9B4E019L) * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 29);
        return (int) Math.floorMod(h, 2L * amp + 1) - amp;
    }
}
