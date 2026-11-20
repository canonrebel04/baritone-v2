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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.sim.MovementSimulator;
import baritone.pathing.movement.sim.PlayerPhysics;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int dist;
    private final boolean ascend;
    private boolean hasJumped = false;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, boolean ascend) {
        super(baritone, src, src.relative(dir, dist).above(ascend ? 1 : 0), EMPTY, src.relative(dir, dist).below(ascend ? 0 : 1));
        this.direction = dir;
        this.dist = dist;
        this.ascend = ascend;
    }

    @Override
    public void reset() {
        super.reset();
        this.hasJumped = false;
    }

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y > src.y);
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
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
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
        Block standingBlock = standingOn.getBlock();
        boolean iceRunup = standingBlock == Blocks.ICE || standingBlock == Blocks.PACKED_ICE
                || standingBlock == Blocks.BLUE_ICE || standingBlock == Blocks.FROSTED_ICE;
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingBlock == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint) {
            maxJump = context.maxCardinalParkourGap(iceRunup);
        } else {
            maxJump = 3;
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
            if (!MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3 && context.allowParkourAscend && context.canSprint
                        && MovementHelper.fullyPassable(context, x, y + 3, z)
                        && MovementHelper.fullyPassable(context, x + xDiff, y + 3, z + zDiff)
                        && MovementHelper.canWalkOn(context, destX, y, destZ, destInto)
                        && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
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
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break;
            }

            if (!MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
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
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
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
                if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
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
                return WALK_ONE_BLOCK_COST * 2;
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            case 5:
                return SPRINT_ONE_BLOCK_COST * 5;
            default:
                return SPRINT_ONE_BLOCK_COST * Math.max(2, dist);
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
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once this movement is instantiated, the state is default to PREPPING
        // but once it's ticked for the first time it changes to RUNNING
        // since we don't really know anything about momentum, it suffices to say Parkour can only be canceled on the 0th tick
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < src.y) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        int xDiff = direction.getStepX();
        int zDiff = direction.getStepZ();
        double px = ctx.player().position().x;
        double py = ctx.player().position().y;
        double pz = ctx.player().position().z;
        double vx = ctx.player().getDeltaMovement().x;
        double vz = ctx.player().getDeltaMovement().z;
        boolean onGround = ctx.player().onGround();

        double forwardSpeed = vx * xDiff + vz * zDiff;
        double relFromSrc = (px - (src.x + 0.5)) * xDiff + (pz - (src.z + 0.5)) * zDiff;
        double distToDestAxis = ((dest.x + 0.5) - px) * xDiff + ((dest.z + 0.5) - pz) * zDiff;

        if (!onGround && py > src.y + 0.05) {
            hasJumped = true;
        }

        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        // Aim at dest center and measure yaw alignment
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
        float targetYaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenter, ctx.playerRotations()).getYaw();
        float yawErr = Math.abs(Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), targetYaw));

        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (py - ctx.playerFeet().getY() < 0.094) { // lilypads
                // Landing snap: brake if the block past dest is a drop-off
                if (Baritone.settings().movementLandingSnap.value && !MovementHelper.canWalkOn(ctx, dest.relative(direction).below())) {
                    if (forwardSpeed > 0.08) {
                        MovementHelper.moveTowards(ctx, state, destCenter);
                        state.setInput(Input.MOVE_FORWARD, false);
                        state.setInput(Input.SPRINT, false);
                        state.setInput(Input.SNEAK, true);
                        return state;
                    }
                }
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }

        // Pre-jump run-up & yaw alignment while still grounded on src
        if (onGround && !hasJumped && ctx.playerFeet().equals(src)) {
            boolean needsSprintRunup = dist >= 4 || ascend;
            // Wait for camera yaw to converge before running off a narrow takeoff block
            if (yawErr > 22.0f && relFromSrc < 0.25) {
                MovementHelper.moveTowards(ctx, state, destCenter);
                state.setInput(Input.MOVE_FORWARD, false);
                return state;
            }
            // If we need full sprint momentum for a 4-5 block or ascend jump but started near the front edge at low speed, back up slightly first
            if (needsSprintRunup && forwardSpeed < 0.14 && relFromSrc > 0.15) {
                MovementHelper.moveTowards(ctx, state, destCenter);
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, true);
                state.setInput(Input.SPRINT, false);
                return state;
            }
        }

        MovementHelper.moveTowards(ctx, state, destCenter);
        boolean wantsSprint = (dist >= 4 || ascend) && yawErr < 25.0f;
        if (wantsSprint) {
            state.setInput(Input.SPRINT, true);
        }

        // Landing snap while airborne approaching a 1x1 / cliff landing
        if (hasJumped && Baritone.settings().movementLandingSnap.value
                && !MovementHelper.canWalkOn(ctx, dest.relative(direction).below())
                && distToDestAxis < 0.65 && distToDestAxis > -0.3) {
            double slip = onGround ? PlayerPhysics.BASE_GROUND_SLIPPERINESS : PlayerPhysics.AIR_DRAG;
            if (MovementSimulator.shouldBrakeBeforeTarget(Math.max(0.0, distToDestAxis), forwardSpeed, onGround, slip, 0.12)) {
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.MOVE_FORWARD, false);
                if (distToDestAxis < 0.15 && forwardSpeed > 0.12) {
                    state.setInput(Input.MOVE_BACK, true);
                }
                return state;
            }
        }

        // Midair parkour-place block placement
        if (!onGround && (ctx.playerFeet().equals(src.relative(direction)) || py - src.y > 0.0001)) {
            if (Baritone.settings().allowPlace.value
                    && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                    && !MovementHelper.canWalkOn(ctx, dest.below())
                    && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.CLICK_RIGHT, true);
            }
        }

        // Tick-exact takeoff prediction: fire JUMP on the exact last grounded tick before hitbox leaves src
        if (onGround && !hasJumped) {
            if (Baritone.settings().movementTickExactJump.value) {
                double effSpeed = PlayerPhysics.effectiveGroundSpeed(PlayerPhysics.BASE_MOVEMENT_SPEED, 0, 0, wantsSprint);
                double nextAccel = PlayerPhysics.groundAcceleration(effSpeed, PlayerPhysics.BASE_GROUND_SLIPPERINESS) * PlayerPhysics.WALK_INPUT_MULTIPLIER;
                // Tighter edge margin (0.04) for long/ascend jumps to maximize horizontal reach; slightly earlier (0.12) for 2-block hops
                double margin = (dist >= 4 || ascend) ? 0.04 : 0.12;
                if (MovementSimulator.willStepOffEdgeNextTick(px, pz, vx, vz, src.x, src.z, xDiff, zDiff, nextAccel, margin)) {
                    state.setInput(Input.JUMP, true);
                    hasJumped = true;
                    return state;
                }
            }
            // Fallback if already past block center onto adjacent air cell
            if (!ctx.playerFeet().equals(src) && (ctx.playerFeet().equals(src.relative(direction)) || py - src.y > 0.0001)) {
                state.setInput(Input.JUMP, true);
                hasJumped = true;
            } else if (!ctx.playerFeet().equals(src) && !ctx.playerFeet().equals(dest.relative(direction, -1))) {
                state.setInput(Input.SPRINT, false);
                if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, src);
                } else {
                    MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }
}
