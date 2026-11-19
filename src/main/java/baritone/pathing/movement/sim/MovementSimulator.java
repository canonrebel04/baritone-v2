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
 * Pure-Java headless tick-by-tick Minecraft player movement physics simulator.
 * Used by:
 * - A* cost calibration & simulated parkour/ascend/descend costs
 * - Execution precision (brake-to-stop prediction, tick-exact jump timing, landing snap)
 * - Headless JUnit tests verifying vanilla jump distances and cost accuracy
 */
public final class MovementSimulator {

    public static final class SimState {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public boolean onGround;
        public boolean sprinting;

        public SimState(double x, double y, double z, double vx, double vy, double vz, boolean onGround, boolean sprinting) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.onGround = onGround;
            this.sprinting = sprinting;
        }

        public SimState copy() {
            return new SimState(x, y, z, vx, vy, vz, onGround, sprinting);
        }

        public double horizontalSpeed() {
            return Math.hypot(vx, vz);
        }
    }

    public static final class SimInput {
        public final float yawDegrees;
        public final double forward; // -1, 0, +1
        public final double strafe;  // -1, 0, +1
        public final boolean sprint;
        public final boolean sneak;
        public final boolean jump;

        public SimInput(float yawDegrees, double forward, double strafe, boolean sprint, boolean sneak, boolean jump) {
            this.yawDegrees = yawDegrees;
            this.forward = forward;
            this.strafe = strafe;
            this.sprint = sprint;
            this.sneak = sneak;
            this.jump = jump;
        }

        public static SimInput forward(float yawDegrees, boolean sprint, boolean jump) {
            return new SimInput(yawDegrees, 1.0, 0.0, sprint, false, jump);
        }

        public static SimInput idle(float yawDegrees) {
            return new SimInput(yawDegrees, 0.0, 0.0, false, false, false);
        }
    }

    public static final class SimConfig {
        public final double baseSpeed;
        public final int speedLevel;
        public final int slownessLevel;
        public final int jumpBoostLevel;
        public final double slipperiness;
        public final double speedFactor;
        public final boolean inWater;
        public final boolean headHitterCeiling;

        public SimConfig(double baseSpeed, int speedLevel, int slownessLevel, int jumpBoostLevel,
                         double slipperiness, double speedFactor, boolean inWater, boolean headHitterCeiling) {
            this.baseSpeed = baseSpeed;
            this.speedLevel = speedLevel;
            this.slownessLevel = slownessLevel;
            this.jumpBoostLevel = jumpBoostLevel;
            this.slipperiness = slipperiness;
            this.speedFactor = speedFactor;
            this.inWater = inWater;
            this.headHitterCeiling = headHitterCeiling;
        }

        public static SimConfig vanillaDefault() {
            return new SimConfig(
                    PlayerPhysics.BASE_MOVEMENT_SPEED,
                    0, 0, 0,
                    PlayerPhysics.BASE_GROUND_SLIPPERINESS,
                    PlayerPhysics.NORMAL_SPEED_FACTOR,
                    false,
                    false
            );
        }
    }

    public static final class JumpResult {
        public final boolean landed;
        public final int ticks;
        public final double horizontalDistance;
        public final double finalX;
        public final double finalY;
        public final double finalZ;
        public final double maxY;

        public JumpResult(boolean landed, int ticks, double horizontalDistance, double finalX, double finalY, double finalZ, double maxY) {
            this.landed = landed;
            this.ticks = ticks;
            this.horizontalDistance = horizontalDistance;
            this.finalX = finalX;
            this.finalY = finalY;
            this.finalZ = finalZ;
            this.maxY = maxY;
        }
    }

    private MovementSimulator() {
    }

    /**
     * Advances {@code state} by exactly one Minecraft game tick (50ms) in-place.
     * Models vanilla LivingEntity.travel + jumpFromGround + friction/drag.
     */
    public static void step(SimState state, SimInput input, SimConfig config) {
        if (Math.abs(state.vx) < PlayerPhysics.VELOCITY_ZERO_THRESHOLD) state.vx = 0.0;
        if (Math.abs(state.vy) < PlayerPhysics.VELOCITY_ZERO_THRESHOLD) state.vy = 0.0;
        if (Math.abs(state.vz) < PlayerPhysics.VELOCITY_ZERO_THRESHOLD) state.vz = 0.0;

        boolean sprinting = input.sprint && !input.sneak && input.forward > 0.0;
        state.sprinting = sprinting;

        // 1. Jump impulse if grounded and jump pressed
        if (input.jump && state.onGround) {
            state.vy = PlayerPhysics.jumpVelocity(config.jumpBoostLevel);
            if (sprinting) {
                double yawRad = Math.toRadians(input.yawDegrees);
                state.vx -= Math.sin(yawRad) * PlayerPhysics.SPRINT_JUMP_BOOST;
                state.vz += Math.cos(yawRad) * PlayerPhysics.SPRINT_JUMP_BOOST;
            }
            state.onGround = false;
        }

        // 2. Compute input impulse scaled by walk/sneak multipliers
        double fwd = input.forward * PlayerPhysics.WALK_INPUT_MULTIPLIER;
        double str = input.strafe * PlayerPhysics.WALK_INPUT_MULTIPLIER;
        if (input.sneak) {
            fwd *= PlayerPhysics.SNEAK_INPUT_MULTIPLIER;
            str *= PlayerPhysics.SNEAK_INPUT_MULTIPLIER;
        }

        if (config.inWater) {
            double accel = sprinting ? 0.02 : 0.02;
            applyRelativeMove(state, str, fwd, accel, input.yawDegrees);
            state.x += state.vx;
            state.y += state.vy;
            state.z += state.vz;
            double drag = sprinting ? PlayerPhysics.WATER_SPRINT_DRAG : PlayerPhysics.WATER_DRAG;
            state.vx *= drag;
            state.vy = (state.vy * 0.8) - PlayerPhysics.WATER_GRAVITY;
            state.vz *= drag;
            return;
        }

        // 3. Horizontal acceleration (ground vs air)
        double effSpeed = PlayerPhysics.effectiveGroundSpeed(
                config.baseSpeed, config.speedLevel, config.slownessLevel, sprinting);
        double accel;
        if (state.onGround) {
            accel = PlayerPhysics.groundAcceleration(effSpeed, config.slipperiness);
        } else {
            accel = sprinting ? PlayerPhysics.SPRINT_AIR_ACCELERATION : PlayerPhysics.AIR_ACCELERATION;
        }
        applyRelativeMove(state, str, fwd, accel, input.yawDegrees);

        // 4. Integrate position
        state.x += state.vx;
        state.y += state.vy;
        state.z += state.vz;

        // Head-hitter ceiling clamp (2-block ceiling clips upward velocity)
        if (config.headHitterCeiling && state.vy > 0.0 && state.y > 0.2001) {
            state.y = 0.2;
            state.vy = 0.0;
        }

        // 5. Apply block speed factor (e.g., soul sand / honey) when on ground
        if (state.onGround && config.speedFactor < 0.999) {
            state.vx *= config.speedFactor;
            state.vz *= config.speedFactor;
        }

        // 6. Apply gravity and drag
        double hDrag = PlayerPhysics.horizontalDrag(state.onGround, config.slipperiness);
        state.vx *= hDrag;
        state.vz *= hDrag;
        if (!state.onGround) {
            state.vy = (state.vy - PlayerPhysics.GRAVITY) * PlayerPhysics.VERTICAL_DRAG;
        }
    }

    private static void applyRelativeMove(SimState state, double strafe, double forward, double accel, float yawDegrees) {
        double magSq = strafe * strafe + forward * forward;
        if (magSq < 1.0e-7) {
            return;
        }
        double scale = magSq > 1.0 ? accel / Math.sqrt(magSq) : accel;
        double s = strafe * scale;
        double f = forward * scale;
        double yawRad = Math.toRadians(yawDegrees);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        state.vx += s * cos - f * sin;
        state.vz += f * cos + s * sin;
    }

    /**
     * Simulates a forward jump from {@code initialState} along {@code yawDegrees}
     * until the player descends to or below {@code targetY}.
     */
    public static JumpResult simulateJump(SimState initialState, float yawDegrees, boolean sprint, double targetY, SimConfig config, int maxTicks) {
        SimState s = initialState.copy();
        double startX = s.x;
        double startZ = s.z;
        double maxY = s.y;
        boolean jumped = false;

        for (int t = 1; t <= maxTicks; t++) {
            double prevY = s.y;
            SimInput input = SimInput.forward(yawDegrees, sprint, !jumped && s.onGround);
            if (!jumped && s.onGround) {
                jumped = true;
            }
            step(s, input, config);
            if (s.y > maxY) {
                maxY = s.y;
            }
            if (jumped && s.vy <= 0.0 && prevY >= targetY && s.y <= targetY) {
                // Interpolate landing position within the tick
                double denom = prevY - s.y;
                double frac = denom > 1e-9 ? (prevY - targetY) / denom : 1.0;
                double landX = (s.x - s.vx) + s.vx * frac;
                double landZ = (s.z - s.vz) + s.vz * frac;
                double dist = Math.hypot(landX - startX, landZ - startZ);
                return new JumpResult(true, t, dist, landX, targetY, landZ, maxY);
            }
        }
        double dist = Math.hypot(s.x - startX, s.z - startZ);
        return new JumpResult(false, maxTicks, dist, s.x, s.y, s.z, maxY);
    }

    /**
     * Simulates running straight for {@code runupTicks} ticks, then jumping to {@code targetDeltaY}.
     * Returns the jump result (including horizontal reach in blocks from the jump takeoff point).
     */
    public static JumpResult simulateRunAndJump(int runupTicks, double targetDeltaY, boolean sprint, SimConfig config) {
        SimState s = new SimState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true, sprint);
        float yaw = 0.0f; // +Z axis
        for (int t = 0; t < runupTicks; t++) {
            step(s, SimInput.forward(yaw, sprint, false), config);
        }
        return simulateJump(s, yaw, sprint, targetDeltaY, config, 60);
    }

    /**
     * Simulates straight flat travel of {@code distanceBlocks} from rest (or with initial speed)
     * and returns the number of ticks required.
     */
    public static double simulateFlatTravelTicks(double distanceBlocks, double initialSpeed, boolean sprint, boolean sneak, SimConfig config) {
        SimState s = new SimState(0.0, 0.0, 0.0, 0.0, 0.0, initialSpeed, true, sprint);
        float yaw = 0.0f;
        for (int t = 1; t <= 400; t++) {
            double prevZ = s.z;
            step(s, new SimInput(yaw, 1.0, 0.0, sprint, sneak, false), config);
            if (s.z >= distanceBlocks) {
                double stepDist = s.z - prevZ;
                double frac = stepDist > 1e-9 ? (distanceBlocks - prevZ) / stepDist : 1.0;
                return (t - 1) + frac;
            }
        }
        return 400.0;
    }

    /**
     * Predicts whether the player's hitbox center will cross past the given block edge
     * on the next physics tick if continuing with current velocity and forward input.
     * Used for tick-exact jump timing (Enhancement #6).
     */
    public static boolean willLeaveBlockNextTick(double posX, double posZ, double vx, double vz,
                                                 int blockX, int blockZ, int dirX, int dirZ,
                                                 double edgeMargin) {
        double nextX = posX + vx;
        double nextZ = posZ + vz;
        double relX = nextX - (blockX + 0.5);
        double relZ = nextZ - (blockZ + 0.5);
        double forwardNext = relX * dirX + relZ * dirZ;
        // Player's hitbox edge extends 0.3 blocks; block edge is at +0.5.
        // Leaving the block means forwardNext exceeds (0.5 - edgeMargin).
        return forwardNext >= (0.5 - edgeMargin);
    }

    /**
     * Predicts stopping distance in blocks from current horizontal speed and surface slipperiness.
     * Used for brake-to-stop prediction (Enhancement #4).
     */
    public static double predictStoppingDistance(double vx, double vz, boolean onGround, double slipperiness) {
        return PlayerPhysics.stoppingDistance(Math.hypot(vx, vz), onGround, slipperiness);
    }

    /**
     * Estimates the maximum horizontal gap (center-to-center distance in blocks, including the
     * 0.6 player hitbox allowance at takeoff and landing) achievable for a given deltaY and config.
     */
    public static double maxReachableJumpSpan(int runupTicks, double targetDeltaY, boolean sprint, SimConfig config) {
        JumpResult res = simulateRunAndJump(runupTicks, targetDeltaY, sprint, config);
        if (!res.landed) {
            return 0.0;
        }
        // On takeoff the player can stand up to +0.3 past the block edge, and on landing
        // the hitbox catches the block at -0.3 before the edge -> +0.6 total span allowance
        // (conservatively use +0.52 for anticheat safety).
        return res.horizontalDistance + 0.52;
    }

    /**
     * Simulated tick cost for a parkour jump of horizontal distance {@code gapBlocks}
     * and height change {@code deltaY}, accounting for run-up and air time.
     */
    public static double simulatedParkourCost(double gapBlocks, int deltaY, boolean sprint, SimConfig config) {
        int runup = gapBlocks >= 3.5 || deltaY > 0 ? 5 : 2;
        JumpResult res = simulateRunAndJump(runup, deltaY, sprint, config);
        if (!res.landed) {
            return 1000000.0;
        }
        // Base ticks: air time + small centering/run-up overhead
        return res.ticks + (gapBlocks >= 3.5 ? 2.5 : 1.5);
    }
}
