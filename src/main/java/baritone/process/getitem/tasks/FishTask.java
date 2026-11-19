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
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;

/**
 * A29: fish (extends the AutoFish idea): stand by open water, cast, reel in on the bobber splash (bite), wait a
 * human beat, recast. Gives up on a spot after repeated empty casts.
 */
public final class FishTask extends GatherTask {
    private final GetItemKnowledge knowledge;
    private BlockPos water;
    private long castNanos;
    private int castTicks = -1;
    private int recastIn;
    private int casts, emptyCasts;
    private int lookTicks;

    public FishTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, PlanStep step) {
        super(baritone, data, step);
        this.knowledge = knowledge;
    }

    private BlockPos findWater() {
        BlockPos me = ctx.playerFeet();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos w : data.findBlocks(Collections.singleton(Blocks.WATER), 24)) {
            if (!ctx.world().getFluidState(w).isSource() || !ctx.world().getBlockState(w.above()).isAir()) continue;
            int open = 0;
            for (BlockPos n : new BlockPos[]{w.north(), w.south(), w.east(), w.west()}) if (ctx.world().getFluidState(n).isSource()) open++;
            if (open < 3) continue;
            double d = w.distSqr(me);
            if (d < bd) {
                bd = d;
                best = w;
            }
        }
        return best;
    }

    private void use() {
        ctx.minecraft().gameMode.useItem(ctx.player(), InteractionHand.MAIN_HAND);
        ctx.player().swing(InteractionHand.MAIN_HAND, ctx.player().getMainHandItem().getInteractAnimation(), false);
    }

    @Override
    protected PathingCommand onTick() {
        FishingHook hook = ctx.player().fishing;
        if (reached()) {
            if (hook != null) use(); // reel in before leaving
            return finish();
        }
        if (casts > 60 || emptyCasts > 6) {
            if (hook != null) use();
            return have() > target - step.count ? finish() : fail("no bites here");
        }
        if (water == null) {
            water = findWater();
            if (water == null) return fail("no open water nearby to fish in");
        }
        double dist = Math.sqrt(ctx.playerFeet().distSqr(water));
        if (hook == null && (dist > 4.5 || dist < 1.5)) return walkNear(water, 3);
        if (baritone.getPathingBehavior().isPathing()) return pause();
        if (!hold(is("minecraft:fishing_rod"))) {
            return owns("minecraft:fishing_rod") ? pause() : fail("no fishing rod");
        }
        if (hook == null) {
            if (recastIn > 0) {
                recastIn--;
                return pause();
            }
            look(Vec3.atCenterOf(water).add(0, 0.4, 0));
            if (++lookTicks >= 4 && useReady()) {
                use();
                casts++;
                castNanos = System.nanoTime();
                castTicks = 0;
                lookTicks = 0;
            }
            return pause();
        }
        castTicks++;
        boolean bite = knowledge.splashSince(castNanos + 500_000_000L, hook.getX(), hook.getY(), hook.getZ(), 2.0);
        boolean stuck = castTicks > 60 && !hook.isInWater();
        if (bite || castTicks > 20 * 45 || stuck) {
            if (useReady()) {
                int before = have();
                use();
                if (!bite) emptyCasts++;
                else if (have() == before) emptyCasts = Math.max(0, emptyCasts - 1);
                recastIn = 10 + (int) (Math.random() * 8); // human beat before recasting
                if (stuck) water = null;
            }
        }
        return pause();
    }

    @Override
    public void stop() {
        if (ctx.player() != null && ctx.player().fishing != null) use();
    }
}
