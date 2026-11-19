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
 * Break / place rhythm (plan S36, S38): variable but consistent gaps between consecutive block breaks and
 * placements instead of a perfect fixed rate, stretched by server lag (S50). FAST keeps the configured base.
 */
public final class InteractionRhythm {
    private InteractionRhythm() {
    }

    /** Ticks to wait after a block broke before hitting the next one (base = Baritone's blockBreakSpeed - 1). */
    public static int breakGap(Pace pace, int base, long n, double lagFactor) {
        int extra;
        switch (pace) {
            case FAST:
                return Math.max(0, base);
            case BALANCED:
                extra = 1 + Jitter.of(n, 3, 1);
                break;
            default:
                extra = 2 + Jitter.of(n, 3, 2);
        }
        return Math.max(0, (int) Math.round((base + Math.max(0, extra)) * Math.max(1.0, lagFactor)));
    }

    /** Ticks between right-click placements (base = rightClickSpeed - 1). */
    public static int placeGap(Pace pace, int base, long n, double lagFactor) {
        int jitter;
        switch (pace) {
            case FAST:
                return Math.max(0, base);
            case BALANCED:
                jitter = Jitter.of(n, 5, 1);
                break;
            default:
                jitter = 1 + Jitter.of(n, 5, 2);
        }
        return Math.max(1, (int) Math.round(Math.max(1, base + jitter) * Math.max(1.0, lagFactor)));
    }
}
