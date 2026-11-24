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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.movement.sim.MovementSimulator;
import baritone.pathing.movement.sim.PlayerPhysics;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static ItemStack stackBucketWater;

    private static ItemStack getStackBucketWater() {
        if (stackBucketWater == null) {
            stackBucketWater = new ItemStack(Items.WATER_BUCKET);
        }
        return stackBucketWater;
    }

    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final boolean canStepUp;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    public final boolean combatMode;
    public final double combatBlastResistantFootingFavor;
    public final boolean combatDisallowSlowBreak;

    // Deep movement enhancement fields (cached per A* search for consistency)
    public final int speedLevel;
    public final int slownessLevel;
    public final int jumpBoostLevel;
    public final double baseMovementSpeed;
    public final double speedCostScale;
    public final MovementSimulator.SimConfig simConfig;
    public final double turnPenalty;
    public final double tightClearancePenalty;
    public final boolean allowDiagonalParkour;
    public final boolean allowParkourFiveGap;
    public final boolean allowDescendingParkour;
    public final boolean allowNeoJump;
    public final boolean allowHeadHitterJump;
    public final boolean allowSwimSprint;
    public final boolean allowBubbleColumnTravel;
    public final boolean useFailureMemory;
    public final boolean useAdaptiveTuning;

    public final PrecomputedData precomputedData;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    protected CalculationContext(PrecomputedData precomputedData, boolean combatMode, double combatBlastResistantFootingFavor, boolean combatDisallowSlowBreak) {
        this.combatMode = combatMode;
        this.combatBlastResistantFootingFavor = combatBlastResistantFootingFavor;
        this.combatDisallowSlowBreak = combatDisallowSlowBreak;
        this.precomputedData = precomputedData != null ? precomputedData : new PrecomputedData();
        this.safeForThreadedUse = false;
        this.baritone = null;
        this.world = null;
        this.worldData = null;
        this.bsi = null;
        this.toolSet = null;
        this.hasWaterBucket = false;
        this.hasThrowaway = false;
        this.canSprint = true;
        this.placeBlockCost = 20.0D;
        this.allowBreak = true;
        this.allowBreakAnyway = java.util.Collections.emptyList();
        this.allowParkour = false;
        this.allowParkourPlace = false;
        this.allowJumpAtBuildLimit = false;
        this.allowParkourAscend = false;
        this.assumeWalkOnWater = false;
        this.allowFallIntoLava = false;
        this.frostWalker = 0;
        this.allowDiagonalDescend = false;
        this.allowDiagonalAscend = false;
        this.allowDownward = false;
        this.minFallHeight = 3;
        this.maxFallHeightNoWater = 3;
        this.maxFallHeightBucket = 0;
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST;
        this.breakBlockAdditionalCost = 0.0D;
        this.backtrackCostFavoringCoefficient = 1.0D;
        this.jumpPenalty = 0.0D;
        this.canStepUp = false;
        this.walkOnWaterOnePenalty = 0.0D;
        this.allowWalkOnMagmaBlocks = false;
        this.worldBorder = null;
        this.speedLevel = 0;
        this.slownessLevel = 0;
        this.jumpBoostLevel = 0;
        this.baseMovementSpeed = PlayerPhysics.BASE_MOVEMENT_SPEED;
        this.speedCostScale = 1.0;
        this.simConfig = MovementSimulator.SimConfig.vanillaDefault();
        this.turnPenalty = 0.0;
        this.tightClearancePenalty = 0.0;
        this.allowDiagonalParkour = false;
        this.allowParkourFiveGap = false;
        this.allowDescendingParkour = false;
        this.allowNeoJump = false;
        this.allowHeadHitterJump = false;
        this.allowSwimSprint = true;
        this.allowBubbleColumnTravel = true;
        this.useFailureMemory = false;
        this.useAdaptiveTuning = false;
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this.combatMode = Baritone.settings().combatMode.value;
        this.combatBlastResistantFootingFavor = Baritone.settings().combatBlastResistantFootingFavor.value;
        this.combatDisallowSlowBreak = Baritone.settings().combatDisallowSlowBreak.value;
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        LocalPlayer player = baritone.getPlayerContext().player();
        this.world = baritone.getPlayerContext().world();
        this.worldData = (WorldData) baritone.getPlayerContext().worldData();
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = Baritone.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(getStackBucketWater())) && world.dimension() != Level.NETHER;
        // Sprint is allowed when food > 6 (survival), OR when the player is
        // invulnerable (Creative / Spectator — food is always 0 in these modes).
        this.canSprint = Baritone.settings().allowSprint.value
            && (player.getAbilities().invulnerable || player.getFoodData().getFoodLevel() > 6);
        this.placeBlockCost = Baritone.settings().blockPlacementPenalty.value;
        this.allowBreak = Baritone.settings().allowBreak.value;
        this.allowBreakAnyway = new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = Baritone.settings().allowParkour.value;
        this.allowParkourPlace = Baritone.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = Baritone.settings().allowParkourAscend.value;
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        this.frostWalker = frostWalkerLevel;
        this.allowDiagonalDescend = Baritone.settings().allowDiagonalDescend.value;
        this.allowDiagonalAscend = Baritone.settings().allowDiagonalAscend.value;
        this.allowDownward = Baritone.settings().allowDownward.value;
        // The player's actual safe fall distance. Vanilla base is 3.0 and each Feather Falling
        // level adds to the SAFE_FALL_DISTANCE attribute (e.g. Feather Falling IV -> 8.0), so
        // falls up to this distance deal no damage. Baritone previously assumed a fixed safe
        // fall limit of 3 blocks and ignored the attribute entirely; now the real value is used
        // and the movement validation/cost treats falls up to it as safe. Defaults to the
        // vanilla base of 3.0 if the attribute is somehow absent, keeping normal players
        // (and default pathfinding) behavior unchanged.
        double safeFallDistance = 3.0;
        AttributeInstance safeFallAttribute = player.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFallAttribute != null) {
            safeFallDistance = safeFallAttribute.getValue();
        }
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        // the user setting remains a lower bound; the actual safe fall distance can only raise it
        this.maxFallHeightNoWater = Math.max(Baritone.settings().maxFallHeightNoWater.value, (int) safeFallDistance);
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        // P2-13: whether the player can auto-step up a full block (STEP_HEIGHT attribute >= 1.0),
        // which makes 1-block step-ups plain walks instead of jumps; cached per context so the
        // cost model agrees with execution, gated by the honorStepHeight setting
        AttributeInstance stepHeight = player.getAttribute(Attributes.STEP_HEIGHT);
        this.canStepUp = Baritone.settings().honorStepHeight.value
                && stepHeight != null && stepHeight.getValue() >= 1.0;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());

        // Enhancements #19, #20, #34-#39: cache potion effects, attributes, and movement settings
        boolean effectAware = Baritone.settings().movementEffectAwareness.value;
        int spd = 0;
        int slw = 0;
        int jmp = 0;
        double baseSpd = PlayerPhysics.BASE_MOVEMENT_SPEED;
        if (effectAware) {
            MobEffectInstance speedEff = player.getEffect(MobEffects.SPEED);
            if (speedEff != null) {
                spd = speedEff.getAmplifier() + 1;
            }
            MobEffectInstance slowEff = player.getEffect(MobEffects.SLOWNESS);
            if (slowEff != null) {
                slw = slowEff.getAmplifier() + 1;
            }
            MobEffectInstance jumpEff = player.getEffect(MobEffects.JUMP_BOOST);
            if (jumpEff != null) {
                jmp = jumpEff.getAmplifier() + 1;
            }
            AttributeInstance moveSpeedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (moveSpeedAttr != null && moveSpeedAttr.getBaseValue() > 0.01) {
                baseSpd = moveSpeedAttr.getBaseValue();
            }
        }
        this.speedLevel = spd;
        this.slownessLevel = slw;
        this.jumpBoostLevel = jmp;
        this.baseMovementSpeed = baseSpd;
        double effSpeed = PlayerPhysics.effectiveGroundSpeed(baseSpd, spd, slw, false);
        this.speedCostScale = effSpeed > 0.01 ? Math.max(0.4, Math.min(2.5, PlayerPhysics.BASE_MOVEMENT_SPEED / effSpeed)) : 1.0;
        this.simConfig = new MovementSimulator.SimConfig(
                baseSpd, spd, slw, jmp,
                PlayerPhysics.BASE_GROUND_SLIPPERINESS,
                PlayerPhysics.NORMAL_SPEED_FACTOR,
                false, false
        );
        this.turnPenalty = Math.max(0.0, Baritone.settings().movementTurnPenalty.value);
        this.tightClearancePenalty = Math.max(0.0, Baritone.settings().movementTightClearancePenalty.value);
        this.allowDiagonalParkour = Baritone.settings().allowDiagonalParkour.value;
        this.allowParkourFiveGap = Baritone.settings().allowParkourFiveGap.value;
        this.allowDescendingParkour = Baritone.settings().allowDescendingParkour.value;
        this.allowNeoJump = Baritone.settings().allowNeoJump.value;
        this.allowHeadHitterJump = Baritone.settings().allowHeadHitterJump.value;
        this.allowSwimSprint = Baritone.settings().allowSwimSprint.value;
        this.allowBubbleColumnTravel = Baritone.settings().allowBubbleColumnTravel.value;
        this.useFailureMemory = Baritone.settings().movementFailureMemory.value;
        this.useAdaptiveTuning = Baritone.settings().movementAdaptiveTuning.value;
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    private int cacheCenterX = Integer.MIN_VALUE;
    private int cacheCenterY = Integer.MIN_VALUE;
    private int cacheCenterZ = Integer.MIN_VALUE;
    private final BlockState[] cubeCache = new BlockState[9 * 9 * 9];
    private final int[] cubeCacheEpoch = new int[9 * 9 * 9];
    private int currentEpoch = 0;

    public void setCacheCenter(int x, int y, int z) {
        this.cacheCenterX = x;
        this.cacheCenterY = y;
        this.cacheCenterZ = z;
        currentEpoch++;
        if (currentEpoch == 0) {
            // Epoch overflowed
            java.util.Arrays.fill(cubeCacheEpoch, 0);
            currentEpoch = 1;
        }
    }

    public BlockState get(int x, int y, int z) {
        int dx = x - cacheCenterX;
        int dy = y - cacheCenterY;
        int dz = z - cacheCenterZ;
        if (dx >= -4 && dx <= 4 && dy >= -4 && dy <= 4 && dz >= -4 && dz <= 4) {
            int index = (dx + 4) * 81 + (dy + 4) * 9 + (dz + 4);
            // ⚡ Bolt Optimization: Use O(1) epoch validation instead of O(N) Arrays.fill()
            if (cubeCacheEpoch[index] == currentEpoch) {
                return cubeCache[index];
            }
            BlockState state = bsi.get0(x, y, z);
            cubeCache[index] = state;
            cubeCacheEpoch[index] = currentEpoch;
            return state;
        }
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (combatMode && combatDisallowSlowBreak && current.getDestroySpeed(world, new BlockPos(x, y, z)) > 0.3f) {
            return COST_INF;
        }
        return 1;
    }

    /**
     * Returns the combat footing cost multiplier when stepping onto blast-resistant blocks
     * (bedrock, obsidian, crying obsidian, reinforced deepslate) in {@link #combatMode}.
     */
    public double blastResistantFootingMultiplier(BlockState footing) {
        if (!combatMode || !Double.isFinite(combatBlastResistantFootingFavor) || combatBlastResistantFootingFavor <= 0.0D) {
            return 1.0D;
        }
        Block b = footing.getBlock();
        if (b == Blocks.BEDROCK || b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN || b == Blocks.REINFORCED_DEEPSLATE) {
            return combatBlastResistantFootingFavor;
        }
        return 1.0D;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        // TODO more protection logic here; see #220
        return false;
    }

    /**
     * Enhancement #32 & #35: Returns the surface cost multiplier for walking on {@code below}
     * while feet are in {@code feet} (accounts for ice, blue ice, slime, soul sand, honey,
     * cobweb, powder snow, and active Speed/Slowness effects).
     */
    public double surfaceCostMultiplier(BlockState below, BlockState feet) {
        Block belowBlock = below.getBlock();
        Block feetBlock = feet.getBlock();
        double slip = PlayerPhysics.BASE_GROUND_SLIPPERINESS;
        double speedFactor = PlayerPhysics.NORMAL_SPEED_FACTOR;
        if (belowBlock == Blocks.BLUE_ICE) {
            slip = PlayerPhysics.BLUE_ICE_SLIPPERINESS;
        } else if (belowBlock == Blocks.ICE || belowBlock == Blocks.PACKED_ICE || belowBlock == Blocks.FROSTED_ICE) {
            slip = PlayerPhysics.ICE_SLIPPERINESS;
        } else if (belowBlock == Blocks.SLIME_BLOCK) {
            slip = PlayerPhysics.SLIME_SLIPPERINESS;
        } else if (belowBlock == Blocks.SOUL_SAND) {
            speedFactor = PlayerPhysics.SOUL_SAND_SPEED_FACTOR;
        } else if (belowBlock == Blocks.HONEY_BLOCK) {
            speedFactor = PlayerPhysics.HONEY_SPEED_FACTOR;
        }
        boolean inCobweb = feetBlock == Blocks.COBWEB;
        boolean inPowderSnow = feetBlock == Blocks.POWDER_SNOW;
        return PlayerPhysics.surfaceCostMultiplier(slip, speedFactor, inCobweb, inPowderSnow) * speedCostScale;
    }

    /**
     * Enhancement #38: Fall damage mitigation multiplier for soft landing blocks
     * (hay bale = 0.2, honey = 0.2, bed = 0.5, slime = 0.0, cobweb = 0.0).
     */
    public double landingFallDamageMultiplier(Block landingBlock) {
        if (landingBlock == Blocks.SLIME_BLOCK || landingBlock == Blocks.COBWEB || landingBlock == Blocks.WATER) {
            return 0.0;
        }
        if (landingBlock == Blocks.HAY_BLOCK || landingBlock == Blocks.HONEY_BLOCK) {
            return 0.2;
        }
        if (landingBlock instanceof net.minecraft.world.level.block.BedBlock) {
            return 0.5;
        }
        return 1.0;
    }

    /**
     * Enhancement #39: Small additive penalty when a destination node has tight horizontal clearance
     * (adjacent lethal drop or hazard/solid wall pinch).
     */
    public double tightClearanceCost(int x, int y, int z) {
        if (tightClearancePenalty <= 0.0 || bsi == null) {
            return 0.0;
        }
        int pinchedSides = 0;
        if (!MovementHelper.canWalkThrough(this, x + 1, y, z)) pinchedSides++;
        if (!MovementHelper.canWalkThrough(this, x - 1, y, z)) pinchedSides++;
        if (!MovementHelper.canWalkThrough(this, x, y, z + 1)) pinchedSides++;
        if (!MovementHelper.canWalkThrough(this, x, y, z - 1)) pinchedSides++;
        if (pinchedSides >= 3) {
            return tightClearancePenalty;
        }
        return 0.0;
    }

    /**
     * Enhancement #19, #20, #22: Maximum cardinal parkour gap in blocks given current
     * sprint capability, Speed/Jump Boost effects, and settings.
     */
    public int maxCardinalParkourGap(boolean iceRunup) {
        if (!canSprint) {
            return jumpBoostLevel > 0 ? 3 : 2;
        }
        int base = 4;
        if (allowParkourFiveGap && (speedLevel >= 1 || jumpBoostLevel >= 1 || iceRunup)) {
            double span = MovementSimulator.maxReachableJumpSpan(
                    6, 0.0, true,
                    iceRunup ? new MovementSimulator.SimConfig(
                            baseMovementSpeed, speedLevel, slownessLevel, jumpBoostLevel,
                            PlayerPhysics.ICE_SLIPPERINESS, 1.0, false, false) : simConfig);
            if (span >= 5.05) {
                base = 5;
            }
        }
        return base;
    }
}
