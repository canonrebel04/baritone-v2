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
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import baritone.process.getitem.safety.StationSpotPicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Place a crafting table / furnace from the inventory on the ground next to the player (plan A22/A23). */
public final class PlaceStationTask extends StepTask {
    private final Item item;
    private final Block block;
    private BetterBlockPos spot;
    private int attempts;
    private int stageTicks;
    private BlockPos placed;

    public PlaceStationTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.item = GameData.item(step.blocks.get(0));
        this.block = GameData.block(step.blocks.get(0));
    }

    @Override
    protected PathingCommand onTick() {
        if (block == null) return fail("unknown station " + step.blocks.get(0));
        if (spot != null && ctx.world().getBlockState(spot).getBlock() == block) {
            placed = spot;
            return finish();
        }
        if (baritone.getPathingBehavior().isPathing()) return pause();
        if (spot == null || !ctx.world().getBlockState(spot).isAir() && !ctx.world().getBlockState(spot).canBeReplaced()) {
            spot = findSpot();
            stageTicks = 0;
            if (spot == null) return fail("no free spot to place " + step.blocks.get(0).replace("minecraft:", ""));
        }
        if (!baritone.getInventoryBehavior().throwaway(true, s -> s.is(item), true)) {
            if (++stageTicks > 60) return fail("no " + step.blocks.get(0).replace("minecraft:", "") + " in inventory");
            return pause();
        }
        BlockPos below = spot.below();
        Vec3 target = Vec3.atCenterOf(below).add(0, 0.5, 0);
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true);
        stageTicks++;
        if (ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getBlockPos().equals(below)
                && hit.getDirection() == Direction.UP && stageTicks >= 3) {
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            stageTicks = -6; // give the server a moment to confirm
            if (++attempts > 4) return fail("placing " + step.blocks.get(0).replace("minecraft:", "") + " failed");
        } else if (stageTicks > 60) {
            spot = null; // can't see that face, try another spot
            if (++attempts > 4) return fail("no visible spot to place " + step.blocks.get(0).replace("minecraft:", ""));
        }
        return pause();
    }

    /** A32: scored spot (solid floor, two free blocks, away from hazards and not in the way ahead). */
    private BetterBlockPos findSpot() {
        BetterBlockPos feet = ctx.playerFeet();
        var world = ctx.world();
        float yaw = ctx.playerRotations().getYaw();
        Direction facing = Direction.fromYRot(yaw);
        int[] spotXYZ = StationSpotPicker.pick(feet.getX(), feet.getY(), feet.getZ(), new int[]{facing.getStepX(), facing.getStepZ()},
                new StationSpotPicker.Probe() {
                    @Override
                    public boolean free(int x, int y, int z) {
                        var st = world.getBlockState(new BlockPos(x, y, z));
                        return st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty();
                    }

                    @Override
                    public boolean solid(int x, int y, int z) {
                        return world.getBlockState(new BlockPos(x, y, z)).isSolidRender();
                    }

                    @Override
                    public double hazard(int x, int y, int z) {
                        double h = 0;
                        for (Direction d : Direction.values()) {
                            var f = world.getBlockState(new BlockPos(x, y, z).relative(d)).getFluidState();
                            if (!f.isEmpty()) h += 2;
                        }
                        return h;
                    }
                });
        return spotXYZ == null ? null : new BetterBlockPos(spotXYZ[0], spotXYZ[1], spotXYZ[2]);
    }

    /** Where the station ended up (for the station registry), or null. */
    public BlockPos placedAt() {
        return placed;
    }

    @Override
    public String status() {
        return "placing " + step.blocks.get(0).replace("minecraft:", "");
    }
}
