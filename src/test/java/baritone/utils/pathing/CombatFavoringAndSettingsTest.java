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

package baritone.utils.pathing;

import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.process.IElytraProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.CombatFavoringStorage;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.precompute.PrecomputedData;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongToDoubleFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CombatFavoringAndSettingsTest {

    private static final class StubPrecomputedData extends PrecomputedData {
        @Override
        public boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
            return state.getBlock() != Blocks.AIR;
        }

        @Override
        public boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
            return state.getBlock() == Blocks.AIR;
        }
    }

    private static final class StubCalculationContext extends CalculationContext {
        private final Map<Long, BlockState> worldMap = new HashMap<>();

        StubCalculationContext(boolean combatMode, double footingFavor, boolean disallowSlowBreak) {
            super(new StubPrecomputedData(), combatMode, footingFavor, disallowSlowBreak);
        }

        void set(int x, int y, int z, Block block) {
            worldMap.put(BetterBlockPos.longHash(x, y, z), block.defaultBlockState());
        }

        @Override
        public BlockState get(int x, int y, int z) {
            return worldMap.getOrDefault(BetterBlockPos.longHash(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void cleanupSuppliers() {
        CombatFavoringStorage.set(null);
        Favoring.combatFavoringSupplier = null;
    }

    @Test
    public void combatSettingsExistWithExpectedDefaults() throws Exception {
        Constructor<Settings> ctor = Settings.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Settings settings = ctor.newInstance();

        assertEquals(0.78D, settings.combatBlastResistantFootingFavor.value, 1e-9);
        assertTrue(settings.combatDisallowSlowBreak.value);
        assertEquals(8, settings.followLeadTicks.value.intValue());
        assertNotNull(settings.byLowerName.get("combatblastresistantfootingfavor"));
        assertNotNull(settings.byLowerName.get("combatdisallowslowbreak"));
        assertNotNull(settings.byLowerName.get("followleadticks"));
    }

    @Test
    public void combatFavoringSupplierAffectsFavoringEmptyAndCalculate() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", false, getClass().getClassLoader());
        Method setter = apiClass.getMethod("setCombatFavoringSupplier", LongToDoubleFunction.class);
        Method getter = apiClass.getMethod("getCombatFavoringSupplier");
        assertTrue(Modifier.isPublic(setter.getModifiers()) && Modifier.isStatic(setter.getModifiers()));
        assertTrue(Modifier.isPublic(getter.getModifiers()) && Modifier.isStatic(getter.getModifiers()));
        assertEquals(LongToDoubleFunction.class, getter.getReturnType());

        StubCalculationContext ctx = new StubCalculationContext(true, 0.78D, true);
        Favoring favoring = new Favoring(null, ctx);

        assertNull(CombatFavoringStorage.get());
        assertTrue(favoring.isEmpty());

        long targetHash = BetterBlockPos.longHash(10, 64, -5);
        long badNanHash = BetterBlockPos.longHash(11, 64, -5);
        long badNegHash = BetterBlockPos.longHash(12, 64, -5);
        long badZeroHash = BetterBlockPos.longHash(13, 64, -5);
        long badInfHash = BetterBlockPos.longHash(14, 64, -5);

        LongToDoubleFunction supplier = h -> {
            if (h == targetHash) return 2.5D;
            if (h == badNanHash) return Double.NaN;
            if (h == badNegHash) return -1.5D;
            if (h == badZeroHash) return 0.0D;
            if (h == badInfHash) return Double.POSITIVE_INFINITY;
            return 0.78D;
        };

        CombatFavoringStorage.set(supplier);
        assertEquals(supplier, CombatFavoringStorage.get());
        assertFalse(favoring.isEmpty());

        assertEquals(2.5D, favoring.calculate(10, 64, -5, targetHash), 1e-9);
        assertEquals(0.78D, favoring.calculate(0, 64, 0, BetterBlockPos.longHash(0, 64, 0)), 1e-9);
        // Non-finite or non-positive values must be ignored safely
        assertEquals(1.0D, favoring.calculate(11, 64, -5, badNanHash), 1e-9);
        assertEquals(1.0D, favoring.calculate(12, 64, -5, badNegHash), 1e-9);
        assertEquals(1.0D, favoring.calculate(13, 64, -5, badZeroHash), 1e-9);
        assertEquals(1.0D, favoring.calculate(14, 64, -5, badInfHash), 1e-9);

        CombatFavoringStorage.set(null);
        assertTrue(favoring.isEmpty());
    }

    @Test
    public void calculationContextBreakCostDisallowsSlowBlocksInCombatOnly() {
        StubCalculationContext combatDisallow = new StubCalculationContext(true, 0.78D, true);
        assertEquals(ActionCosts.COST_INF, combatDisallow.breakCostMultiplierAt(0, 64, 0, Blocks.STONE.defaultBlockState()), 1e-9);
        assertEquals(ActionCosts.COST_INF, combatDisallow.breakCostMultiplierAt(0, 64, 0, Blocks.OBSIDIAN.defaultBlockState()), 1e-9);
        assertEquals(ActionCosts.COST_INF, combatDisallow.breakCostMultiplierAt(0, 64, 0, Blocks.DIRT.defaultBlockState()), 1e-9);
        assertEquals(1.0D, combatDisallow.breakCostMultiplierAt(0, 64, 0, Blocks.SHORT_GRASS.defaultBlockState()), 1e-9);
        assertEquals(1.0D, combatDisallow.breakCostMultiplierAt(0, 64, 0, Blocks.OAK_LEAVES.defaultBlockState()), 1e-9);

        StubCalculationContext combatAllow = new StubCalculationContext(true, 0.78D, false);
        assertEquals(1.0D, combatAllow.breakCostMultiplierAt(0, 64, 0, Blocks.STONE.defaultBlockState()), 1e-9);

        StubCalculationContext nonCombat = new StubCalculationContext(false, 0.78D, true);
        assertEquals(1.0D, nonCombat.breakCostMultiplierAt(0, 64, 0, Blocks.STONE.defaultBlockState()), 1e-9);
    }

    @Test
    public void movementTraverseFavorsBlastResistantFootingInCombatMode() {
        Block[] blastResistant = {
            Blocks.BEDROCK,
            Blocks.OBSIDIAN,
            Blocks.CRYING_OBSIDIAN,
            Blocks.REINFORCED_DEEPSLATE
        };

        StubCalculationContext combatCtx = new StubCalculationContext(true, 0.78D, true);
        combatCtx.set(0, 63, 0, Blocks.STONE);
        combatCtx.set(1, 63, 0, Blocks.STONE);
        double normalCombatCost = MovementTraverse.cost(combatCtx, 0, 64, 0, 1, 0);

        for (Block footing : blastResistant) {
            combatCtx.set(1, 63, 0, footing);
            double favoredCost = MovementTraverse.cost(combatCtx, 0, 64, 0, 1, 0);
            assertEquals("Expected 0.78x cost on " + footing, normalCombatCost * 0.78D, favoredCost, 1e-6);
        }

        StubCalculationContext nonCombatCtx = new StubCalculationContext(false, 0.78D, true);
        nonCombatCtx.set(0, 63, 0, Blocks.STONE);
        nonCombatCtx.set(1, 63, 0, Blocks.STONE);
        double normalCost = MovementTraverse.cost(nonCombatCtx, 0, 64, 0, 1, 0);
        nonCombatCtx.set(1, 63, 0, Blocks.OBSIDIAN);
        double nonCombatObsidianCost = MovementTraverse.cost(nonCombatCtx, 0, 64, 0, 1, 0);
        assertEquals(normalCost, nonCombatObsidianCost, 1e-6);

        StubCalculationContext nanFavorCtx = new StubCalculationContext(true, Double.NaN, true);
        nanFavorCtx.set(0, 63, 0, Blocks.STONE);
        nanFavorCtx.set(1, 63, 0, Blocks.OBSIDIAN);
        assertEquals(normalCombatCost, MovementTraverse.cost(nanFavorCtx, 0, 64, 0, 1, 0), 1e-6);
    }

    @Test
    public void elytraFollowEntityContractPresent() throws Exception {
        Method followEntityApi = IElytraProcess.class.getMethod("followEntity", Entity.class);
        assertTrue(followEntityApi.isDefault());

        Method followEntityImpl = ElytraProcess.class.getMethod("followEntity", Entity.class);
        assertEquals(ElytraProcess.class, followEntityImpl.getDeclaringClass());
    }
}
