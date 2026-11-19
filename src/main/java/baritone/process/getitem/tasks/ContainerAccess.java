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

package baritone.process.getitem.tasks;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/** Walk to a known container position, look at it and open it (legal reach + line of sight, paced). */
final class ContainerAccess {
    private final Baritone baritone;
    private final IPlayerContext ctx;
    final BlockPos pos;
    private int lookTicks;
    private int clicks;
    private int pathTicks;

    ContainerAccess(Baritone baritone, BlockPos pos) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.pos = pos;
    }

    /** @return null once the right-click was sent (or on failure, see {@code failure}); otherwise the command. */
    PathingCommand approach(StringBuilder failure) {
        Optional<Rotation> reach = reachComfortably(baritone, pos, pathTicks);
        if (reach.isEmpty()) {
            if (++pathTicks > 1600) {
                failure.append("couldn't reach the container at ").append(pos.toShortString());
                return null;
            }
            return new PathingCommand(new GoalGetToBlock(pos), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (baritone.getPathingBehavior().isPathing()) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        baritone.getLookBehavior().updateTarget(reach.get(), true);
        lookTicks++;
        if (ctx.isLookingAt(pos) && ctx.objectMouseOver() instanceof BlockHitResult hit && lookTicks >= 3) {
            if (ctx.player().isShiftKeyDown()) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            clicks++;
            lookTicks = -15; // wait for the screen before clicking again
            return null;
        }
        if (lookTicks > 60) {
            failure.append("couldn't look at the container at ").append(pos.toShortString());
            return null;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /** S43: comfortable opening distance for a given full reach (never below 2 blocks). */
    static double comfortReach(double full) {
        return Math.max(2.0, Math.min(full - 0.8, 3.7));
    }

    /**
     * S43: a rotation to {@code pos} within comfort reach; once we've pathed for a while and stopped (the goal
     * is as close as the terrain allows), anything within legal reach is accepted.
     */
    static Optional<Rotation> reachComfortably(Baritone baritone, BlockPos pos, int pathTicks) {
        IPlayerContext ctx = baritone.getPlayerContext();
        double full = ctx.playerController().getBlockReachDistance();
        if (!Baritone.settings().containerComfortReach.value) return RotationUtils.reachable(ctx, pos, full);
        Optional<Rotation> r = RotationUtils.reachable(ctx, pos, comfortReach(full));
        if (r.isPresent()) return r;
        if (pathTicks > 20 && !baritone.getPathingBehavior().isPathing()) return RotationUtils.reachable(ctx, pos, full);
        return Optional.empty();
    }

    int clicks() {
        return clicks;
    }
}
