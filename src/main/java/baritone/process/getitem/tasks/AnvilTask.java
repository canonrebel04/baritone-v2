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
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.inventory.AnvilMenu;

import java.util.Collections;
import java.util.Set;

/**
 * A25: one anvil use: combine the item with a book or a second copy, or rename it. The real level cost is read
 * from the screen and refused when it is at or above {@code getItemMaxAnvilCost} or more than the player has.
 */
public final class AnvilTask extends StationMenuTask<AnvilMenu> {
    private final int baseInv;
    private final int otherInv;
    private final String name;
    private boolean named;
    private int resultWait;

    public AnvilTask(Baritone baritone, GameData data, String item, int baseInv, int otherInv, String name, Set<BlockPos> blacklist) {
        super(baritone, data, PlanStep.operate(name != null ? "Rename " + item.replace("minecraft:", "") + " to \"" + name + "\" on an anvil"
                        : "Combine " + item.replace("minecraft:", "") + " on an anvil", "minecraft:anvil", Collections.singletonList(item), 1),
                "minecraft:anvil", AnvilMenu.class, 2, blacklist);
        this.baseInv = baseInv;
        this.otherInv = otherInv;
        this.name = name;
    }

    @Override
    protected PathingCommand work(AnvilMenu m) {
        if (m.getSlot(0).getItem().isEmpty()) {
            int from = menuSlotOf(m, baseInv);
            if (from < 0 || m.getCarried().isEmpty() && m.getSlot(from).getItem().isEmpty()) return abort("the item to work on moved");
            place(m, from, 0);
            return pause();
        }
        if (otherInv >= 0 && m.getSlot(1).getItem().isEmpty()) {
            int from = menuSlotOf(m, otherInv);
            if (from < 0 || m.getCarried().isEmpty() && m.getSlot(from).getItem().isEmpty()) return abort("the book moved");
            place(m, from, 1);
            return pause();
        }
        if (!m.getCarried().isEmpty()) {
            if (pacer.ready()) putAway(m);
            return pause();
        }
        if (name != null && !named) {
            if (!pacer.ready()) return pause();
            m.setItemName(name);
            ctx.player().connection.send(new ServerboundRenameItemPacket(name));
            named = true;
            return pause();
        }
        if (m.getSlot(2).getItem().isEmpty()) {
            if (++resultWait > 60) return abort("the anvil won't combine these");
            return pause();
        }
        int cost = m.getCost();
        if (cost >= Baritone.settings().getItemMaxAnvilCost.value + 1 || cost >= 40) return abort("the anvil wants " + cost + " levels (too expensive)");
        if (!ctx.player().getAbilities().instabuild && ctx.player().experienceLevel < cost) {
            return abort("the anvil needs " + cost + " levels (have " + ctx.player().experienceLevel + ")");
        }
        quick(m, 2);
        return succeed();
    }

    @Override
    public String status() {
        return step.label;
    }
}
