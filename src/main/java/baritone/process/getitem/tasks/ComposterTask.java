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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * A31: make bone meal in a composter from the step's compostable input: right-click items in until it's full,
 * wait for it to mature, right-click the bone meal out and pick it up.
 */
public final class ComposterTask extends GatherTask {
    private final String input;
    private final Set<BlockPos> blacklist;
    private BlockPos pos;
    private int waitTicks;
    private PlaceStationTask placer;

    public ComposterTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step);
        String in = null;
        for (Map.Entry<String, Integer> e : step.source.inputs.entrySet()) in = e.getKey();
        this.input = in;
        this.blacklist = blacklist;
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        if (pos == null || ctx.world().getBlockState(pos).getBlock() != Blocks.COMPOSTER) {
            pos = null;
            for (BlockPos p : data.findBlocks(Set.of(Blocks.COMPOSTER), Baritone.settings().getItemStationSearchRadius.value)) {
                if (!blacklist.contains(p)) {
                    pos = p;
                    break;
                }
            }
            if (pos == null) {
                if (!Baritone.settings().getItemPlaceStations.value) return fail("no composter nearby");
                if (placer == null) {
                    placer = new PlaceStationTask(baritone, data, PlanStep.place("minecraft:composter", 0, 3));
                }
                PathingCommand placed = placer.tick();
                if (placer.failure() != null) return fail(placer.failure());
                if (placer.isDone()) {
                    pos = placer.placedAt();
                    placer = null;
                    if (pos == null) return fail("placed a composter but couldn't find it");
                    return pause();
                }
                return placed;
            }
        }
        ItemEntity drop = nearestDrop(Vec3.atCenterOf(pos), 3);
        if (drop != null) return pickUp(drop);
        int level = ctx.world().getBlockState(pos).getValue(ComposterBlock.LEVEL);
        if (level == 8) {
            PathingCommand cmd = useBlock(pos); // empty hand not needed: a full composter ejects with anything held
            return cmd == null ? pause() : cmd;
        }
        if (level == 7) {
            if (++waitTicks > 200) return fail("the composter didn't finish");
            return walkNear(pos, 2);
        }
        waitTicks = 0;
        if (input == null || !owns(input)) {
            return have() >= target ? finish() : fail("ran out of " + (input == null ? "compostables" : input.replace("minecraft:", "")));
        }
        if (!hold(is(input))) return pause();
        PathingCommand cmd = useBlock(pos);
        if (stalled() > 2400) return fail("composting is taking too long");
        return cmd == null ? pause() : cmd;
    }

    @Override
    public void stop() {
        if (placer != null) placer.stop();
    }

    @Override
    public String status() {
        return "composting " + (input == null ? "" : input.replace("minecraft:", "")) + " (" + Math.max(0, have() - (target - step.count)) + "/" + step.count + " bone meal)";
    }
}
