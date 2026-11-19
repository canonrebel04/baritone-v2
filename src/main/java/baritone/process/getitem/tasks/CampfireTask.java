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
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * A26: cook on a lit campfire (no fuel, four at a time). Holds the raw food, right-clicks it onto free campfire
 * slots, waits, and picks up the cooked items it pops out. Campfires already cooking someone else's food are
 * skipped (blacklisted).
 */
public final class CampfireTask extends GatherTask {
    private final String input;
    private final Set<BlockPos> blacklist;
    private BlockPos pos;
    private int placed;

    public CampfireTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step);
        String in = null;
        for (Map.Entry<String, Integer> e : step.inputs.entrySet()) in = e.getKey();
        this.input = in;
        this.blacklist = blacklist;
    }

    @Override
    protected PathingCommand onTick() {
        if (input == null) return fail("no input for " + step.recipe.id);
        if (reached()) return finish();
        if (pos == null || !lit(pos)) {
            pos = null;
            for (BlockPos p : data.findBlocks(Set.of(net.minecraft.world.level.block.Blocks.CAMPFIRE, net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE),
                    Baritone.settings().getItemStationSearchRadius.value)) {
                if (!blacklist.contains(p) && lit(p) && !foreign(p)) {
                    pos = p;
                    break;
                }
            }
            if (pos == null) return fail("no lit campfire nearby");
        }
        ItemEntity drop = nearestDrop(Vec3.atCenterOf(pos), 4);
        if (drop != null) return pickUp(drop);
        int free = freeSlots(pos);
        boolean owns = owns(input);
        if (placed < step.batches && owns && free > 0) {
            if (!hold(is(input))) return pause();
            PathingCommand cmd = useBlock(pos);
            if (cmd == null) placed++;
            return cmd == null ? pause() : cmd;
        }
        int cook = step.recipe.cookTime <= 0 ? 600 : step.recipe.cookTime;
        if (stalled() > cook + 400) return fail("the campfire stopped cooking");
        if (!owns && free == 4 && placed > 0) return fail("cooked fewer " + step.items.get(0).replace("minecraft:", "") + " than planned");
        return walkNear(pos, 2);
    }

    private boolean lit(BlockPos p) {
        var st = ctx.world().getBlockState(p);
        return st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT);
    }

    private int freeSlots(BlockPos p) {
        if (!(ctx.world().getBlockEntity(p) instanceof CampfireBlockEntity be)) return 0;
        int n = 0;
        for (var s : be.getItems()) if (s.isEmpty()) n++;
        return n;
    }

    /** Cooking something that isn't ours. */
    private boolean foreign(BlockPos p) {
        if (!(ctx.world().getBlockEntity(p) instanceof CampfireBlockEntity be)) return false;
        for (var s : be.getItems()) if (!s.isEmpty() && !input.equals(GameData.id(s.getItem()))) return true;
        return false;
    }

    @Override
    public String status() {
        return "cooking " + step.items.get(0).replace("minecraft:", "") + " on a campfire (" + Math.max(0, have() - (target - step.count)) + "/" + step.count + ")";
    }
}
