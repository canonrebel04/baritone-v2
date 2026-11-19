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
 * One-axis jerk-limited (minimum-jerk, quintic) trajectory (plan S1 / S3).
 *
 * <p>Plans a fifth-order polynomial from an arbitrary start state (position, velocity, acceleration) to a
 * rest state (end position, zero velocity, zero acceleration) over {@code T} ticks. With zero start
 * velocity/acceleration it reduces to the classic minimum-jerk profile {@code 10s^3 - 15s^4 + 6s^5}.
 * Starting from the current velocity/acceleration is what makes re-planning on a target switch C2
 * continuous (S3: no velocity or acceleration jump, so no snap).
 *
 * <p>Allocation free; all state is primitive and the instance is re-used by {@link #plan}.
 */
public final class MinJerkTrajectory {

    /**
     * Peak velocity of a rest-to-rest minimum-jerk move is {@code 1.875 * D / T}.
     */
    public static final double PEAK_VELOCITY_FACTOR = 1.875;

    // P(s) = c0 + c1 s + c2 s^2 + c3 s^3 + c4 s^4 + c5 s^5, s = t / T in [0, 1]
    private double c0, c1, c2, c3, c4, c5;
    private double duration;
    private double end;
    private boolean active;

    /**
     * Plans a new trajectory.
     *
     * @param p0       start position
     * @param v0       start velocity (units per tick)
     * @param a0       start acceleration (units per tick^2)
     * @param p1       end position (reached at rest)
     * @param duration duration in ticks (clamped to at least 1)
     */
    public void plan(double p0, double v0, double a0, double p1, double duration) {
        double T = Math.max(1.0, duration);
        this.duration = T;
        this.end = p1;
        this.c0 = p0;
        this.c1 = v0 * T;
        this.c2 = a0 * T * T / 2.0;
        double h = p1 - c0 - c1 - c2;
        double d = -(c1 + 2.0 * c2);
        double e = -2.0 * c2;
        this.c3 = 10.0 * h - 4.0 * d + e / 2.0;
        this.c4 = -15.0 * h + 7.0 * d - e;
        this.c5 = 6.0 * h - 3.0 * d + e / 2.0;
        this.active = true;
    }

    public boolean isActive() {
        return active;
    }

    public void cancel() {
        active = false;
    }

    public double duration() {
        return duration;
    }

    public double end() {
        return end;
    }

    /** Position at time {@code t} ticks after planning. */
    public double position(double t) {
        if (t >= duration) {
            return end;
        }
        double s = Math.max(0.0, t / duration);
        return c0 + s * (c1 + s * (c2 + s * (c3 + s * (c4 + s * c5))));
    }

    /** Velocity (units per tick) at time {@code t}. */
    public double velocity(double t) {
        if (t >= duration) {
            return 0.0;
        }
        double s = Math.max(0.0, t / duration);
        double dp = c1 + s * (2.0 * c2 + s * (3.0 * c3 + s * (4.0 * c4 + s * 5.0 * c5)));
        return dp / duration;
    }

    /** Acceleration (units per tick^2) at time {@code t}. */
    public double acceleration(double t) {
        if (t >= duration) {
            return 0.0;
        }
        double s = Math.max(0.0, t / duration);
        double ddp = 2.0 * c2 + s * (6.0 * c3 + s * (12.0 * c4 + s * 20.0 * c5));
        return ddp / (duration * duration);
    }

    /** Largest |acceleration| over the planned trajectory, sampled at 1/16 of its duration. */
    public double peakAcceleration() {
        double peak = 0.0;
        for (int i = 0; i <= 16; i++) {
            double t = duration * i / 16.0;
            double s = Math.min(1.0, t / duration);
            double ddp = 2.0 * c2 + s * (6.0 * c3 + s * (12.0 * c4 + s * 20.0 * c5));
            peak = Math.max(peak, Math.abs(ddp / (duration * duration)));
        }
        return peak;
    }

    /**
     * Shortest duration (ticks) for which a rest-to-rest move of {@code distance} keeps its peak velocity at
     * or below {@code maxVelocity}.
     */
    public static double minDurationForPeak(double distance, double maxVelocity) {
        if (maxVelocity <= 0.0) {
            return 1.0;
        }
        return PEAK_VELOCITY_FACTOR * Math.abs(distance) / maxVelocity;
    }
}
