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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared shape of the M4 station screens (stonecutter, smithing table, brewing stand, anvil, enchanting table,
 * grindstone): walk to the station and open it ({@link StationAccess}), let the slots sync, run the subclass's
 * paced clicks, take back whatever is left in the station's slots, close with a human close delay. All clicks go
 * through the shared pacing layer ({@link GuiPacer}).
 */
abstract class StationMenuTask<M extends AbstractContainerMenu> extends StepTask {
    protected final String station;
    protected final GuiPacer pacer = new GuiPacer();
    private final Class<M> type;
    private final Set<BlockPos> blacklist;
    private final int stationSlots;
    private StationAccess access;
    private boolean open;
    private int openTicks;
    private int reopen;
    private boolean closing;
    private boolean ok;
    private String closeWhy;
    private int closeWait = -1;

    StationMenuTask(Baritone baritone, GameData data, PlanStep step, String station, Class<M> type, int stationSlots, Set<BlockPos> blacklist) {
        super(baritone, data, step);
        this.station = station;
        this.type = type;
        this.stationSlots = stationSlots;
        this.blacklist = blacklist;
    }

    protected final M menu() {
        AbstractContainerMenu m = ctx.player().containerMenu;
        return type.isInstance(m) ? type.cast(m) : null;
    }

    protected final BlockPos stationPos() {
        return access == null ? null : access.pos();
    }

    /** Ticks since the screen opened. */
    protected final int openTicks() {
        return openTicks;
    }

    @Override
    protected final PathingCommand onTick() {
        M m = menu();
        if (closing) {
            if (m != null) {
                // take back what's left in the station's own slots first
                for (int i = 0; i < stationSlots; i++) {
                    if (!m.getSlot(i).getItem().isEmpty() && keepInStation(i)) continue;
                    if (!m.getSlot(i).getItem().isEmpty()) {
                        if (pacer.ready(i, 9)) ctx.playerController().windowClick(m.containerId, i, 0, ContainerInput.QUICK_MOVE, ctx.player());
                        return pause();
                    }
                }
                if (!m.getCarried().isEmpty()) {
                    if (pacer.ready()) putAway(m);
                    return pause();
                }
                if (closeWait < 0) closeWait = pacer.closeDelay();
                if (closeWait-- > 0) return pause();
                ctx.player().closeContainer();
            }
            return ok ? finish() : fail(closeWhy);
        }
        if (m == null) {
            if (open) {
                open = false;
                if (++reopen > 4) return fail(shortStation() + " screen closed unexpectedly");
            }
            if (access == null) access = new StationAccess(baritone, data, station, blacklist);
            StringBuilder why = new StringBuilder();
            PathingCommand cmd = access.approach(why);
            if (why.length() > 0) return fail(why.toString());
            if (cmd != null) return cmd;
            if (access.clicks() > 4) return fail(shortStation() + " didn't open");
            return pause();
        }
        if (!open) {
            open = true;
            openTicks = 0;
            pacer.opened(stationSlots);
        }
        if (openTicks++ < 3) return pause(); // let the slots sync
        return work(m);
    }

    /** One tick of station work with the screen open; end with {@link #succeed()} or {@link #abort(String)}. */
    protected abstract PathingCommand work(M m);

    /** Leave this station slot's item where it is when closing (e.g. brewing stand fuel). */
    protected boolean keepInStation(int slot) {
        return false;
    }

    protected final PathingCommand succeed() {
        closing = true;
        ok = true;
        return pause();
    }

    protected final PathingCommand abort(String why) {
        closing = true;
        ok = false;
        closeWhy = why;
        return pause();
    }

    protected final String shortStation() {
        return station.replace("minecraft:", "").replace('_', ' ');
    }

    protected final boolean isPlayerSlot(Slot s) {
        return s.container instanceof Inventory;
    }

    /** Menu slot index showing player inventory index {@code invIndex}, or -1. */
    protected final int menuSlotOf(M m, int invIndex) {
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (isPlayerSlot(s) && s.getContainerSlot() == invIndex) return i;
        }
        return -1;
    }

    /**
     * One paced click towards {@code count} items matching {@code match} in station slot {@code slot} (from the
     * player's slots; whole stack when it fits, single right-clicks otherwise).
     *
     * @return 1 when the slot is filled, 0 when a click was made or the pacer is waiting, -1 when nothing matching is left
     */
    protected final int fill(M m, int slot, Predicate<ItemStack> match, int count) {
        ItemStack inSlot = m.getSlot(slot).getItem();
        ItemStack carried = m.getCarried();
        if (!inSlot.isEmpty() && !match.test(inSlot)) {
            if (pacer.ready(slot, 9)) ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
            return 0;
        }
        int need = count - inSlot.getCount();
        if (need <= 0) {
            if (!carried.isEmpty()) {
                if (pacer.ready()) putAway(m);
                return 0;
            }
            return 1;
        }
        if (!pacer.ready(slot, 9)) return 0;
        if (carried.isEmpty()) {
            int src = -1;
            for (int i = 0; i < m.slots.size(); i++) {
                Slot s = m.slots.get(i);
                if (isPlayerSlot(s) && !s.getItem().isEmpty() && match.test(s.getItem())) {
                    src = i;
                    break;
                }
            }
            if (src < 0) return -1;
            ctx.playerController().windowClick(m.containerId, src, 0, ContainerInput.PICKUP, ctx.player());
            return 0;
        }
        if (!match.test(carried)) {
            putAway(m);
            return 0;
        }
        ctx.playerController().windowClick(m.containerId, slot, carried.getCount() <= need ? 0 : 1, ContainerInput.PICKUP, ctx.player());
        return 0;
    }

    /** Move a player stack (by menu slot) into the station with one paced click on an empty station slot. */
    protected final boolean place(M m, int from, int to) {
        if (!pacer.ready(to, 9)) return false;
        ItemStack carried = m.getCarried();
        if (carried.isEmpty()) {
            ctx.playerController().windowClick(m.containerId, from, 0, ContainerInput.PICKUP, ctx.player());
        } else {
            ctx.playerController().windowClick(m.containerId, to, 0, ContainerInput.PICKUP, ctx.player());
        }
        return true;
    }

    /** Shift-click a slot (paced). */
    protected final boolean quick(M m, int slot) {
        if (!pacer.ready(slot, 9)) return false;
        ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
        return true;
    }

    /** Put the carried stack into the player inventory (merge first, else an empty slot). */
    protected final void putAway(M m) {
        ItemStack carried = m.getCarried();
        if (carried.isEmpty()) return;
        int target = -1;
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (!isPlayerSlot(s)) continue;
            ItemStack it = s.getItem();
            if (it.isEmpty() && target < 0) target = i;
            if (!it.isEmpty() && ItemStack.isSameItemSameComponents(it, carried) && it.getCount() < it.getMaxStackSize()) {
                target = i;
                break;
            }
        }
        if (target >= 0) ctx.playerController().windowClick(m.containerId, target, 0, ContainerInput.PICKUP, ctx.player());
    }

    protected static String id(ItemStack s) {
        return s.isEmpty() ? null : GameData.id(s.getItem());
    }

    @Override
    public void stop() {
        if (menu() != null) ctx.player().closeContainer();
    }
}
