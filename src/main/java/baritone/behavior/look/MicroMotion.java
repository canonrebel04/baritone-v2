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

/**
 * Bounded, correlated offsets layered on top of an aim target (plan S5, S10, S13).
 *
 * <ul>
 *     <li><b>S5</b> {@link #noiseYaw}/{@link #noisePitch}: a resting-hand micro drift, never white jitter.</li>
 *     <li><b>S10</b> {@link #idleYaw}/{@link #idlePitch}: a slow look-around drift for when the bot is in control
 *     but has nothing to look at, so the camera is never frozen.</li>
 *     <li><b>S13</b> the micro-noise phase advances with distance walked as well as time, so the wobble is
 *     step-synced (faster while sprinting, calm while standing) instead of a fixed-rate shake.</li>
 * </ul>
 *
 * <p>Pure Java and allocation free per tick.
 */
public final class MicroMotion {

    /** Noise lattice cells per tick while standing still (~1 wobble per 1.25 s). */
    static final double BASE_RATE = 0.04;
    /** Extra lattice cells per block walked (S13 stride coupling). */
    static final double STRIDE_RATE = 0.35;
    /** Idle drift lattice cells per tick (~1 look-around per 6 s). */
    static final double IDLE_RATE = 0.008;
    /** Idle drift amplitude, degrees. */
    public static final double IDLE_YAW_AMPLITUDE = 6.0;
    public static final double IDLE_PITCH_AMPLITUDE = 2.5;

    private final CorrelatedNoise yawNoise;
    private final CorrelatedNoise pitchNoise;
    private final CorrelatedNoise idleYawNoise;
    private final CorrelatedNoise idlePitchNoise;

    private double phase;
    private double idlePhase;

    public MicroMotion(long seed) {
        this.yawNoise = new CorrelatedNoise(seed);
        this.pitchNoise = new CorrelatedNoise(seed * 31 + 7);
        this.idleYawNoise = new CorrelatedNoise(seed * 131 + 11);
        this.idlePitchNoise = new CorrelatedNoise(seed * 257 + 13);
    }

    /**
     * Advances the noise clocks by one tick.
     *
     * @param walkedBlocks horizontal distance walked this tick (blocks), for S13 stride coupling
     */
    public void tick(double walkedBlocks) {
        phase += BASE_RATE + STRIDE_RATE * Math.min(1.0, Math.max(0.0, walkedBlocks));
        idlePhase += IDLE_RATE;
    }

    public double phase() {
        return phase;
    }

    public double noiseYaw(double amplitude) {
        return yawNoise.sample(phase) * amplitude;
    }

    public double noisePitch(double amplitude) {
        // Hands wobble less vertically
        return pitchNoise.sample(phase + 17.3) * amplitude * 0.6;
    }

    public double idleYaw() {
        return idleYawNoise.sample(idlePhase) * IDLE_YAW_AMPLITUDE;
    }

    public double idlePitch() {
        return idlePitchNoise.sample(idlePhase + 5.1) * IDLE_PITCH_AMPLITUDE;
    }

    /**
     * S9 path gaze: the pitch (degrees, positive = down) at which an eye at {@code eyeY} looks at a ground point
     * {@code horizontalDistance} blocks ahead whose surface is at {@code groundY}. Clamped to a comfortable
     * {@code [-15, 35]} range.
     */
    public static double gazePitch(double eyeY, double groundY, double horizontalDistance) {
        double pitch = Math.toDegrees(Math.atan2(eyeY - groundY, Math.max(0.5, horizontalDistance)));
        return pitch < -15.0 ? -15.0 : (pitch > 35.0 ? 35.0 : pitch);
    }

    /**
     * S9/S13 look-ahead distance in blocks for a horizontal speed in blocks per tick: about 4 blocks walking,
     * 7 sprinting, clamped to {@code [3, 9]}.
     */
    public static double gazeDistance(double speedBlocksPerTick) {
        double d = 3.0 + 16.0 * Math.max(0.0, speedBlocksPerTick);
        return d < 3.0 ? 3.0 : (d > 9.0 ? 9.0 : d);
    }
}
