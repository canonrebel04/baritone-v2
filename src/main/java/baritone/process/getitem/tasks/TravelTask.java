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
import baritone.process.elytra.PortalKnowledge;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.CostModel;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Decision 9: travel to the Nether / End (and back) through a portal that is loaded nearby or in Baritone's cache.
 * Portals are not built; without a known portal the step fails with a clear message.
 */
public final class TravelTask extends StepTask {
    private final String target;
    private final String from;
    private final Block portal;
    private BlockPos pos;
    private int inPortalTicks;
    /** Portal positions used, by dimension, for the trip back. */
    public static final Map<String, BlockPos> LAST_PORTAL = new HashMap<>();

    public TravelTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.target = step.dimension();
        this.from = data.dimension();
        this.portal = CostModel.END.equals(target) || CostModel.END.equals(from) ? Blocks.END_PORTAL : Blocks.NETHER_PORTAL;
    }

    @Override
    protected PathingCommand onTick() {
        if (target.equals(data.dimension())) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return finish();
        }
        if (!from.equals(data.dimension())) return pause(); // in between / loading
        if (pos == null || ctx.world().getBlockState(pos).getBlock() != portal && ctx.world().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            pos = find();
            if (pos == null) {
                return fail("no " + (portal == Blocks.END_PORTAL ? "end portal" : "nether portal") + " known nearby to reach "
                        + PlanStep.dimensionName(target) + " (build or find one, or disable getItemAllowNether/End)");
            }
            LAST_PORTAL.put(from, pos);
        }
        if (ticks > 20 * 600) return fail("couldn't get through the portal at " + pos.toShortString());
        if (portal == Blocks.NETHER_PORTAL) {
            if (ctx.playerFeet().equals(pos) || ctx.world().getBlockState(ctx.playerFeet()).getBlock() == portal) {
                if (++inPortalTicks > 20 * 15) return fail("the portal at " + pos.toShortString() + " didn't take us anywhere");
                return pause();
            }
            return new PathingCommand(new GoalBlock(pos), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // end portal: get next to it, then step in
        double d2 = ctx.player().position().distanceToSqr(Vec3.atCenterOf(pos));
        if (d2 > 2.5 * 2.5) return new PathingCommand(new GoalGetToBlock(pos), PathingCommandType.SET_GOAL_AND_PATH);
        if (baritone.getPathingBehavior().isPathing()) return pause();
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(pos), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(new Rotation(r.getYaw(), ctx.playerRotations().getPitch()), true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        if (++inPortalTicks > 20 * 15) return fail("couldn't step into the end portal");
        return pause();
    }

    private BlockPos find() {
        BlockPos me = ctx.playerFeet();
        List<BlockPos> loaded = data.findBlocks(Collections.singleton(portal), 96);
        if (!loaded.isEmpty()) return lowest(loaded, me);
        var world = baritone.getWorldProvider().getCurrentWorld();
        if (world != null) {
            List<BlockPos> cached = world.getCachedWorld().getLocationsOf(GameData.id(portal).replace("minecraft:", ""), 64, me.getX(), me.getZ(), 4);
            if (!cached.isEmpty()) return lowest(cached, me);
        }
        if (portal == Blocks.NETHER_PORTAL) {
            // portals remembered by the elytra/portal router knowledge
            String dimPath = from.substring(from.indexOf(':') + 1);
            BlockPos best = null;
            for (PortalKnowledge.Portal p : PortalKnowledge.known(dimPath)) {
                BlockPos bp = new BlockPos(p.x, p.y, p.z);
                if (best == null || bp.distSqr(me) < best.distSqr(me)) best = bp;
            }
            if (best != null && best.distSqr(me) < 2000L * 2000L) return best;
        }
        return null;
    }

    /** Nearest portal; for nether portals the bottom block of that portal (so the goal is standable). */
    private BlockPos lowest(List<BlockPos> ps, BlockPos me) {
        BlockPos best = Collections.min(ps, Comparator.comparingDouble(p -> p.distSqr(me)));
        if (portal == Blocks.NETHER_PORTAL) {
            while (ctx.world().getBlockState(best.below()).getBlock() == portal) best = best.below();
        }
        return best;
    }

    @Override
    public void stop() {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
    }

    @Override
    public String status() {
        return "travelling to " + PlanStep.dimensionName(target) + (pos != null ? " via " + pos.toShortString() : "");
    }
}
