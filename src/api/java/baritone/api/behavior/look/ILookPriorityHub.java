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

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Coordinates and arbitrates rotation requests from Baritone internal pathing/movement and external mods
 * (such as Meteor Client combat modules: KillAura, BowAimbot, CrystalAura).
 * <p>
 * Requests are prioritized numerically: higher priority requests take precedence over lower priority ones.
 * Requests can have a finite tick lifetime or remain active until explicitly released via {@link ILookRequest#release()}.
 *
 * @author Baritone
 */
public interface ILookPriorityHub {

    /**
     * Default priority for Baritone's internal pathing, look-ahead, and movement rotations.
     */
    double DEFAULT_BARITONE_PRIORITY = 0.0;

    /**
     * Default fallback priority for unspecified rotation requests.
     */
    double DEFAULT_PRIORITY = 0.0;

    /**
     * Standard priority for block placement/breaking interactions.
     */
    double INTERACTION_PRIORITY = 50.0;

    /**
     * Standard priority for external combat modules (KillAura, BowAimbot, CrystalAura, etc.).
     */
    double COMBAT_PRIORITY = 100.0;

    /**
     * High override priority for critical safety or manual override rotations.
     */
    double OVERRIDE_PRIORITY = 1000.0;

    /**
     * Submits a persistent rotation request with the specified priority and yaw/pitch.
     * Remains active until explicitly released via {@link ILookRequest#release()}.
     *
     * @param priority The numeric priority (higher wins)
     * @param yaw      The target yaw angle
     * @param pitch    The target pitch angle
     * @return The handle to the created request
     */
    default ILookRequest requestRotation(double priority, float yaw, float pitch) {
        return requestRotation(priority, new Rotation(yaw, pitch), -1);
    }

    /**
     * Submits a rotation request with the specified priority, yaw/pitch, and lifetime in ticks.
     *
     * @param priority      The numeric priority (higher wins)
     * @param yaw           The target yaw angle
     * @param pitch         The target pitch angle
     * @param lifetimeTicks The number of ticks before this request expires (-1 for indefinite)
     * @return The handle to the created request
     */
    default ILookRequest requestRotation(double priority, float yaw, float pitch, int lifetimeTicks) {
        return requestRotation(priority, new Rotation(yaw, pitch), lifetimeTicks);
    }

    /**
     * Submits a persistent rotation request with the specified priority and {@link Rotation}.
     *
     * @param priority The numeric priority (higher wins)
     * @param rotation The target rotation
     * @return The handle to the created request
     */
    default ILookRequest requestRotation(double priority, Rotation rotation) {
        return requestRotation(priority, rotation, -1);
    }

    /**
     * Submits a rotation request with the specified priority, {@link Rotation}, and lifetime in ticks.
     *
     * @param priority      The numeric priority (higher wins)
     * @param rotation      The target rotation
     * @param lifetimeTicks The number of ticks before this request expires (-1 for indefinite)
     * @return The handle to the created request
     */
    ILookRequest requestRotation(double priority, Rotation rotation, int lifetimeTicks);

    /**
     * Submits a persistent rotation request with a dynamic {@link Supplier} evaluated each tick.
     *
     * @param priority         The numeric priority (higher wins)
     * @param rotationSupplier The supplier providing target rotations
     * @return The handle to the created request
     */
    default ILookRequest requestRotation(double priority, Supplier<Rotation> rotationSupplier) {
        return requestRotation(priority, rotationSupplier, -1);
    }

    /**
     * Submits a rotation request with a dynamic {@link Supplier} and lifetime in ticks.
     *
     * @param priority         The numeric priority (higher wins)
     * @param rotationSupplier The supplier providing target rotations
     * @param lifetimeTicks    The number of ticks before this request expires (-1 for indefinite)
     * @return The handle to the created request
     */
    ILookRequest requestRotation(double priority, Supplier<Rotation> rotationSupplier, int lifetimeTicks);

    /**
     * Submits a rotation request with block interaction flag and lifetime.
     *
     * @param priority      The numeric priority (higher wins)
     * @param rotation      The target rotation
     * @param blockInteract Whether this rotation is needed for block interaction
     * @param lifetimeTicks The number of ticks before this request expires (-1 for indefinite)
     * @return The handle to the created request
     */
    ILookRequest requestRotation(double priority, Rotation rotation, boolean blockInteract, int lifetimeTicks);

    /**
     * Submits a rotation request with a dynamic supplier, block interaction flag, and lifetime.
     *
     * @param priority         The numeric priority (higher wins)
     * @param rotationSupplier The supplier providing target rotations
     * @param blockInteract    Whether this rotation is needed for block interaction
     * @param lifetimeTicks    The number of ticks before this request expires (-1 for indefinite)
     * @return The handle to the created request
     */
    ILookRequest requestRotation(double priority, Supplier<Rotation> rotationSupplier, boolean blockInteract, int lifetimeTicks);

    /**
     * Resolves and returns the currently active request with the highest priority.
     *
     * @return An {@link Optional} containing the highest priority active request, or empty if none
     */
    Optional<ILookRequest> getHighestPriorityRequest();

    /**
     * Returns the target rotation from the highest priority active request, if any.
     *
     * @return An {@link Optional} containing the target rotation, or empty if no request is active
     */
    default Optional<Rotation> getActiveRotation() {
        return getHighestPriorityRequest().map(ILookRequest::getRotation);
    }

    /**
     * Checks if there are any active requests currently in the hub.
     *
     * @return {@code true} if at least one active request exists
     */
    boolean hasActiveRequests();

    /**
     * Checks if there are active external requests with priority exceeding Baritone's default priority.
     *
     * @return {@code true} if an overriding external request is active
     */
    default boolean hasActiveExternalRequests() {
        return getHighestPriorityRequest()
                .filter(req -> req.getPriority() > DEFAULT_BARITONE_PRIORITY)
                .isPresent();
    }

    /**
     * Returns the highest priority among all currently active requests.
     *
     * @return The highest active priority, or {@link Double#NEGATIVE_INFINITY} if none
     */
    default double getHighestActivePriority() {
        return getHighestPriorityRequest().map(ILookRequest::getPriority).orElse(Double.NEGATIVE_INFINITY);
    }

    /**
     * Returns an unmodifiable snapshot list of all currently active requests.
     *
     * @return List of active requests
     */
    List<ILookRequest> getActiveRequests();

    /**
     * Releases and removes the specified request from the hub.
     *
     * @param request The request to release
     */
    void release(ILookRequest request);

    /**
     * Releases all currently active requests in this hub.
     */
    void releaseAll();

    /**
     * Clears all requests from this hub.
     */
    void clear();
}
