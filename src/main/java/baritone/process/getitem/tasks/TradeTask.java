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
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * A28: walk to the remembered villager, open its trade screen and run the planned trade the planned number of
 * times, one result at a time (exact count, no shift-click over-trading). Every click is paced by the shared GUI
 * pacer (S40/S41). Sold-out or changed offers end the step so the process re-plans with fresh offers.
 */
public final class TradeTask extends GatherTask {
    private final GetItemKnowledge knowledge;
    private final UUID uuid;
    private final int offerIndex;
    private final BlockPos remembered;
    private final GuiPacer pacer = new GuiPacer();
    private boolean opened, selected;
    private int index = -1;
    private int trades;
    private int lookTicks;
    private int closeIn = -1;
    private int openClicks;

    public TradeTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, PlanStep step) {
        super(baritone, data, step);
        this.knowledge = knowledge;
        String[] ref = step.source.ref.split("\\|");
        this.uuid = UUID.fromString(ref[0]);
        this.offerIndex = Integer.parseInt(ref[1]);
        this.remembered = parsePos(ref[2]);
    }

    private AbstractVillager find() {
        for (Entity e : ctx.world().getEntities((Entity) null, new AABB(ctx.playerFeet()).inflate(96), x -> x instanceof AbstractVillager && x.isAlive())) {
            if (e.getUUID().equals(uuid)) return (AbstractVillager) e;
        }
        return null;
    }

    @Override
    protected PathingCommand onTick() {
        if (ctx.player().containerMenu instanceof MerchantMenu m) return trading(m);
        if (opened) {
            return trades > 0 || reached() ? finish() : fail("the trade screen closed before trading");
        }
        AbstractVillager v = find();
        if (v == null) {
            if (ctx.playerFeet().distSqr(remembered) > 12 * 12) return walkNear(remembered, 3);
            return ticks > 400 ? fail("the villager at " + remembered.toShortString() + " isn't there any more") : pause();
        }
        if (ctx.player().distanceTo(v) > 2.8) {
            if (ticks > 2400) return fail("couldn't reach the villager");
            return follow(v, 1);
        }
        if (baritone.getPathingBehavior().isPathing()) return pause();
        look(v.getEyePosition().subtract(0, 0.3, 0));
        if (++lookTicks >= 3 && interact(v)) {
            openClicks++;
            lookTicks = -15;
        }
        if (openClicks > 4) return fail("the villager wouldn't trade (busy or a baby?)");
        return pause();
    }

    private PathingCommand trading(MerchantMenu m) {
        if (!opened) {
            opened = true;
            pacer.opened(m.getOffers().size());
            index = matchOffer(m);
            if (index < 0) {
                ctx.player().closeContainer();
                return fail("the villager doesn't offer " + step.items.get(0).replace("minecraft:", "") + " any more");
            }
        }
        if (closeIn >= 0) {
            if (closeIn-- == 0) {
                ctx.player().closeContainer();
                return finish();
            }
            return pause();
        }
        ItemStack carried = m.getCarried();
        if (!carried.isEmpty()) {
            int slot = stashSlot(m, carried);
            if (slot < 0) {
                closeIn = pacer.closeDelay();
                return pause();
            }
            if (pacer.ready(slot, 9)) ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.PICKUP, ctx.player());
            return pause();
        }
        MerchantOffer offer = m.getOffers().get(index);
        if (trades >= step.batches || reached() || offer.isOutOfStock()) {
            closeIn = pacer.closeDelay();
            return pause();
        }
        if (!selected) {
            if (!pacer.ready(index, 1)) return pause();
            // same as clicking the offer button: fill the payment slots and tell the server
            m.setSelectionHint(index);
            m.tryMoveItems(index);
            ctx.player().connection.send(new ServerboundSelectTradePacket(index));
            selected = true;
            return pause();
        }
        if (m.getSlot(2).getItem().isEmpty()) {
            if (trades == 0 && ticks > 200) {
                ctx.player().closeContainer();
                return fail("couldn't pay for the trade");
            }
            if (trades > 0 && pacer.ready()) selected = false; // payment slots ran dry: refill from the inventory
            return pause();
        }
        if (pacer.ready(2, 9)) {
            ctx.playerController().windowClick(m.containerId, 2, 0, ContainerInput.PICKUP, ctx.player());
            trades++;
        }
        return pause();
    }

    private int matchOffer(MerchantMenu m) {
        String want = step.items.get(0);
        String costA = step.inputs.isEmpty() ? null : step.inputs.keySet().iterator().next();
        var offers = m.getOffers();
        if (offerIndex < offers.size() && matches(offers.get(offerIndex), want, costA)) return offerIndex;
        for (int i = 0; i < offers.size(); i++) if (matches(offers.get(i), want, costA)) return i;
        return -1;
    }

    private static boolean matches(MerchantOffer o, String want, String costA) {
        return !o.isOutOfStock() && GameData.id(o.getResult().getItem()).equals(want)
                && (costA == null || GameData.id(o.getCostA().getItem()).equals(costA)
                || o.getCostB().isEmpty() || GameData.id(o.getCostB().getItem()).equals(costA));
    }

    /** Player inventory slot (menu index) that can take the carried stack: a matching stack, else empty. */
    static int stashSlot(net.minecraft.world.inventory.AbstractContainerMenu m, ItemStack carried) {
        int empty = -1;
        for (int i = 3; i < m.slots.size(); i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (s.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (ItemStack.isSameItemSameComponents(s, carried) && s.getCount() + carried.getCount() <= s.getMaxStackSize()) {
                return i;
            }
        }
        return empty;
    }

    @Override
    public void stop() {
        if (ctx.player() != null && ctx.player().containerMenu instanceof MerchantMenu) ctx.player().closeContainer();
    }
}
