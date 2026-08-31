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

package baritone.api.process;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.util.List;

public interface IElytraProcess extends IBaritoneProcess {

    void repackChunks();

    /**
     * @return Where it is currently flying to, null if not active
     */
    BlockPos currentDestination();

    /**
     * @return Current active path, empty if not active or no path has been calculated yet
     */
    List<BetterBlockPos> getPath();

    void pathTo(BlockPos destination);

    void pathTo(Goal destination);

    /**
     * Starts a multi-leg trip. The process will fly each leg in order, landing and taking off
     * again between legs, until every leg is complete.
     *
     * @param legs The legs to fly, in order. Must contain at least 2 legs
     * @throws IllegalArgumentException If fewer than 2 legs are provided
     */
    default void startTrip(List<GoalXZ> legs) {
        throw new UnsupportedOperationException("Trips are not supported by this elytra process");
    }

    /**
     * Cancels the current multi-leg trip, if any, and clears its persisted progress
     */
    default void cancelTrip() {}

    /**
     * @return {@code true} if a multi-leg trip is currently in progress
     */
    default boolean isTripActive() {
        return false;
    }

    /**
     * Resets the state of the process but will maintain the same destination and will try to keep flying
     */
    void resetState();

    /**
     * @return {@code true} if the native library loaded and elytra is actually usable
     */
    boolean isLoaded();

    /*
     * FOR INTERNAL USE ONLY. MAY BE REMOVED AT ANY TIME.
     */
    boolean isSafeToCancel();
}
