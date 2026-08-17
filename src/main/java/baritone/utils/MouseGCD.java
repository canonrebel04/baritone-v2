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

package baritone.utils;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;

/**
 * Mouse GCD (greatest common divisor of mouse deltas) quantization utilities.
 *
 * <p>Anti-cheat "GCD" / sensitivity checks flag rotation deltas that are not multiples of the
 * smallest angle increment the player can achieve with their current mouse sensitivity. Vanilla
 * Minecraft converts raw mouse deltas into player rotation in two steps:
 *
 * <ol>
 *     <li>{@code net.minecraft.client.MouseHandler#turnPlayer} (verified against the decompiled
 *     26.1.2 mappings): {@code f = sensitivity * 0.6F + 0.2F}, then
 *     {@code step = f * f * f * 8.0D}.</li>
 *     <li>{@code net.minecraft.world.entity.Entity#turn}: multiplies the per-pixel step by
 *     {@code 0.15F} and adds it to the player's yaw/pitch.</li>
 * </ol>
 *
 * <p>So the minimal yaw/pitch increment for the current sensitivity is
 * {@code (float) (f * f * f * 8.0D) * 0.15F}, and every rotation Baritone writes to the player is
 * rounded to the nearest multiple of that step. Note the float literals are promoted to double
 * exactly like the vanilla bytecode does ({@code 0.6000000238418579d} / {@code 0.20000000298023224d}).
 *
 * <p>This is the same formula used by {@code AbstractAimProcessor#mouseToAngle} (which rounds
 * deltas to whole mouse pixels); {@link #step} is that formula evaluated for exactly one pixel.
 */
public final class MouseGCD {

    /**
     * Fallback sensitivity used when the vanilla options instance cannot be read (headless/test
     * environments). 50% sensitivity yields {@code f = 0.5}, i.e. a step of exactly 0.15 degrees.
     */
    private static final double DEFAULT_SENSITIVITY = 0.5;

    private MouseGCD() {
    }

    /**
     * The minimal yaw/pitch increment (in degrees) achievable at the given mouse sensitivity,
     * replicating the vanilla {@code MouseHandler#turnPlayer} + {@code Entity#turn} math exactly.
     *
     * @param sensitivity The mouse sensitivity (vanilla slider value, typically {@code 0.0 - 1.0})
     * @return The per-pixel rotation step in degrees
     */
    public static float stepForSensitivity(double sensitivity) {
        final double f = sensitivity * (double) 0.6f + (double) 0.2f;
        return (float) (f * f * f * 8.0d) * 0.15f;
    }

    /**
     * The GCD step for the player's current sensitivity, falling back to {@link #DEFAULT_SENSITIVITY}
     * if the vanilla options instance is unavailable.
     *
     * @param ctx The player context
     * @return The per-pixel rotation step in degrees
     */
    public static float step(IPlayerContext ctx) {
        double sensitivity = DEFAULT_SENSITIVITY;
        try {
            sensitivity = ctx.minecraft().options.sensitivity().get();
        } catch (Exception e) {
            // Fall back to the default sensitivity; vanilla options are unreachable (e.g. headless).
        }
        return stepForSensitivity(sensitivity);
    }

    /**
     * Rounds an angle to the nearest multiple of {@code step}. Angles that are already multiples
     * (and zero) are returned unchanged; non-finite angles are passed through untouched.
     *
     * @param angle The angle in degrees
     * @param step  The quantization step in degrees
     * @return The nearest multiple of {@code step}
     */
    public static float quantize(float angle, float step) {
        if (step <= 0.0f || !Float.isFinite(angle)) {
            return angle;
        }
        return Math.round(angle / step) * step;
    }

    /**
     * Returns a copy of {@code rotation} with both angles rounded to the nearest GCD multiple.
     *
     * @param rotation The rotation to quantize
     * @param step     The quantization step in degrees
     * @return A new GCD-quantized rotation
     */
    public static Rotation quantize(Rotation rotation, float step) {
        return new Rotation(quantize(rotation.getYaw(), step), quantize(rotation.getPitch(), step));
    }
}
