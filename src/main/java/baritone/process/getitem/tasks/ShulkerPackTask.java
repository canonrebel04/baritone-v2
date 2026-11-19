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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.process.getitem.GameData;
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * A43: pack overflow into a carried shulker box: place it next to the player, put the given items in, break it
 * and pick it back up (contents stay inside the item). Off by default ({@code getItemShulkerPack}).
 */
public final class ShulkerPackTask extends StepTask {
    private enum Phase { PLACE, FILL, BREAK, PICKUP }

    private final GetItemKnowledge knowledge;
    private final String shulker;
    private final Set<String> items;
    private Phase phase = Phase.PLACE;
    private StepTask sub;
    private BlockPos pos;
    private int phaseTicks;
    private int startCount;

    public ShulkerPackTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, String shulker, Set<String> items) {
        super(baritone, data, PlanStep.operate("Pack overflow into a " + shulker.replace("minecraft:", "").replace('_', ' '), null,
                Collections.singletonList(shulker), 1));
        this.knowledge = knowledge;
        this.shulker = shulker;
        this.items = items;
        this.startCount = data.count(Collections.singletonList(shulker));
    }

    @Override
    protected PathingCommand onTick() {
        phaseTicks++;
        switch (phase) {
            case PLACE: {
                if (sub == null) sub = new PlaceStationTask(baritone, data, PlanStep.place(shulker, 0, 0));
                PathingCommand cmd = sub.tick();
                if (sub.failure() != null) return fail(sub.failure());
                if (sub.isDone()) {
                    pos = ((PlaceStationTask) sub).placedAt();
                    sub = null;
                    next(Phase.FILL);
                    return pause();
                }
                return cmd;
            }
            case FILL: {
                if (sub == null) sub = new StashTask(baritone, data, knowledge, pos, items);
                PathingCommand cmd = sub.tick();
                if (sub.isDone() || sub.failure() != null) {
                    sub.stop();
                    sub = null;
                    knowledge.base().containers.remove(data.dimension(), pos.getX(), pos.getY(), pos.getZ());
                    next(Phase.BREAK);
                    return pause();
                }
                return cmd;
            }
            case BREAK: {
                if (ctx.world().getBlockState(pos).isAir()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    next(Phase.PICKUP);
                    return pause();
                }
                if (phaseTicks > 400) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    return fail("couldn't break the shulker box back up at " + pos.toShortString());
                }
                Optional<Rotation> r = RotationUtils.reachable(ctx, pos);
                if (r.isEmpty()) return new PathingCommand(new GoalGetToBlock(pos), PathingCommandType.SET_GOAL_AND_PATH);
                baritone.getLookBehavior().updateTarget(r.get(), true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, ctx.isLookingAt(pos));
                return pause();
            }
            default: {
                if (data.count(Collections.singletonList(shulker)) >= startCount) return finish();
                if (phaseTicks > 300) return fail("the shulker box item wasn't picked up");
                for (Entity e : ctx.world().getEntities((Entity) null, new AABB(pos).inflate(4), x -> x instanceof ItemEntity && x.isAlive())) {
                    ItemEntity ie = (ItemEntity) e;
                    if (shulker.equals(GameData.id(ie.getItem().getItem()))) {
                        return new PathingCommand(new GoalBlock(ie.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
                    }
                }
                return pause();
            }
        }
    }

    private void next(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    @Override
    public void stop() {
        if (sub != null) sub.stop();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    public String status() {
        return "packing overflow into a shulker box (" + phase.name().toLowerCase() + ")";
    }
}
