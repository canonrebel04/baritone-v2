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
 * Two-axis human-like aim controller (plan S1, S3, S4, S6, S7, S8).
 *
 * <ul>
 *     <li><b>S1</b> large turns follow a jerk-limited quintic ({@link MinJerkTrajectory}) instead of a spring.</li>
 *     <li><b>S3</b> every re-plan starts from the current position, velocity and acceleration, so switching or
 *     moving the target never produces a velocity jump.</li>
 *     <li><b>S4</b> saccade + settle: a fast ballistic move to near the target, then a slow critically-damped
 *     correction onto it.</li>
 *     <li><b>S6</b> the saccade duration scales with angular distance (Fitts-law, {@link LookProfile#fittsTicks}).</li>
 *     <li><b>S7</b> the ballistic phase lands slightly short or long by a fraction chosen deterministically from the
 *     target (same target, same error), and the settle spring is critically damped so it never oscillates.</li>
 *     <li><b>S8</b> pitch runs on a longer duration than yaw, so big diagonal moves curve instead of sweeping in a
 *     perfectly straight "robot" line.</li>
 * </ul>
 *
 * <p>Pure Java and allocation free per tick. Angles are continuous degrees (yaw is unwrapped, like the player's
 * own yaw); the caller quantizes the output to the mouse GCD grid.
 */
public final class SaccadeAimController {

    private final Axis yaw = new Axis();
    private final Axis pitch = new Axis();
    private final long seed;

    private boolean initialized;
    private double goalYaw, goalPitch;
    private boolean saccading;
    private double snapEpsilon = 0.075;

    public SaccadeAimController(long seed) {
        this.seed = seed;
    }

    /** Resets the state to rest at the given rotation. */
    public void reset(double yawDeg, double pitchDeg) {
        yaw.reset(yawDeg);
        pitch.reset(pitchDeg);
        goalYaw = yawDeg;
        goalPitch = pitchDeg;
        saccading = false;
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void invalidate() {
        initialized = false;
    }

    /** Below this distance and speed the settle phase snaps onto the target and stops (default 0.075 deg). */
    public void setSnapEpsilon(double snapEpsilon) {
        this.snapEpsilon = Math.max(1e-6, snapEpsilon);
    }

    public double yaw() {
        return yaw.pos;
    }

    public double pitch() {
        return pitch.pos;
    }

    public double yawVelocity() {
        return yaw.vel;
    }

    public double pitchVelocity() {
        return pitch.vel;
    }

    public boolean isSaccading() {
        return saccading;
    }

    /**
     * Advances the controller by one tick toward the target.
     *
     * @param targetYaw   target yaw in degrees (any wrap; unwrapped relative to the current yaw)
     * @param targetPitch target pitch in degrees
     * @param profile     the active profile (must not be {@link LookProfile#FAST}, which uses the legacy spring)
     * @param maxTurn     per-tick turn-rate cap in degrees
     * @param precise     block-interaction aim: no over/undershoot, shorter durations, tighter settle
     */
    public void step(double targetYaw, double targetPitch, LookProfile profile, double maxTurn, boolean precise) {
        if (!initialized) {
            reset(targetYaw, targetPitch);
            return;
        }
        final double tYaw = yaw.pos + wrapDegrees(targetYaw - yaw.pos);
        final double tPitch = clamp(targetPitch, -90.0, 90.0);
        final double maxVel = Math.max(0.01, maxTurn);

        if (saccading) {
            final double shift = Math.hypot(tYaw - goalYaw, tPitch - goalPitch);
            if (shift > 1e-6) {
                final double remaining = Math.hypot(tYaw - yaw.pos, tPitch - pitch.pos);
                if (shift > Math.max(profile.saccadeThreshold * 0.5, remaining * 0.5)) {
                    // A genuinely new target: fresh Fitts-timed saccade from the current motion (S3)
                    planSaccade(tYaw, tPitch, profile, maxVel, precise);
                } else {
                    // The same target drifting (entity moving, path re-submitting): slide the end point by the
                    // same amount and keep the remaining time, re-planned from the current state.
                    yaw.retarget(yaw.aim + (tYaw - goalYaw), 2.0);
                    pitch.retarget(clamp(pitch.aim + (tPitch - goalPitch), -90.0, 90.0), 2.0);
                    goalYaw = tYaw;
                    goalPitch = tPitch;
                }
            }
        } else {
            goalYaw = tYaw;
            goalPitch = tPitch;
            final double distance = Math.hypot(tYaw - yaw.pos, tPitch - pitch.pos);
            if (distance > profile.saccadeThreshold) {
                planSaccade(tYaw, tPitch, profile, maxVel, precise);
            }
        }

        final double tau = Math.max(1.0, precise ? profile.settleTau * 0.6 : profile.settleTau);
        yaw.advance(goalYaw, tau, maxVel, snapEpsilon);
        pitch.advance(goalPitch, tau, maxVel, snapEpsilon);
        if (pitch.pos > 90.0) {
            pitch.pos = 90.0;
            pitch.vel = Math.min(0.0, pitch.vel);
        } else if (pitch.pos < -90.0) {
            pitch.pos = -90.0;
            pitch.vel = Math.max(0.0, pitch.vel);
        }
        saccading = yaw.traj.isActive() || pitch.traj.isActive();
    }

    private void planSaccade(double tYaw, double tPitch, LookProfile profile, double maxVel, boolean precise) {
        final double dYaw = tYaw - yaw.pos;
        final double dPitch = tPitch - pitch.pos;
        final double distance = Math.hypot(dYaw, dPitch);

        double duration = profile.fittsTicks(distance) * (precise ? 0.75 : 1.0);
        final double yawDuration = Math.max(duration, MinJerkTrajectory.minDurationForPeak(dYaw, maxVel));
        final double pitchDuration = Math.max(duration * profile.pitchSlowdown,
                MinJerkTrajectory.minDurationForPeak(dPitch, maxVel));

        final double error = precise ? 0.0 : errorFraction(tYaw, tPitch, profile);
        final double yawAim = tYaw + dYaw * error;
        final double pitchAim = clamp(tPitch + dPitch * error, -90.0, 90.0);
        // Snapshot the start state: re-planning below must always start from the same state
        final double yp = yaw.pos, yv = yaw.vel, ya = yaw.acc;
        final double pp = pitch.pos, pv = pitch.vel, pa = pitch.acc;
        final double maxAcc = maxAcceleration(maxVel);
        double scale = 1.0;
        for (int i = 0; ; i++) {
            yaw.restore(yp, yv, ya);
            pitch.restore(pp, pv, pa);
            yaw.plan(yawAim, yawDuration * scale);
            pitch.plan(pitchAim, pitchDuration * scale);
            // Acceleration cap: a re-plan from a fast-moving state onto a nearby target would otherwise need a
            // sharp brake (a visible velocity kink), so stretch the duration until the brake is human-plausible
            if (i >= 12 || Math.max(yaw.traj.peakAcceleration(), pitch.traj.peakAcceleration()) <= maxAcc) {
                break;
            }
            scale *= 1.2;
        }
        goalYaw = tYaw;
        goalPitch = tPitch;
        saccading = true;
    }

    /** Per-tick acceleration cap used when planning a saccade, in degrees/tick^2. */
    static double maxAcceleration(double maxVel) {
        return Math.max(2.0, 0.2 * maxVel);
    }

    /**
     * Deterministic per-target end-point error (S7): the same target (to within 2 degrees) always yields the same
     * over/undershoot fraction for a given controller seed.
     */
    double errorFraction(double tYaw, double tPitch, LookProfile profile) {
        long key = seed;
        key = key * 31 + Math.round(wrapDegrees(tYaw) / 2.0);
        key = key * 31 + Math.round(tPitch / 2.0);
        double u = (mix(key) >>> 11) * 0x1.0p-53;
        return profile.minError + (profile.maxError - profile.minError) * u;
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static final class Axis {
        double pos, vel, acc;
        double aim;
        double t;
        final MinJerkTrajectory traj = new MinJerkTrajectory();

        void reset(double p) {
            pos = p;
            vel = 0;
            acc = 0;
            aim = p;
            t = 0;
            traj.cancel();
        }

        void restore(double p, double v, double a) {
            pos = p;
            vel = v;
            acc = a;
        }

        void plan(double aimPos, double duration) {
            aim = aimPos;
            t = 0;
            traj.plan(pos, vel, acc, aimPos, duration);
        }

        void retarget(double aimPos, double minRemaining) {
            if (!traj.isActive()) {
                return;
            }
            double remaining = Math.max(minRemaining, traj.duration() - t);
            plan(aimPos, remaining);
        }

        void advance(double goal, double tau, double maxVel, double snapEpsilon) {
            if (traj.isActive()) {
                t += 1.0;
                double next = traj.position(t);
                double nextVel = traj.velocity(t);
                acc = traj.acceleration(t);
                double step = next - pos;
                if (step > maxVel) {
                    step = maxVel;
                } else if (step < -maxVel) {
                    step = -maxVel;
                }
                pos += step;
                vel = Math.abs(nextVel) > maxVel ? Math.copySign(maxVel, nextVel) : nextVel;
                if (t >= traj.duration()) {
                    traj.cancel();
                    vel = 0;
                    acc = 0;
                }
                return;
            }
            // Settle / tracking: exact one-tick solution of a critically-damped spring (stable for any tau and,
            // from rest, never overshoots - so the settle phase can't oscillate)
            final double w = 1.0 / tau;
            final double x0 = pos - goal;
            final double decay = Math.exp(-w);
            final double c = vel + w * x0;
            double newVel = (vel - w * c) * decay;
            double newX = (x0 + c) * decay;
            if (newVel > maxVel) {
                newVel = maxVel;
            } else if (newVel < -maxVel) {
                newVel = -maxVel;
            }
            double step = (goal + newX) - pos;
            if (step > maxVel) {
                step = maxVel;
            } else if (step < -maxVel) {
                step = -maxVel;
            }
            acc = newVel - vel;
            vel = newVel;
            pos += step;
            if (Math.abs(goal - pos) < snapEpsilon && Math.abs(vel) < snapEpsilon) {
                pos = goal;
                vel = 0;
                acc = 0;
            }
        }
    }
}
