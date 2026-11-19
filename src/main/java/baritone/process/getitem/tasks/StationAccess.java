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
import baritone.process.getitem.GameData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

import java.util.*;

/** Walk to a station block, look at it and open it (legal reach + line of sight, paced like a player). */
public final class StationAccess {
    /** Remembered station positions (A12) by block id near a point, used when none is loaded in range. */
    public static java.util.function.BiFunction<String, BlockPos, List<BlockPos>> knownStations = (b, p) -> Collections.emptyList();

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final GameData data;
    private final Block station;
    private final Set<BlockPos> blacklist;
    BlockPos pos;
    private int lookTicks;
    private int clicks;
    private int pathTicks;

    StationAccess(Baritone baritone, GameData data, String stationId, Set<BlockPos> blacklist) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.data = data;
        this.station = GameData.block(stationId);
        this.blacklist = blacklist;
    }

    /** @return null once the right-click was sent and a container should open; otherwise the command to run. */
    PathingCommand approach(StringBuilder failure) {
        if (station == null) {
            failure.append("unknown station");
            return null;
        }
        if (pos == null || ctx.world().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && ctx.world().getBlockState(pos).getBlock() != station) {
            pos = null;
            for (BlockPos p : data.findBlocks(Collections.singleton(station), Baritone.settings().getItemStationSearchRadius.value)) {
                if (!blacklist.contains(p)) {
                    pos = p;
                    break;
                }
            }
            if (pos == null) {
                for (BlockPos p : knownStations.apply(GameData.id(station), ctx.playerFeet())) {
                    boolean loaded = ctx.world().getChunkSource().hasChunk(p.getX() >> 4, p.getZ() >> 4);
                    if (!blacklist.contains(p) && (!loaded || ctx.world().getBlockState(p).getBlock() == station)) {
                        pos = p;
                        break;
                    }
                }
            }
            if (pos == null) {
                failure.append("no ").append(GameData.id(station).replace("minecraft:", "")).append(" nearby");
                return null;
            }
        }
        Optional<Rotation> reach = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
        if (reach.isEmpty()) {
            if (++pathTicks > 1200) {
                blacklist.add(pos);
                failure.append("couldn't reach the station at ").append(pos.toShortString());
                return null;
            }
            return new PathingCommand(new GoalGetToBlock(pos), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (baritone.getPathingBehavior().isPathing()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        baritone.getLookBehavior().updateTarget(reach.get(), true);
        lookTicks++;
        if (ctx.isLookingAt(pos) && ctx.objectMouseOver() instanceof BlockHitResult hit && lookTicks >= 3) {
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            clicks++;
            lookTicks = 0;
            return null;
        }
        if (lookTicks > 60) {
            blacklist.add(pos);
            failure.append("couldn't look at the station");
            return null;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    int clicks() {
        return clicks;
    }
}
