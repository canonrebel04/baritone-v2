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
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/**
 * A33/A36/A39: harvest the ripe crops of a known farm with {@code FarmProcess} (which replants with seeds / the
 * crop and bone meals if you carry some), until the step's count is in the inventory. Ends when the farm has
 * nothing ripe left so the process re-plans.
 */
public final class HarvestTask extends GatherTask {
    private final BlockPos center;
    private final int range;
    private boolean started;
    private boolean hoed;
    private BlockPos hoeTarget;

    public HarvestTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        String[] p = step.source.ref.split(",");
        this.center = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        this.range = p.length > 3 ? Integer.parseInt(p[3]) : 8;
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        if (!hoed && Baritone.settings().getItemHoeSoil.value) {
            if (ctx.playerFeet().distSqr(center) > (range + 16L) * (range + 16L)) return walkNear(center, range);
            PathingCommand hoe = hoeNext();
            if (hoe != null) return hoe;
            hoed = true;
        }
        if (!started) {
            if (ctx.playerFeet().distSqr(center) > (range + 16) * (range + 16)) return walkNear(center, range);
            baritone.getFarmProcess().farm(range, center);
            started = true;
            return defer();
        }
        int stall = stalled();
        if (!baritone.getFarmProcess().isActive() || stall > 900) {
            if (have() > target - step.count) return finish(); // got some: re-plan for the rest
            return fail("nothing ripe to harvest at " + center.toShortString());
        }
        return defer();
    }

    @Override
    public void stop() {
        if (baritone.getFarmProcess().isActive()) baritone.getFarmProcess().onLostControl();
    }

    /**
     * Opt-in (default off): hoe dirt / grass / a dirt path next to water into farmland, inside this farm's range.
     * Skips hoeing entirely when no hoe is in the inventory.
     */
    private PathingCommand hoeNext() {
        if (hoeTarget != null && alreadyFarmland(hoeTarget)) hoeTarget = null;
        if (hoeTarget == null) hoeTarget = findHoeSpot();
        if (hoeTarget == null) return null;
        if (!hold(HarvestTask::isHoe)) {
            hoeTarget = null;
            return null; // no hoe: leave the soil and let FarmProcess work with what is already farmland
        }
        PathingCommand cmd = useBlock(hoeTarget);
        return cmd == null ? pause() : cmd;
    }

    private boolean alreadyFarmland(BlockPos p) {
        return ctx.world().getBlockState(p).is(Blocks.FARMLAND);
    }

    private static boolean isHoe(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String id = GameData.id(s.getItem());
        return id.endsWith("_hoe");
    }

    public static boolean isHoeable(net.minecraft.world.level.block.state.BlockState st) {
        var b = st.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH;
    }

    /** True when water is close enough to hydrate farmland (4-block square, same Y or one above). */
    public static boolean waterCanHydrate(java.util.function.Predicate<BlockPos> water, BlockPos soil) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (water.test(soil.offset(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    private BlockPos findHoeSpot() {
        for (BlockPos p : data.findBlocks(java.util.Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH), Math.max(range, 16))) {
            if (p.distSqr(center) > (double) range * range) continue;
            if (!ctx.world().getBlockState(p.above()).isAir()) continue;
            if (!waterCanHydrate(q -> ctx.world().getFluidState(q).isSourceOfType(Fluids.WATER), p)) continue;
            return p;
        }
        return null;
    }

}
