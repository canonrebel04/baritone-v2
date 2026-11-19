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
import baritone.api.process.PathingCommand;
import baritone.api.utils.Rotation;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * A45: eat when hungry. Picks the most filling safe food (never golden apples, chorus fruit, rotten flesh, spider
 * eyes, pufferfish, poisonous potatoes, suspicious stew or protected items), moves it to the hotbar if needed and
 * holds "use" while looking up (so nothing in front gets right-clicked).
 */
public final class EatTask extends StepTask {
    public static final Set<String> NEVER = Set.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple",
            "minecraft:chorus_fruit", "minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:pufferfish",
            "minecraft:poisonous_potato", "minecraft:suspicious_stew");
    private final GuiPacer pacer = new GuiPacer();
    private int startFood = -1;
    private int prevSlot = -1;
    private boolean holding;

    public EatTask(Baritone baritone, GameData data) {
        super(baritone, data, PlanStep.withdraw("minecraft:air", 0, 0, 0));
    }

    /** Inventory index of the best food, or -1. */
    public static int bestFood(net.minecraft.world.entity.player.Player p, Set<String> prot) {
        var inv = p.getInventory().getNonEquipmentItems();
        int best = -1, bestNut = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) continue;
            var food = s.get(DataComponents.FOOD);
            String id = GameData.id(s.getItem());
            if (food == null || NEVER.contains(id) || prot.contains(id)) continue;
            int n = food.nutrition() * 2 + (i < 9 ? 1 : 0);
            if (n > bestNut) {
                bestNut = n;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected PathingCommand onTick() {
        var p = ctx.player();
        int food = p.getFoodData().getFoodLevel();
        if (startFood < 0) startFood = food;
        if (food > startFood || food >= 20 || ticks > 20 * 8) return done();
        if (ctx.minecraft().gui.screen() != null) return pause();
        int slot = bestFood(p, data.protectedItems());
        if (slot < 0) return done();
        if (slot >= 9) {
            if (pacer.ready()) {
                ctx.playerController().windowClick(p.inventoryMenu.containerId, slot, 8, ContainerInput.SWAP, p);
            }
            return pause();
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
            p.getInventory().setSelectedSlot(slot);
            ctx.playerController().syncHeldItem();
            return pause();
        }
        baritone.getLookBehavior().updateTarget(new Rotation(ctx.playerRotations().getYaw(), -80), true);
        if (ctx.playerRotations().getPitch() > -60) return pause();
        ctx.minecraft().options.keyUse.setDown(true);
        holding = true;
        return pause();
    }

    private PathingCommand done() {
        release();
        return finish();
    }

    private void release() {
        if (holding) {
            ctx.minecraft().options.keyUse.setDown(false);
            holding = false;
        }
        if (prevSlot >= 0 && ctx.player() != null) {
            ctx.player().getInventory().setSelectedSlot(prevSlot);
            ctx.playerController().syncHeldItem();
            prevSlot = -1;
        }
    }

    @Override
    public void stop() {
        release();
    }

    @Override
    public String status() {
        return "eating";
    }
}
