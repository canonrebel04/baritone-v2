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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.sim.MovementSimulator;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int dist;
    private final boolean ascend;
    private final int deltaY;
    private boolean steppedBack;
    private int ticksOnGroundBeforeJump;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, int deltaY) {
        super(baritone, src, src.relative(dir, dist).above(deltaY), EMPTY, src.relative(dir, dist).above(deltaY - 1));
        this.direction = dir;
        this.dist = dist;
        this.deltaY = deltaY;
        this.ascend = deltaY > 0;
    }

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y - src.y);
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        // Enhancement #26: Head-hitter sprint-jump (opt-in) allows a 2-high ceiling above takeoff
        boolean headHitter = !MovementHelper.fullyPassable(context, x, y + 2, z);
        if (headHitter && !context.allowHeadHitterJump) {
            return;
        }
        if (!headHitter && !MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock()) || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        boolean iceRunup = standingOn.is(Blocks.ICE) || standingOn.is(Blocks.PACKED_ICE) || standingOn.is(Blocks.BLUE_ICE);
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingOn.getBlock() == Blocks.SOUL_SAND || standingOn.getBlock() == Blocks.HONEY_BLOCK) {
            maxJump = 2; // 1 block gap
        } else {
            maxJump = context.maxCardinalParkourGap(iceRunup);
            if (headHitter && maxJump < 4 && context.canSprint) {
                maxJump = 4;
            }
        }

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // check head/feet
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!headHitter && !MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position (Enhancement #23: strict head clearance at destX, y+3, destZ)
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (!headHitter && i <= 3 && context.allowParkourAscend && context.canSprint
                        && MovementHelper.fullyPassable(context, destX, y + 3, destZ)
                        && MovementHelper.canWalkOn(context, destX, y, destZ, destInto)
                        && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = MovementSimulator.simulatedParkourCost(i, 1, true, context.simConfig) + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for flat landing position
            BlockState landingOn = context.bsi.get0(destX, y - 1, destZ);
            // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
            // frostwalker works here because we can't jump from possibly unfrozen water
            if ((landingOn.getBlock() != Blocks.FARMLAND && MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i && MovementHelper.canUseFrostWalker(context, landingOn))
            ) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = MovementSimulator.simulatedParkourCost(i, 0, context.canSprint, context.simConfig)
                            + context.jumpPenalty
                            + context.tightClearanceCost(destX, y, destZ);
                    return;
                }
                break;
            }

            // Enhancement #24: Descending parkour (-1/-2 Y over 3-5 block gaps, opt-in)
            if (context.allowDescendingParkour && i >= 3 && i <= 5 && context.canSprint) {
                for (int dy = -1; dy >= -2; dy--) {
                    BlockState downLanding = context.bsi.get0(destX, y + dy - 1, destZ);
                    if (downLanding.getBlock() != Blocks.FARMLAND
                            && MovementHelper.canWalkOn(context, destX, y + dy - 1, destZ, downLanding)
                            && MovementHelper.fullyPassable(context, destX, y + dy, destZ)
                            && MovementHelper.fullyPassable(context, destX, y + dy + 1, destZ)
                            && checkOvershootSafety(context.bsi, destX + xDiff, y + dy, destZ + zDiff)) {
                        res.x = destX;
                        res.y = y + dy;
                        res.z = destZ;
                        res.cost = MovementSimulator.simulatedParkourCost(i, dy, true, context.simConfig)
                                + context.jumpPenalty + 1.5;
                        return;
                    }
                }
            }

            if (!headHitter && !MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(context, destX, y - 1, destZ, toReplace)) {
                continue;
            }
            if (!checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }


    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int i = 0; i <= dist; i++) {
            for (int y = 0; y < 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        // the bot steps back one block behind the start to build up sprint momentum for
        // long jumps; that position must also be valid, otherwise PathExecutor aborts the path
        for (int y = 0; y < 2; y++) {
            set.add(src.relative(direction, -1).above(y));
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // Allow cancelling if not yet running, OR if still grounded on the takeoff / run-up block
        // so PathExecutor's stuck detector can break out of any pre-jump loop.
        if (state.getStatus() != MovementStatus.RUNNING) {
            return true;
        }
        return ctx.player().onGround()
                && (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.relative(direction, -1)));
    }

    @Override
    public void reset() {
        super.reset();
        this.steppedBack = false;
        this.ticksOnGroundBeforeJump = 0;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < Math.min(src.y, dest.y)) {
            // we have fallen below both takeoff and landing elevation
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dist >= 4 || ascend) {
            state.setInput(Input.SPRINT, true);
        }
        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        double px = ctx.player().position().x;
        double pz = ctx.player().position().z;
        double vx = ctx.player().getDeltaMovement().x;
        double vz = ctx.player().getDeltaMovement().z;
        double forwardOffset = (px - (src.x + 0.5)) * direction.getStepX() + (pz - (src.z + 0.5)) * direction.getStepZ();
        double lateralOffset = Math.abs((px - (src.x + 0.5)) * direction.getStepZ() + (pz - (src.z + 0.5)) * direction.getStepX());
        double forwardVel = vx * direction.getStepX() + vz * direction.getStepZ();

        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // it physically hurt me to add support for parkour jumping onto a vine
                // but i did it anyway
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (ctx.player().onGround() && ctx.player().position().y - ctx.playerFeet().getY() < 0.094) { // lilypads
                // Enhancement #7: Landing snap — damp horizontal overshoot if moving fast and next move turns/stops
                if (Baritone.settings().movementLandingSnap.value && forwardVel > 0.16 && !continuesStraight()) {
                    state.setInput(Input.MOVE_FORWARD, false);
                    state.setInput(Input.SPRINT, false);
                    state.setInput(Input.MOVE_BACK, true);
                }
                return state.setStatus(MovementStatus.SUCCESS);
            }
            MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }

        if (ctx.playerFeet().equals(src)) {
            if (ctx.player().onGround() && ++ticksOnGroundBeforeJump > 30) {
                // Break out of any pre-jump oscillation loop and let A* replan with failure memory
                logDebug("Parkour stuck before takeoff, aborting to replan");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            // Enhancements #16 & #17: Parkour entry from momentum + multi-block run-up
            // If already carrying sprint momentum (>= 0.21 b/t) along jump axis, skip step-back!
            if (forwardVel >= 0.21) {
                steppedBack = true;
            }
            if ((dist >= 4 || ascend) && !steppedBack) {
                int runupDist = dist >= 5 ? 2 : 1;
                BetterBlockPos back = src.relative(direction, -runupDist);
                if (runupDist == 2 && (!MovementHelper.canWalkOn(ctx, back.below())
                        || !MovementHelper.canWalkThrough(ctx, back)
                        || !MovementHelper.canWalkThrough(ctx, back.above()))) {
                    back = src.relative(direction, -1);
                }
                if (forwardVel < 0.16 && forwardOffset > -0.15
                        && MovementHelper.canWalkOn(ctx, back.below())
                        && MovementHelper.canWalkThrough(ctx, back)
                        && MovementHelper.canWalkThrough(ctx, back.above())) {
                    state.setInput(Input.SPRINT, false);
                    MovementHelper.moveTowards(ctx, state, back);
                    return state;
                }
                steppedBack = true;
            }
            // Always keep camera aimed toward dest (never swing sideways toward src center, which caused loops)
            MovementHelper.moveTowards(ctx, state, dest);
            if (!state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false)) {
                return state;
            }
            // Center laterally using gentle strafe while keeping forward momentum (don't kill MOVE_FORWARD!)
            double signedLateral = (px - (src.x + 0.5)) * (-direction.getStepZ())
                    + (pz - (src.z + 0.5)) * direction.getStepX();
            if (Math.abs(signedLateral) > 0.22 && forwardOffset < 0.20) {
                state.setInput(signedLateral > 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
            }
            // Enhancement #6: Tick-exact jump timing using headless physics simulator edge prediction
            boolean readyToJump;
            if (Baritone.settings().movementTickExactJump.value) {
                double edgeMargin = (dist >= 4 || ascend) ? 0.08 : 0.04;
                boolean leavesNextTick = MovementSimulator.willLeaveBlockNextTick(
                        px, pz, vx, vz, src.x, src.z, direction.getStepX(), direction.getStepZ(), edgeMargin);
                readyToJump = lateralOffset <= 0.28 && (leavesNextTick || forwardOffset >= ((dist >= 4 || ascend) ? 0.36 : 0.44));
            } else {
                readyToJump = (dist >= 4 || ascend)
                        ? (forwardOffset >= 0.35 && lateralOffset <= 0.28)
                        : (dist == 2 ? forwardOffset >= 0.42 : forwardOffset >= 0.52);
            }
            if (readyToJump) {
                state.setInput(Input.JUMP, true);
            }
        } else if (ctx.playerFeet().equals(src.relative(direction, -1)) || ctx.playerFeet().equals(src.relative(direction, -2))) {
            steppedBack = true;
            MovementHelper.moveTowards(ctx, state, dest);
        } else {
            MovementHelper.moveTowards(ctx, state, dest);
            if (ctx.playerFeet().equals(src.relative(direction)) || ctx.player().position().y - src.y > 0.0001) {
                if (Baritone.settings().allowPlace.value // see PR #3775
                        && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                        && !MovementHelper.canWalkOn(ctx, dest.below())
                        && !ctx.player().onGround()
                        && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE
                ) {
                    // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                // prevent jumping too late by checking for ascend
                if (dist == 3 && !ascend) { // this is a 2 block gap, dest = src + direction * 3
                    double xDiff = (src.x + 0.5) - px;
                    double zDiff = (src.z + 0.5) - pz;
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    if (distFromStart < 0.55) {
                        return state;
                    }
                }

                state.setInput(Input.JUMP, true);
            } else if (!ctx.playerFeet().equals(dest.relative(direction, -1))) {
                state.setInput(Input.SPRINT, false);
                MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
            }
        }
        return state;
    }
}
