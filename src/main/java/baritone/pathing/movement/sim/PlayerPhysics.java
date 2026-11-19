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

/**
 * Pure-Java vanilla Minecraft player physics constants and helper formulas.
 * Completely headless (no Minecraft client or registry bootstrap required),
 * safe for both A* cost calculations and JUnit verification.
 */
public final class PlayerPhysics {

    // Vanilla LivingEntity / Player movement constants
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_DRAG = 0.91;
    public static final double BASE_GROUND_SLIPPERINESS = 0.6;
    public static final double ICE_SLIPPERINESS = 0.98;
    public static final double BLUE_ICE_SLIPPERINESS = 0.989;
    public static final double SLIME_SLIPPERINESS = 0.8;

    public static final double BASE_MOVEMENT_SPEED = 0.1;
    public static final double SPRINT_SPEED_MULTIPLIER = 1.3;
    public static final double SNEAK_INPUT_MULTIPLIER = 0.3;
    public static final double WALK_INPUT_MULTIPLIER = 0.98;
    public static final double AIR_ACCELERATION = 0.02;
    public static final double SPRINT_AIR_ACCELERATION = 0.026;

    public static final double BASE_JUMP_VELOCITY = 0.42;
    public static final double JUMP_BOOST_PER_LEVEL = 0.1;
    public static final double SPRINT_JUMP_BOOST = 0.2;

    public static final double VELOCITY_ZERO_THRESHOLD = 0.003;
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;

    // Slow block speed factors (vanilla Block.getSpeedFactor)
    public static final double NORMAL_SPEED_FACTOR = 1.0;
    public static final double SOUL_SAND_SPEED_FACTOR = 0.4;
    public static final double HONEY_SPEED_FACTOR = 0.4;
    public static final double COBWEB_XZ_MULTIPLIER = 0.25;
    public static final double COBWEB_Y_MULTIPLIER = 0.05;
    public static final double POWDER_SNOW_XZ_MULTIPLIER = 0.9;

    // Water physics constants
    public static final double WATER_DRAG = 0.8;
    public static final double WATER_SPRINT_DRAG = 0.9;
    public static final double WATER_GRAVITY = 0.02;

    private PlayerPhysics() {
    }

    /**
     * Effective ground movement speed given base attribute (0.1 default),
     * Speed potion amplifier (0 = none, 1 = Speed I, ...),
     * Slowness potion amplifier (0 = none, 1 = Slowness I, ...),
     * and whether sprinting.
     */
    public static double effectiveGroundSpeed(double baseAttributeSpeed, int speedLevel, int slownessLevel, boolean sprinting) {
        double speed = baseAttributeSpeed;
        if (speedLevel > 0) {
            speed *= (1.0 + 0.2 * speedLevel);
        }
        if (slownessLevel > 0) {
            speed *= Math.max(0.0, 1.0 - 0.15 * slownessLevel);
        }
        if (sprinting) {
            speed *= SPRINT_SPEED_MULTIPLIER;
        }
        return speed;
    }

    /**
     * Vertical jump impulse for a given Jump Boost level (0 = none, 1 = Jump Boost I, ...).
     */
    public static double jumpVelocity(int jumpBoostLevel) {
        return BASE_JUMP_VELOCITY + Math.max(0, jumpBoostLevel) * JUMP_BOOST_PER_LEVEL;
    }

    /**
     * Vanilla ground acceleration factor: speed * (0.21600002 / slipperiness^3).
     */
    public static double groundAcceleration(double effectiveSpeed, double slipperiness) {
        double s3 = slipperiness * slipperiness * slipperiness;
        return effectiveSpeed * (0.21600002 / s3);
    }

    /**
     * Horizontal drag factor applied at the end of a tick.
     */
    public static double horizontalDrag(boolean onGround, double slipperiness) {
        return onGround ? slipperiness * AIR_DRAG : AIR_DRAG;
    }

    /**
     * Friction cost multiplier for A* pathing on different block slipperiness/speed factors.
     * Normal ground (slip=0.6, factor=1.0) returns 1.0.
     * Ice slightly lowers straight travel cost (faster steady-state sprint-jump) while adding
     * a small control overhead; soul sand / honey / cobweb increase cost proportionally.
     */
    public static double surfaceCostMultiplier(double slipperiness, double speedFactor, boolean inCobweb, boolean inPowderSnow) {
        if (inCobweb) {
            return 4.5;
        }
        if (inPowderSnow) {
            return 2.6;
        }
        double mult = 1.0;
        if (speedFactor < 0.99) {
            mult /= Math.max(0.25, speedFactor);
        }
        if (slipperiness >= BLUE_ICE_SLIPPERINESS - 1e-4) {
            // Blue ice: faster straight line, slightly harder to stop/turn
            mult *= 0.88;
        } else if (slipperiness >= ICE_SLIPPERINESS - 1e-4) {
            mult *= 0.92;
        } else if (slipperiness >= SLIME_SLIPPERINESS - 1e-4) {
            // Slime slows walking slightly and causes bounce risk
            mult *= 1.35;
        }
        return mult;
    }

    /**
     * Computes the maximum vanilla jump height (in blocks) for a given Jump Boost level.
     */
    public static double maxJumpHeight(int jumpBoostLevel) {
        double y = 0.0;
        double vy = jumpVelocity(jumpBoostLevel);
        double peak = 0.0;
        for (int t = 0; t < 40; t++) {
            y += vy;
            if (y > peak) {
                peak = y;
            }
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            if (vy <= 0 && y < peak) {
                break;
            }
        }
        return peak;
    }

    /**
     * Computes the analytical geometric-series stopping distance when releasing all movement
     * inputs on a surface with the given slipperiness.
     */
    public static double stoppingDistance(double currentSpeed, boolean onGround, double slipperiness) {
        double v = Math.abs(currentSpeed);
        if (v < VELOCITY_ZERO_THRESHOLD) {
            return 0.0;
        }
        double drag = horizontalDrag(onGround, slipperiness);
        double dist = 0.0;
        for (int t = 0; t < 80 && v >= VELOCITY_ZERO_THRESHOLD; t++) {
            dist += v;
            v *= drag;
        }
        return dist;
    }
}
