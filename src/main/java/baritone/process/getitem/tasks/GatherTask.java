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
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.pacing.InteractionRhythm;
import baritone.api.utils.pacing.LagModel;
import baritone.api.utils.pacing.Pace;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Shared base for M3 world-source steps (trade, farm, tree, animals, fishing, mob farm): progress counting, a
 * no-progress timeout, walking near a spot, holding an item, looking and paced use-interactions (S38 rhythm).
 */
abstract class GatherTask extends StepTask {
    protected final int target;
    private int lastCount;
    private int stall;
    private int useWait;
    private long uses;
    private int regoal;

    GatherTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.target = data.count(step.items) + step.count;
        this.lastCount = data.count(step.items);
    }

    protected int have() {
        return data.count(step.items);
    }

    protected boolean reached() {
        return have() >= target;
    }

    /** Ticks without the item count going up (reset on progress). */
    protected int stalled() {
        int c = have();
        if (c > lastCount) {
            lastCount = c;
            stall = 0;
        } else {
            stall++;
        }
        return stall;
    }

    protected PathingCommand walkNear(BlockPos pos, int range) {
        return new PathingCommand(new GoalNear(pos, range), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /** Follow a moving target: the goal is refreshed and the path re-checked against it. */
    protected PathingCommand follow(Entity e, int range) {
        return new PathingCommand(new GoalNear(e.blockPosition(), range), regoal++ % 10 == 0
                ? PathingCommandType.REVALIDATE_GOAL_AND_PATH : PathingCommandType.SET_GOAL_AND_PATH);
    }

    /** Put an item matching {@code what} in the main hand; false while it is still being moved / not owned. */
    protected boolean hold(Predicate<ItemStack> what) {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (what.test(inv.get(i))) {
                ctx.player().getInventory().setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (what.test(inv.get(i))) {
                baritone.getInventoryBehavior().attemptToPutOnHotbar(i, slot -> !inv.get(slot).isEmpty()
                        && inv.get(slot).getItem().components().has(net.minecraft.core.component.DataComponents.TOOL));
                return false;
            }
        }
        return false;
    }

    protected boolean owns(String item) {
        return data.inventory().getOrDefault(item, 0) > 0;
    }

    protected static Predicate<ItemStack> is(String id) {
        return s -> !s.isEmpty() && GameData.id(s.getItem()).equals(id);
    }

    protected void look(Vec3 at) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(r, true);
    }

    /** S38: true when a paced use-click may happen now. */
    protected boolean useReady() {
        if (useWait > 0) {
            useWait--;
            return false;
        }
        Pace pace = Pace.fromProfile(Baritone.settings().lookProfile.value);
        useWait = InteractionRhythm.placeGap(pace, Math.max(1, Baritone.settings().rightClickSpeed.value - 1), uses++,
                Math.min(1.5, LagModel.global().factor()));
        return true;
    }

    /** Right-click the entity under the crosshair if it's {@code e}. */
    protected boolean interact(Entity e) {
        if (ctx.objectMouseOver() instanceof EntityHitResult hit && hit.getEntity() == e && useReady()) {
            ctx.minecraft().gameMode.interact(ctx.player(), e, hit, InteractionHand.MAIN_HAND);
            ctx.player().swing(InteractionHand.MAIN_HAND, ctx.player().getMainHandItem().getInteractAnimation(), false);
            return true;
        }
        return false;
    }

    /** Nearest dropped item of the step's items within {@code r} of {@code around}. */
    protected ItemEntity nearestDrop(Vec3 around, double r) {
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : ctx.world().getEntities((Entity) null, new AABB(around, around).inflate(r), x -> x instanceof ItemEntity && x.isAlive())) {
            ItemEntity ie = (ItemEntity) e;
            if (!step.items.contains(GameData.id(ie.getItem().getItem())) && !wantsDrop(GameData.id(ie.getItem().getItem()))) continue;
            double d = ie.distanceToSqr(ctx.player());
            if (d < bd) {
                bd = d;
                best = ie;
            }
        }
        return best;
    }

    /** Other drops worth picking up (saplings for replanting, ...). */
    protected boolean wantsDrop(String item) {
        return false;
    }

    protected PathingCommand pickUp(ItemEntity ie) {
        return new PathingCommand(new baritone.api.pathing.goals.GoalBlock(ie.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
    }

    static BlockPos parsePos(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    @Override
    public String status() {
        return step.source.label + " (" + Math.max(0, have() - (target - step.count)) + "/" + step.count + ")";
    }
}
