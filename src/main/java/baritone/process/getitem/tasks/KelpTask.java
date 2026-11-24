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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Kelp leftover: break kelp that is growing on top of more kelp, leaving the root so the farm can regrow.
 * {@code FarmProcess} does not handle kelp.
 */
public final class KelpTask extends GatherTask {
    private final BlockPos center;
    private final int range;
    private BlockPos current;
    private int breakTicks;

    public KelpTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        String[] p = step.source.ref.split(",");
        this.center = new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        this.range = p.length > 3 ? Integer.parseInt(p[3].trim()) : 8;
    }

    public static boolean isKelp(Block b) {
        return b == Blocks.KELP || b == Blocks.KELP_PLANT;
    }

    /** Harvestable if this plant sits on more kelp (the seafloor root is left alone). */
    public static boolean harvestable(net.minecraft.world.level.Level world, BlockPos p) {
        return isKelp(world.getBlockState(p).getBlock()) && isKelp(world.getBlockState(p.below()).getBlock());
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        ItemEntity drop = nearestDrop(Vec3.atCenterOf(center), range + 2);
        if (drop != null) return pickUp(drop);
        if (ctx.playerFeet().distSqr(center) > (range + 16L) * (range + 16L)) return walkNear(center, range);
        if (current == null || !harvestable(ctx.world(), current)) {
            current = null;
            breakTicks = 0;
            for (BlockPos p : data.findBlocks(Set.of(Blocks.KELP, Blocks.KELP_PLANT), Math.max(range, 16))) {
                if (p.distSqr(center) <= (double) range * range && harvestable(ctx.world(), p)) {
                    current = p;
                    break;
                }
            }
            if (current == null) {
                if (have() > target - step.count) return finish();
                return fail("no harvestable kelp at " + center.toShortString());
            }
        }
        java.util.Optional<Rotation> reach = RotationUtils.reachable(ctx, current, ctx.playerController().getBlockReachDistance());
        if (reach.isEmpty()) return walkNear(current, 2);
        if (baritone.getPathingBehavior().isPathing()) return pause();
        baritone.getLookBehavior().updateTarget(reach.get(), true);
        if (ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getBlockPos().equals(current)) {
            Direction face = hit.getDirection();
            if (breakTicks++ == 0) ctx.playerController().clickBlock(current, face);
            else ctx.playerController().onPlayerDamageBlock(current, face);
        }
        if (stalled() > 2400) return fail("harvesting kelp is taking too long");
        return pause();
    }

    @Override
    public String status() {
        return "harvesting kelp " + Math.min(have(), target) + "/" + target;
    }
}
