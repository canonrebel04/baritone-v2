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
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Optional;

/**
 * A34 tree farming: pick a clear dirt/grass spot, plant the sapling, bone meal it (paced) until it grows (or wait
 * for natural growth), chop the logs with MineProcess, then (A34 leaf decay) collect sapling drops while the
 * leaves decay and plant one back for next time.
 */
public final class TreeTask extends GatherTask {
    private enum Phase { SPOT, PLANT, GROW, CHOP, COLLECT, REPLANT }

    private final String sapling;
    private final Block log;
    private Phase phase = Phase.SPOT;
    private BlockPos soil;
    private int phaseTicks;
    private boolean chopStarted;

    public TreeTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.sapling = step.source.ref;
        this.log = GameData.block(step.items.get(0));
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    /** A dirt-like block with a clear 5x5x7 column of air above (room for a small tree). */
    boolean goodSoil(BlockPos p) {
        BlockState s = ctx.world().getBlockState(p);
        if (!s.is(BlockTags.DIRT)) return false;
        for (int y = 1; y <= 7; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (y == 1 && (Math.abs(dx) > 0 || Math.abs(dz) > 0)) continue; // ground level around may be anything walkable
                    BlockState a = ctx.world().getBlockState(p.offset(dx, y, dz));
                    if (!a.isAir() && !a.is(BlockTags.LEAVES) && !a.canBeReplaced()) return false;
                }
            }
        }
        return ctx.world().getBrightness(net.minecraft.world.level.LightLayer.SKY, p.above()) >= 8
                || ctx.world().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p.above()) >= 9;
    }

    private BlockPos findSoil() {
        BlockPos me = ctx.playerFeet();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    BlockPos p = me.offset(dx, dy, dz);
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue; // not under our feet
                    double d = p.distSqr(me);
                    if (d < bd && goodSoil(p)) {
                        bd = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private boolean place(BlockPos on, String item) {
        if (!hold(is(item))) return false;
        Optional<Rotation> r = RotationUtils.reachable(ctx, on, ctx.playerController().getBlockReachDistance());
        if (r.isEmpty()) return false;
        baritone.getLookBehavior().updateTarget(r.get(), true);
        if (ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getBlockPos().equals(on) && useReady()) {
            BlockHitResult top = new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false);
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                    hit.getDirection() == Direction.UP ? hit : top);
            ctx.player().swing(InteractionHand.MAIN_HAND, ctx.player().getMainHandItem().getInteractAnimation(), false);
            return true;
        }
        return false;
    }

    @Override
    protected boolean wantsDrop(String item) {
        return item.equals(sapling) || item.equals("minecraft:apple") || item.equals("minecraft:stick");
    }

    @Override
    protected PathingCommand onTick() {
        phaseTicks++;
        if (log == null) return fail("unknown log " + step.items.get(0));
        switch (phase) {
            case SPOT: {
                soil = findSoil();
                if (soil == null) return fail("no clear spot to plant a " + sapling.replace("minecraft:", ""));
                to(Phase.PLANT);
                return pause();
            }
            case PLANT: {
                if (ctx.world().getBlockState(soil.above()).getBlock() instanceof SaplingBlock) {
                    to(Phase.GROW);
                    return pause();
                }
                if (!owns(sapling)) return fail("no " + sapling.replace("minecraft:", ""));
                if (!goodSoil(soil)) {
                    to(Phase.SPOT);
                    return pause();
                }
                if (Math.sqrt(ctx.playerFeet().distSqr(soil)) > 3.5) return walkNear(soil, 2);
                if (baritone.getPathingBehavior().isPathing()) return pause();
                place(soil, sapling);
                return phaseTicks > 300 ? fail("couldn't plant the sapling") : pause();
            }
            case GROW: {
                BlockState at = ctx.world().getBlockState(soil.above());
                if (at.getBlock() == log || at.is(BlockTags.LOGS)) {
                    to(Phase.CHOP);
                    return pause();
                }
                if (!(at.getBlock() instanceof SaplingBlock)) return fail("the sapling is gone");
                boolean meal = Baritone.settings().getItemTreeBoneMeal.value && owns("minecraft:bone_meal");
                if (meal) {
                    if (Math.sqrt(ctx.playerFeet().distSqr(soil)) > 3.5) return walkNear(soil, 2);
                    Optional<Rotation> r = RotationUtils.reachable(ctx, soil.above(), ctx.playerController().getBlockReachDistance());
                    if (r.isPresent() && hold(is("minecraft:bone_meal"))) {
                        baritone.getLookBehavior().updateTarget(r.get(), true);
                        if (ctx.objectMouseOver() instanceof BlockHitResult hit && hit.getBlockPos().equals(soil.above()) && useReady()) {
                            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                            ctx.player().swing(InteractionHand.MAIN_HAND, ctx.player().getMainHandItem().getInteractAnimation(), false);
                        }
                    }
                    return pause();
                }
                // no bone meal: wait for natural growth (the planner only picks this as a last resort)
                return phaseTicks > 20 * 60 * 20 ? fail("the sapling didn't grow") : pause();
            }
            case CHOP: {
                if (reached()) {
                    baritone.getMineProcess().cancel();
                    to(Phase.COLLECT);
                    return pause();
                }
                if (!chopStarted) {
                    baritone.getMineProcess().mine(0, new BlockOptionalMetaLookup(Collections.singletonList(log)));
                    chopStarted = true;
                    return defer();
                }
                if (!baritone.getMineProcess().isActive() || phaseTicks > 20 * 90) {
                    baritone.getMineProcess().cancel();
                    to(Phase.COLLECT);
                    return pause();
                }
                return defer();
            }
            case COLLECT: {
                if (!Baritone.settings().getItemTreeReplant.value) return finish();
                // leaves decay over the next while: pick up the saplings they drop
                ItemEntity d = nearestDrop(Vec3.atCenterOf(soil), 8);
                if (d != null) return pickUp(d);
                if (phaseTicks > 20 * 25) to(Phase.REPLANT);
                return phaseTicks % 40 == 0 ? walkNear(soil, 2) : pause();
            }
            case REPLANT: {
                if (!owns(sapling) || ctx.world().getBlockState(soil.above()).getBlock() instanceof SaplingBlock
                        || !ctx.world().getBlockState(soil.above()).isAir() || phaseTicks > 200) {
                    return finish();
                }
                if (Math.sqrt(ctx.playerFeet().distSqr(soil)) > 3.5) return walkNear(soil, 2);
                place(soil, sapling);
                return pause();
            }
            default:
                return finish();
        }
    }

    @Override
    public void stop() {
        if (phase == Phase.CHOP) baritone.getMineProcess().cancel();
    }
}
