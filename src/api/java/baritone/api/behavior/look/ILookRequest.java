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

package baritone.api.behavior.look;

import baritone.api.utils.Rotation;

import java.util.function.Supplier;

/**
 * A handle representing an active rotation request submitted to the {@link ILookPriorityHub}.
 * Holds the target rotation, priority, lifetime, and behavioral flags.
 *
 * @author Baritone
 */
public interface ILookRequest {

    /**
     * Gets the numeric priority of this request. Higher values take precedence over lower values.
     *
     * @return The priority
     */
    double getPriority();

    /**
     * Updates the priority of this request.
     *
     * @param priority The new priority
     */
    void setPriority(double priority);

    /**
     * Gets the target rotation of this request. If a rotation supplier is attached, it will be evaluated.
     *
     * @return The target rotation, or {@code null} if unavailable
     */
    Rotation getRotation();

    /**
     * Sets a fixed target rotation for this request.
     *
     * @param rotation The target rotation
     */
    void setRotation(Rotation rotation);

    /**
     * Sets a fixed target rotation with the specified yaw and pitch.
     *
     * @param yaw   The target yaw
     * @param pitch The target pitch
     */
    default void setRotation(float yaw, float pitch) {
        setRotation(new Rotation(yaw, pitch));
    }

    /**
     * Gets the rotation supplier for this request, if one was provided.
     *
     * @return The rotation supplier, or {@code null} if a static rotation is used
     */
    Supplier<Rotation> getRotationSupplier();

    /**
     * Sets a dynamic rotation supplier for this request.
     *
     * @param rotationSupplier The rotation supplier
     */
    void setRotationSupplier(Supplier<Rotation> rotationSupplier);

    /**
     * Gets the remaining lifetime ticks of this request. A value of {@code -1} indicates an indefinite request
     * that remains active until explicitly released via {@link #release()}.
     *
     * @return The remaining lifetime in ticks, or -1 if indefinite
     */
    int getTicksRemaining();

    /**
     * Sets the remaining lifetime in ticks.
     *
     * @param ticksRemaining The ticks remaining (-1 for indefinite)
     */
    void setTicksRemaining(int ticksRemaining);

    /**
     * Returns whether this rotation request is required for a block interaction.
     *
     * @return {@code true} if required for block interaction
     */
    boolean isBlockInteract();

    /**
     * Sets whether this rotation request is required for a block interaction.
     *
     * @param blockInteract {@code true} if required for block interaction
     */
    void setBlockInteract(boolean blockInteract);

    /**
     * Returns whether this request explicitly specifies smooth look behavior.
     * If {@code null}, Baritone's default smoothLook settings are used.
     *
     * @return {@link Boolean#TRUE} for smooth, {@link Boolean#FALSE} for instant, or {@code null} to inherit settings
     */
    Boolean isSmooth();

    /**
     * Sets whether this request should use smooth camera interpolation or instant snap.
     *
     * @param smooth {@link Boolean#TRUE} for smooth, {@link Boolean#FALSE} for instant, or {@code null} to inherit settings
     */
    void setSmooth(Boolean smooth);

    /**
     * Returns whether this rotation should be sent silently to the server without affecting the client view camera.
     *
     * @return {@code true} if silent/server-side only
     */
    boolean isSilent();

    /**
     * Sets whether this rotation should be sent silently to the server.
     *
     * @param silent {@code true} for silent server-side rotations
     */
    void setSilent(boolean silent);

    /**
     * Returns whether this request is currently active (not released and not expired).
     *
     * @return {@code true} if active
     */
    boolean isActive();

    /**
     * Returns whether this request has been released or expired.
     *
     * @return {@code true} if released or expired
     */
    boolean isReleased();

    /**
     * Releases this request, removing it from the priority hub so it will no longer influence player rotation.
     */
    void release();

    /**
     * Alias for {@link #release()}. Cancels and releases this request.
     */
    default void cancel() {
        release();
    }
}
