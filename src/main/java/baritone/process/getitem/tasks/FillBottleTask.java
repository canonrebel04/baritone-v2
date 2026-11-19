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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Set;

/** A27: fill glass bottles at the nearest water source (looking at the water surface, one paced use per bottle). */
public final class FillBottleTask extends GatherTask {
    private final Set<BlockPos> blacklist;
    private final int startBottles;
    private BlockPos water;
    private int nearTicks;
    private int lastFilled;
    private int idle;

    public FillBottleTask(Baritone baritone, GameData data, int count, Set<BlockPos> blacklist) {
        super(baritone, data, PlanStep.operate("Fill " + count + " water bottles", null, Collections.singletonList("minecraft:glass_bottle"), count));
        this.blacklist = blacklist;
        this.startBottles = data.count(Collections.singletonList("minecraft:glass_bottle"));
    }

    private int bottles() {
        return data.count(Collections.singletonList("minecraft:glass_bottle"));
    }

    @Override
    protected PathingCommand onTick() {
        if (startBottles - bottles() >= step.count || bottles() == 0) return finish();
        if (water == null) {
            for (BlockPos p : data.findBlocks(Set.of(Blocks.WATER), 48)) {
                if (blacklist.contains(p)) continue;
                if (ctx.world().getFluidState(p).isSource() && ctx.world().getBlockState(p.above()).isAir()) {
                    water = p;
                    break;
                }
            }
            if (water == null) return fail("no water nearby to fill bottles");
        }
        Vec3 surface = new Vec3(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
        if (ctx.playerHead().distanceTo(surface) > 4.0) {
            if (++nearTicks > 1200) {
                blacklist.add(water);
                water = null;
                nearTicks = 0;
            }
            return walkNear(water.above(), 2);
        }
        if (baritone.getPathingBehavior().isPathing()) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (!hold(is("minecraft:glass_bottle"))) return pause();
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), surface, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(r, true);
        if (ctx.playerRotations().isReallyCloseTo(r) && useReady()) {
            ctx.minecraft().gameMode.useItem(ctx.player(), InteractionHand.MAIN_HAND);
        }
        int filled = startBottles - bottles();
        if (filled > lastFilled) {
            lastFilled = filled;
            idle = 0;
        } else if (++idle > 400) {
            return fail("couldn't fill bottles");
        }
        return pause();
    }

    @Override
    public String status() {
        return "filling water bottles (" + Math.max(0, startBottles - bottles()) + "/" + step.count + ")";
    }
}
