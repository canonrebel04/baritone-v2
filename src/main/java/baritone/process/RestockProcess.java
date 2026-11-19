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
import baritone.api.process.IRestockProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous shulker box restock: finds a shulker in inventory containing the requested
 * item, places it on an adjacent solid block, opens it, shift-clicks matching stacks,
 * mines the shulker back up, and walks over the drop to re-collect it.
 */
public final class RestockProcess extends BaritoneProcessHelper implements IRestockProcess {

    private enum Stage {
        PLACE,
        OPEN,
        LOOT,
        MINE,
        PICKUP
    }

    private Item targetItem;
    private int shulkerSlot = -1;
    private BetterBlockPos placedPos;
    private Stage stage;
    private int ticksInStage;

    public RestockProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean restock(Item item) {
        if (item == null || ctx.player() == null) {
            return false;
        }
        int slot = findShulkerContaining(item);
        if (slot < 0) {
            logDirect("No shulker box in inventory contains " + item.getDefaultInstance().getHoverName().getString());
            return false;
        }
        this.targetItem = item;
        this.shulkerSlot = slot;
        this.placedPos = null;
        this.stage = Stage.PLACE;
        this.ticksInStage = 0;
        logDirect("Restocking " + item.getDefaultInstance().getHoverName().getString() + " from shulker in slot " + slot);
        return true;
    }

    @Override
    public boolean isActive() {
        return targetItem != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (targetItem == null || ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ticksInStage++;
        baritone.getInputOverrideHandler().clearAllKeys();

        switch (stage) {
            case PLACE -> {
                if (placedPos == null) {
                    placedPos = findPlacementSpot();
                    if (placedPos == null) {
                        logDirect("No safe adjacent space to place shulker box");
                        onLostControl();
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                }
                if (!baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock && containsTarget(s, targetItem))) {
                    logDirect("Unable to move shulker box to hotbar");
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                BlockPos below = placedPos.below();
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(below).add(0, 0.5, 0), ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(rot, true);
                if (ticksInStage >= 2) {
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
                    stage = Stage.OPEN;
                    ticksInStage = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case OPEN -> {
                if (ctx.player().containerMenu instanceof ShulkerBoxMenu) {
                    stage = Stage.LOOT;
                    ticksInStage = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!(ctx.world().getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock)) {
                    if (ticksInStage > 20) {
                        logDirect("Shulker placement failed");
                        onLostControl();
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(placedPos), ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(rot, true);
                if (ticksInStage % 4 == 2) {
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(placedPos), Direction.UP, placedPos, false));
                }
                if (ticksInStage > 40) {
                    stage = Stage.MINE;
                    ticksInStage = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case LOOT -> {
                if (!(ctx.player().containerMenu instanceof ShulkerBoxMenu menu)) {
                    stage = Stage.MINE;
                    ticksInStage = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                int moved = 0;
                for (int i = 0; i < 27; i++) {
                    ItemStack stack = menu.getSlot(i).getItem();
                    if (!stack.isEmpty() && stack.is(targetItem)) {
                        ctx.playerController().windowClick(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, ctx.player());
                        moved++;
                        if (moved >= 4) break;
                    }
                }
                if (moved == 0 || ticksInStage > 15) {
                    ctx.player().closeContainer();
                    stage = Stage.MINE;
                    ticksInStage = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case MINE -> {
                BlockState state = ctx.world().getBlockState(placedPos);
                if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
                    stage = Stage.PICKUP;
                    ticksInStage = 0;
                    return new PathingCommand(new GoalBlock(placedPos), PathingCommandType.SET_GOAL_AND_PATH);
                }
                baritone.getInventoryBehavior().throwaway(true, s -> s.isCorrectToolForDrops(state));
                Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(placedPos), ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(rot, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                if (ticksInStage > 100) {
                    logDirect("Timed out mining shulker box");
                    onLostControl();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case PICKUP -> {
                if (ctx.playerFeet().distSqr(placedPos) <= 2.25 || ticksInStage > 60) {
                    logDirect("Restock complete");
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(new GoalBlock(placedPos), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        targetItem = null;
        shulkerSlot = -1;
        placedPos = null;
        stage = null;
        ticksInStage = 0;
    }

    @Override
    public String displayName0() {
        return "Restock" + (targetItem != null ? " (" + targetItem.getDefaultInstance().getHoverName().getString() + ")" : "");
    }

    @Override
    public double priority() {
        return 4.5D;
    }

    private BetterBlockPos findPlacementSpot() {
        BetterBlockPos feet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BetterBlockPos candidate = feet.relative(dir);
            if (bsi.get0(candidate).isAir()
                    && bsi.get0(candidate.above()).isAir()
                    && !bsi.get0(candidate.below()).isAir()) {
                return candidate;
            }
        }
        // Fallback for 1x1 holes: place on top of a surrounding hole wall if open above
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BetterBlockPos candidate = feet.relative(dir).above();
            if (bsi.get0(candidate).isAir()
                    && bsi.get0(candidate.above()).isAir()
                    && !bsi.get0(candidate.below()).isAir()) {
                return candidate;
            }
        }
        return null;
    }

    private int findShulkerContaining(Item item) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock && containsTarget(stack, item)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsTarget(ItemStack shulker, Item target) {
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents == null) {
            return false;
        }
        for (ItemStack inner : contents.nonEmptyItemCopyStream().toList()) {
            if (inner.is(target)) {
                return true;
            }
        }
        return false;
    }
}
