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
import baritone.process.getitem.GameData;
import baritone.process.getitem.advanced.EnchantPlanner;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A25: enchanting table. Puts the item and up to 3 lapis in, reads the three offers (cost and the shown enchant
 * clue) and takes an offer showing a wanted enchantment; otherwise the cheapest one when a reroll is allowed
 * (the grindstone cleans it afterwards and the offers change), else gives up.
 */
public final class EnchantTask extends StationMenuTask<EnchantmentMenu> {
    private static final String LAPIS = "minecraft:lapis_lazuli";
    private final int baseInv;
    private final Map<String, Integer> wanted;
    private final boolean reroll;
    private boolean clicked;
    private int offerWait;

    public EnchantTask(Baritone baritone, GameData data, String item, int baseInv, Map<String, Integer> wanted, boolean reroll, Set<BlockPos> blacklist) {
        super(baritone, data, PlanStep.operate("Enchant " + item.replace("minecraft:", "") + " on an enchanting table", "minecraft:enchanting_table",
                Collections.singletonList(item), 1), "minecraft:enchanting_table", EnchantmentMenu.class, 2, blacklist);
        this.baseInv = baseInv;
        this.wanted = wanted;
        this.reroll = reroll;
    }

    @Override
    protected PathingCommand work(EnchantmentMenu m) {
        if (clicked) {
            if (!m.getSlot(0).getItem().getEnchantments().isEmpty() || m.getSlot(0).getItem().is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) return succeed();
            if (++offerWait > 60) return abort("the enchanting table didn't enchant");
            return pause();
        }
        if (m.getSlot(0).getItem().isEmpty()) {
            int from = menuSlotOf(m, baseInv);
            if (from < 0 || m.getCarried().isEmpty() && m.getSlot(from).getItem().isEmpty()) return abort("the item to enchant moved");
            place(m, from, 0);
            return pause();
        }
        int lapisHave = data.count(Collections.singletonList(LAPIS)) + m.getSlot(1).getItem().getCount();
        int r = fill(m, 1, s -> LAPIS.equals(id(s)), Math.min(3, lapisHave));
        if (r < 0 && m.getSlot(1).getItem().isEmpty()) return abort("no lapis lazuli");
        if (r == 0) return pause();
        if (m.costs[0] <= 0 && m.costs[1] <= 0 && m.costs[2] <= 0) {
            if (++offerWait > 60) return abort("the enchanting table offers nothing for this item");
            return pause();
        }
        String[] clue = new String[3];
        int[] clueLevel = new int[3];
        var reg = ctx.world().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
        for (int i = 0; i < 3; i++) {
            if (m.enchantClue[i] < 0) continue;
            Holder<Enchantment> h = reg.byId(m.enchantClue[i]);
            if (h != null) clue[i] = h.unwrapKey().map(k -> k.identifier().getPath()).orElse(null);
            clueLevel[i] = m.levelClue[i];
        }
        int levels = ctx.player().getAbilities().instabuild ? 1000 : ctx.player().experienceLevel;
        int lapis = m.getSlot(1).getItem().getCount();
        int pick = EnchantPlanner.pickOffer(wanted, clue, clueLevel, m.costs, levels, lapis);
        if (pick < 0) {
            if (!reroll) return abort("the enchanting table doesn't offer " + wanted.keySet());
            pick = EnchantPlanner.cheapestOffer(m.costs, levels, lapis);
            if (pick < 0) return abort("not enough levels for any enchanting offer");
        }
        if (!pacer.ready()) return pause();
        ctx.minecraft().gameMode.handleInventoryButtonClick(m.containerId, pick);
        clicked = true;
        offerWait = 0;
        return pause();
    }

    @Override
    public String status() {
        return step.label;
    }
}
