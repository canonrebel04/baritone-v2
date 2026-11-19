/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.elytra.PortalKnowledge;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Cross-dimensional Nether portal router.
 * Uses {@link PortalKnowledge} to route from Overworld -> Nether (8x compression) -> Overworld
 * to reach distant Overworld coordinates.
 */
public final class PortalRouterProcess extends BaritoneProcessHelper {

    private Integer targetOverworldX;
    private Integer targetOverworldZ;
    private boolean enteredNether;

    public PortalRouterProcess(Baritone baritone) {
        super(baritone);
    }

    public void routeToOverworld(int overworldX, int overworldZ) {
        this.targetOverworldX = overworldX;
        this.targetOverworldZ = overworldZ;
        this.enteredNether = ctx.world() != null && ctx.world().dimension() == Level.NETHER;
        logDirect("Portal router: routing to Overworld (" + overworldX + ", " + overworldZ + ")");
    }

    @Override
    public boolean isActive() {
        return targetOverworldX != null && targetOverworldZ != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (targetOverworldX == null || targetOverworldZ == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (ctx.world().dimension() == Level.OVERWORLD) {
            if (enteredNether) {
                // Exited back into Overworld — path to final destination
                GoalXZ finalGoal = new GoalXZ(targetOverworldX, targetOverworldZ);
                if (finalGoal.isInGoal(ctx.playerFeet())) {
                    logDirect("Portal router: arrived at Overworld target");
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(finalGoal, PathingCommandType.SET_GOAL_AND_PATH);
            }

            // In Overworld before entering Nether: find nearest known Overworld portal
            PortalKnowledge.Portal entry = findNearest(PortalKnowledge.OVERWORLD, ctx.playerFeet().x, ctx.playerFeet().z);
            if (entry == null) {
                logDirect("Portal router: no known Overworld portal in PortalKnowledge; pathing directly");
                return new PathingCommand(new GoalXZ(targetOverworldX, targetOverworldZ), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(new GoalBlock(entry.x, entry.y, entry.z), PathingCommandType.SET_GOAL_AND_PATH);
        }

        if (ctx.world().dimension() == Level.NETHER) {
            enteredNether = true;
            int netherTargetX = Math.floorDiv(targetOverworldX, 8);
            int netherTargetZ = Math.floorDiv(targetOverworldZ, 8);

            PortalKnowledge.Portal exit = findNearest(PortalKnowledge.NETHER, netherTargetX, netherTargetZ);
            if (exit != null && Math.hypot(exit.x - netherTargetX, exit.z - netherTargetZ) <= 64.0) {
                return new PathingCommand(new GoalBlock(exit.x, exit.y, exit.z), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(new GoalXZ(netherTargetX, netherTargetZ), PathingCommandType.SET_GOAL_AND_PATH);
        }

        onLostControl();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        targetOverworldX = null;
        targetOverworldZ = null;
        enteredNether = false;
    }

    @Override
    public String displayName0() {
        return "PortalRouter" + (targetOverworldX != null ? " (" + targetOverworldX + ", " + targetOverworldZ + ")" : "");
    }

    private static PortalKnowledge.Portal findNearest(String dim, int x, int z) {
        List<PortalKnowledge.Portal> portals = PortalKnowledge.known(dim);
        PortalKnowledge.Portal best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PortalKnowledge.Portal p : portals) {
            double dx = p.x - (double) x;
            double dz = p.z - (double) z;
            double dSq = dx * dx + dz * dz;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = p;
            }
        }
        return best;
    }
}
