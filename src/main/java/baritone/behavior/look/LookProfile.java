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

package baritone.behavior.look;

import java.util.Locale;

/**
 * Rotation-smoothing profiles (plan S1-S15 / user decision 10: Legit-Smooth is the default, Fast is opt-in).
 *
 * <p>Pure data, no Minecraft dependencies, so the aim controller can be unit tested headless.
 */
public enum LookProfile {

    /**
     * Human-like: Fitts-law timed minimum-jerk saccades with a slow settle, decoupled (slower) pitch,
     * small over/undershoot, correlated micro-noise, path gaze and idle gaze. Default.
     */
    LEGIT_SMOOTH(2.0, 1.0, 3.0, 15.0, -0.06, 0.035, 1.30, 2.2, 0.12, true),

    /**
     * Middle ground: same model, shorter durations and smaller error terms.
     */
    BALANCED(1.5, 0.7, 3.0, 25.0, -0.04, 0.02, 1.15, 1.6, 0.06, true),

    /**
     * Legacy behaviour: the single critically-damped quantized spring (no saccades, no noise, no gaze).
     * Opt-in for speed.
     */
    FAST(0.0, 0.0, 3.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0, 0.0, 0.0, false);

    /** Fitts intercept, ticks. */
    public final double fittsA;
    /** Fitts slope, ticks per bit. */
    public final double fittsB;
    /** Fitts target width, degrees. */
    public final double fittsWidth;
    /** Angular distance (degrees) above which a ballistic saccade is planned instead of spring tracking. */
    public final double saccadeThreshold;
    /** Saccade end-point error as a fraction of the move: uniformly in [minError, maxError] (negative = undershoot). */
    public final double minError, maxError;
    /** Pitch duration multiplier (S8: pitch slower than yaw). */
    public final double pitchSlowdown;
    /** Settle/tracking spring time constant, ticks. */
    public final double settleTau;
    /** Peak amplitude of the correlated micro-noise, degrees (S5). */
    public final double noiseAmplitude;
    /** Whether path look-ahead gaze and idle gaze are allowed (S9, S10). */
    public final boolean gaze;

    LookProfile(double fittsA, double fittsB, double fittsWidth, double saccadeThreshold, double minError,
                double maxError, double pitchSlowdown, double settleTau, double noiseAmplitude, boolean gaze) {
        this.fittsA = fittsA;
        this.fittsB = fittsB;
        this.fittsWidth = fittsWidth;
        this.saccadeThreshold = saccadeThreshold;
        this.minError = minError;
        this.maxError = maxError;
        this.pitchSlowdown = pitchSlowdown;
        this.settleTau = settleTau;
        this.noiseAmplitude = noiseAmplitude;
        this.gaze = gaze;
    }

    /** True for the legacy spring-only profile. */
    public boolean isLegacy() {
        return this == FAST;
    }

    /**
     * Fitts-law-like movement time (S6): {@code T = a + b * log2(1 + D / W)} ticks, at least 2 ticks.
     *
     * @param distanceDeg angular distance of the move in degrees
     * @return the planned duration in ticks
     */
    public double fittsTicks(double distanceDeg) {
        double t = fittsA + fittsB * (Math.log(1.0 + Math.abs(distanceDeg) / fittsWidth) / Math.log(2.0));
        return Math.max(2.0, t);
    }

    /**
     * Lenient parse of the {@code lookProfile} setting: accepts {@code LEGIT_SMOOTH}, {@code legit-smooth},
     * {@code legit}, {@code smooth}, {@code balanced}, {@code fast}. Unknown values fall back to LEGIT_SMOOTH.
     */
    public static LookProfile parse(String value) {
        if (value == null) {
            return LEGIT_SMOOTH;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (v) {
            case "FAST":
                return FAST;
            case "BALANCED":
                return BALANCED;
            default:
                return LEGIT_SMOOTH;
        }
    }
}
