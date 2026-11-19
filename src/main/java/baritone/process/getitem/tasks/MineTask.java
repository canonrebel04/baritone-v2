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
import baritone.process.getitem.inventory.ToolDurability;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import baritone.utils.ToolSet;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A21: mine via {@code MineProcess} until the inventory holds the step's target. Get Item defers while mining so
 * MineProcess drives; tool choice avoids Silk Touch when the plan wants the normal drop, prefers Fortune on ores,
 * and never uses protected tools.
 */
public final class MineTask extends StepTask {
    private final int target;
    private int restarts;
    private boolean started;
    private int toolWait;
    private final Set<Block> wantDropNotBlock = new HashSet<>();

    public MineTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.target = data.count(step.items) + step.count;
        for (String b : step.blocks) {
            Block blk = GameData.block(b);
            if (blk != null && !step.items.contains(GameData.id(blk.asItem()))) wantDropNotBlock.add(blk);
        }
    }

    @Override
    protected PathingCommand onTick() {
        if (data.count(step.items) >= target) {
            baritone.getMineProcess().cancel();
            return finish();
        }
        if (!started) {
            if (step.tool != null && !ensureToolOnHotbar()) {
                if (++toolWait > 100) return fail("couldn't move " + step.tool + " to the hotbar");
                return pause();
            }
            installToolHooks();
            List<Block> blocks = new ArrayList<>();
            for (String b : step.blocks) {
                Block blk = GameData.block(b);
                if (blk != null) blocks.add(blk);
            }
            if (blocks.isEmpty()) return fail("unknown blocks " + step.blocks);
            baritone.getMineProcess().mine(0, new BlockOptionalMetaLookup(blocks));
            started = true;
            return defer();
        }
        if (!baritone.getMineProcess().isActive()) {
            if (++restarts > 2) {
                return fail("couldn't find or reach " + shortList() + " nearby");
            }
            started = false;
            return pause();
        }
        return defer();
    }

    private String shortList() {
        String first = step.blocks.get(0).replace("minecraft:", "");
        return step.blocks.size() > 1 ? first + " (or " + (step.blocks.size() - 1) + " similar)" : first;
    }

    /** The planned tool (or any harvesting tool) must be on the hotbar; MineProcess only uses hotbar tools. */
    private boolean ensureToolOnHotbar() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        Set<String> prot = data.protectedItems();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && GameData.id(s.getItem()).equals(step.tool)) return true;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && GameData.id(s.getItem()).equals(step.tool) && !prot.contains(step.tool)) {
                baritone.getInventoryBehavior().attemptToPutOnHotbar(i, slot -> {
                    ItemStack h = inv.get(slot);
                    return !h.isEmpty() && h.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL);
                });
                return false;
            }
        }
        return true; // not in inventory any more: let MineProcess use whatever is best
    }

    private void installToolHooks() {
        Set<String> prot = data.protectedItems();
        int minLeft = Baritone.settings().getItemMinToolDurability.value;
        // protected items and worn tools (PA2 follow-up): a nearly broken / mending-reserved tool is never picked to mine
        ToolSet.excludedTools = s -> prot.contains(GameData.id(s.getItem()))
                || (s.isDamageableItem() && !ToolDurability.usable(
                s.getDamageValue(), s.getMaxDamage(), GameData.hasMending(s), minLeft, 0.15));
        ToolSet.dropPreference = (block, stack) -> {
            boolean silk = has(stack, Enchantments.SILK_TOUCH);
            if (wantDropNotBlock.contains(block)) {
                if (silk) return -1;
                String id = GameData.id(block);
                boolean ore = id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
                return ore && has(stack, Enchantments.FORTUNE) ? 1 : 0;
            }
            return 0;
        };
    }

    private static boolean has(ItemStack stack, net.minecraft.resources.ResourceKey<Enchantment> key) {
        ItemEnchantments e = stack.getEnchantments();
        for (Holder<Enchantment> h : e.keySet()) if (h.is(key) && e.getLevel(h) > 0) return true;
        return false;
    }

    public static void clearToolHooks() {
        ToolSet.excludedTools = null;
        ToolSet.dropPreference = null;
    }

    @Override
    public void stop() {
        baritone.getMineProcess().cancel();
        clearToolHooks();
    }

    @Override
    public String status() {
        return "mining " + shortList() + " " + Math.min(data.count(step.items), target) + "/" + target;
    }
}
