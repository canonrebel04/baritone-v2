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
import baritone.process.getitem.GameData;
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * A24: take planned items from one of your unprotected containers. Picks the nearest usable container holding the
 * step's item and, while it's open, also takes the other items of the plan's withdrawal list that it holds (one
 * trip). Protected items are never clicked. Whole stacks are shift-clicked; the last, partial one is split with
 * {@link PartialTake} so exactly the planned count is taken (every click paced, S40).
 */
public final class WithdrawTask extends ContainerTask {
    private final Map<String, Integer> want;
    private final Map<String, Integer> start = new HashMap<>();
    private final Set<String> protectedItems;
    private final String item;
    private final Deque<PartialTake.Click> pending = new ArrayDeque<>();

    public WithdrawTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, PlanStep step, BlockPos pos, Map<String, Integer> alsoTake) {
        super(baritone, data, knowledge, step, pos);
        this.item = step.items.get(0);
        this.want = new LinkedHashMap<>();
        want.put(item, step.count);
        alsoTake.forEach((k, v) -> { if (!k.equals(item)) want.merge(k, v, Integer::sum); });
        this.protectedItems = data.protectedItems();
        Map<String, Integer> inv = data.inventory();
        for (String k : want.keySet()) start.put(k, inv.getOrDefault(k, 0));
        if (pos == null) setFailure("no unprotected container with " + item.replace("minecraft:", "") + " known nearby");
    }

    private int taken(String k) {
        return data.inventory().getOrDefault(k, 0) - start.getOrDefault(k, 0);
    }

    @Override
    protected boolean busyWithCursor() {
        return !pending.isEmpty();
    }

    @Override
    protected boolean work(AbstractContainerMenu m) {
        int size = containerSize(m);
        if (!pending.isEmpty()) {
            PartialTake.Click c = pending.peek();
            if (!pacer.ready(c.slot, 9)) return false;
            pending.poll();
            ctx.playerController().windowClick(m.containerId, c.slot, c.button, ContainerInput.PICKUP, ctx.player());
            return false;
        }
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            String k = e.getKey();
            if (protectedItems.contains(k) || taken(k) >= e.getValue()) continue;
            for (int i = 0; i < size; i++) {
                ItemStack s = m.getSlot(i).getItem();
                if (!s.isEmpty() && GameData.id(s.getItem()).equals(k)) {
                    if (!hasRoom(m, size, s)) {
                        setFailure("inventory full while taking " + k.replace("minecraft:", ""));
                        return true;
                    }
                    int need = e.getValue() - taken(k);
                    if (need < s.getCount() && Baritone.settings().getItemExactWithdraw.value) {
                        int to = emptySlot(m, size);
                        if (to >= 0) {
                            pending.addAll(PartialTake.clicks(i, to, s.getCount(), need));
                            return false;
                        }
                    }
                    if (!pacer.ready(i, 9)) return false; // S40: includes cursor travel to slot i
                    ctx.playerController().windowClick(m.containerId, i, 0, ContainerInput.QUICK_MOVE, ctx.player());
                    return false;
                }
            }
        }
        return true;
    }

    private static int emptySlot(AbstractContainerMenu m, int size) {
        // prefer the hotbar-less main inventory first, like a player dragging into the grid
        for (int i = size; i < m.slots.size(); i++) if (m.getSlot(i).getItem().isEmpty()) return i;
        return -1;
    }

    private static boolean hasRoom(AbstractContainerMenu m, int size, ItemStack s) {
        for (int i = size; i < m.slots.size(); i++) {
            ItemStack p = m.getSlot(i).getItem();
            if (p.isEmpty() || ItemStack.isSameItemSameComponents(p, s) && p.getCount() < p.getMaxStackSize()) return true;
        }
        return false;
    }

    @Override
    protected boolean succeeded() {
        if (taken(item) <= 0) {
            setFailure("the container didn't have " + item.replace("minecraft:", "") + " any more");
            return false;
        }
        return true;
    }

    @Override
    public String status() {
        return "taking " + step.count + "x " + item.replace("minecraft:", "") + " from a chest" + (access != null ? " at " + access.pos.toShortString() : "");
    }
}
