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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings
    private Goal lastGoal;
    private final Map<Entity, Vec3> anchorPositions = new HashMap<>();

    public FollowProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        if (cache.isEmpty()) {
            anchorPositions.clear();
            lastGoal = null;
            return new PathingCommand(new GoalComposite(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        double threshold = Baritone.settings().followGoalQuantization.value;
        boolean needsReanchor = lastGoal == null
                || threshold <= 0
                || calcFailed
                || cache.size() != anchorPositions.size()
                || shouldReanchor(threshold);

        if (needsReanchor) {
            anchorPositions.clear();
            for (Entity entity : cache) {
                anchorPositions.put(entity, entity.position());
            }
            lastGoal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        }

        return new PathingCommand(lastGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private boolean shouldReanchor(double threshold) {
        double thresholdSq = threshold * threshold;
        for (Entity entity : cache) {
            Vec3 anchor = anchorPositions.get(entity);
            if (anchor == null || entity.position().distanceToSqr(anchor) > thresholdSq) {
                return true;
            }
        }
        return false;
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        if (into) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, Baritone.settings().followRadius.value);
    }


    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        return ctx.entitiesStream().anyMatch(entity::equals);
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        return !cache.isEmpty();
    }

    @Override
    public void onLostControl() {
        filter = null;
        cache = null;
        lastGoal = null;
        anchorPositions.clear();
    }

    @Override
    public String displayName0() {
        return "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
        this.lastGoal = null;
        this.anchorPositions.clear();
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
        this.lastGoal = null;
        this.anchorPositions.clear();
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
