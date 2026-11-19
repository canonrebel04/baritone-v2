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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;

import java.util.Collections;

/** A45: walk over experience orbs within a short radius (for enchanting / anvil levels). */
public final class XpOrbTask extends StepTask {
    public static final double RADIUS = 12;

    public XpOrbTask(Baritone baritone, GameData data) {
        super(baritone, data, PlanStep.operate("Collect experience orbs", null, Collections.emptyList(), 0));
    }

    public static ExperienceOrb nearest(Baritone baritone) {
        var ctx = baritone.getPlayerContext();
        ExperienceOrb best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : ctx.world().getEntities((Entity) null, new AABB(ctx.playerFeet()).inflate(RADIUS), x -> x instanceof ExperienceOrb && x.isAlive())) {
            double d = e.distanceToSqr(ctx.player());
            if (d < bd && Math.abs(e.getY() - ctx.player().getY()) < 4) {
                bd = d;
                best = (ExperienceOrb) e;
            }
        }
        return best;
    }

    @Override
    protected PathingCommand onTick() {
        if (ticks > 600) return finish();
        ExperienceOrb orb = nearest(baritone);
        if (orb == null) return finish();
        return new PathingCommand(new GoalBlock(orb.blockPosition()), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public String status() {
        return "collecting experience (level " + ctx.player().experienceLevel + ")";
    }
}
