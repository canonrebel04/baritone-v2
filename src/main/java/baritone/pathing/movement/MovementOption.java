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

package baritone.pathing.movement;

import baritone.api.utils.input.Input;

import java.util.stream.Stream;

public record MovementOption(Input input1, Input input2, float motionX, float motionZ) {
    private static final float SPRINT_MULTIPLIER = 1.3f;
    private static final float INV_SQRT2 = 0.70710678f;

    public MovementOption(Input input1, float motionX, float motionZ) {
        this(input1, null, motionX, motionZ);
    }

    public void setInputs(MovementState movementState) {
        if (input1 != null) {
            movementState.setInput(input1, true);
        }
        if (input2 != null) {
            movementState.setInput(input2, true);
        }
    }

    public float distanceToSq(float otherX, float otherZ) {
        float dx = motionX() - otherX;
        float dz = motionZ() - otherZ;
        return dx * dx + dz * dz;
    }

    /**
     * Generates the 8 normalized WASD movement directions for a player whose forward unit vector
     * in Minecraft world coordinates is {@code (motionX, motionZ) = (-sin(yaw), cos(yaw))}.
     * In Minecraft's XZ plane (+X east, +Z south), left is (+motionZ, -motionX) and right is (-motionZ, +motionX).
     */
    public static Stream<MovementOption> getOptions(float motionX, float motionZ, boolean canSprint) {
        float fwdScale = canSprint ? SPRINT_MULTIPLIER : 1.0f;
        float diagFwdScale = fwdScale * INV_SQRT2;
        float leftX = motionZ;
        float leftZ = -motionX;
        float rightX = -motionZ;
        float rightZ = motionX;

        return Stream.of(
                new MovementOption(Input.MOVE_FORWARD, motionX * fwdScale, motionZ * fwdScale),
                new MovementOption(Input.MOVE_BACK, -motionX, -motionZ),
                new MovementOption(Input.MOVE_LEFT, leftX, leftZ),
                new MovementOption(Input.MOVE_RIGHT, rightX, rightZ),
                new MovementOption(Input.MOVE_FORWARD, Input.MOVE_LEFT, (motionX + leftX) * diagFwdScale, (motionZ + leftZ) * diagFwdScale),
                new MovementOption(Input.MOVE_FORWARD, Input.MOVE_RIGHT, (motionX + rightX) * diagFwdScale, (motionZ + rightZ) * diagFwdScale),
                new MovementOption(Input.MOVE_BACK, Input.MOVE_LEFT, (-motionX + leftX) * INV_SQRT2, (-motionZ + leftZ) * INV_SQRT2),
                new MovementOption(Input.MOVE_BACK, Input.MOVE_RIGHT, (-motionX + rightX) * INV_SQRT2, (-motionZ + rightZ) * INV_SQRT2)
        );
    }
}
