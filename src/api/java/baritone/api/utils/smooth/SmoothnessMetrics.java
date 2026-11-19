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

package baritone.api.utils.smooth;

/**
 * Plan S100: smoothness regression measures for a per-tick trace. Feed one sample per tick (yaw/pitch in
 * degrees, a horizontal speed and a bit-set of held inputs); read the maxima. Angular velocity, acceleration and
 * jerk are finite differences per tick; an input flip is a key going down then up (or up then down) within
 * {@link #FLIP_WINDOW} ticks, the "toggle spam" a human doesn't do. Pure and allocation-free.
 */
public final class SmoothnessMetrics {
    public static final int FLIP_WINDOW = 3;

    private int samples;
    private double prevYaw, prevPitch;
    private double prevVel, prevAcc;
    private double prevSpeed;
    private int prevInputs;
    private final int[] lastChange = new int[32];
    private final int[] lastState = new int[32];

    private double maxVelocity, maxAcceleration, maxJerk, maxSpeedJump;
    private int flips;
    private int snaps;
    private double snapThreshold = 60;

    /** Angular velocity (deg/tick) at or above this counts as a snap. */
    public SmoothnessMetrics snapThreshold(double degPerTick) {
        snapThreshold = degPerTick;
        return this;
    }

    public void sample(double yaw, double pitch, double speed, int inputs) {
        if (samples > 0) {
            double dy = wrap(yaw - prevYaw);
            double dp = pitch - prevPitch;
            double vel = Math.sqrt(dy * dy + dp * dp);
            if (vel > maxVelocity) maxVelocity = vel;
            if (vel >= snapThreshold) snaps++;
            if (samples > 1) {
                double acc = vel - prevVel;
                if (Math.abs(acc) > maxAcceleration) maxAcceleration = Math.abs(acc);
                if (samples > 2) {
                    double jerk = acc - prevAcc;
                    if (Math.abs(jerk) > maxJerk) maxJerk = Math.abs(jerk);
                }
                prevAcc = acc;
            }
            prevVel = vel;
            double jump = Math.abs(speed - prevSpeed);
            if (jump > maxSpeedJump) maxSpeedJump = jump;
            int changed = inputs ^ prevInputs;
            for (int b = 0; b < 32; b++) {
                if ((changed >> b & 1) == 0) continue;
                int state = inputs >> b & 1;
                if (lastChange[b] > 0 && samples - lastChange[b] <= FLIP_WINDOW && lastState[b] != state) flips++;
                lastChange[b] = samples;
                lastState[b] = state;
            }
        }
        prevYaw = yaw;
        prevPitch = pitch;
        prevSpeed = speed;
        prevInputs = inputs;
        samples++;
    }

    public static double wrap(double d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    public int samples() {
        return samples;
    }

    public double maxVelocity() {
        return maxVelocity;
    }

    public double maxAcceleration() {
        return maxAcceleration;
    }

    public double maxJerk() {
        return maxJerk;
    }

    public double maxSpeedJump() {
        return maxSpeedJump;
    }

    public int inputFlips() {
        return flips;
    }

    public int snaps() {
        return snaps;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "ticks=%d vel=%.1f acc=%.1f jerk=%.1f speedJump=%.3f flips=%d snaps=%d",
                samples, maxVelocity, maxAcceleration, maxJerk, maxSpeedJump, flips, snaps);
    }
}
