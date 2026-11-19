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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A23: smelt in a furnace / blast furnace / smoker. Loads exactly the planned inputs and fuel with legal clicks
 * (whole stacks when they fit, single right-clicks otherwise), collects output as it cooks (XP comes with it),
 * then takes back leftover input and fuel so nothing is wasted. Furnaces holding someone else's items are left
 * alone (blacklisted) and another one is used or placed.
 */
public final class SmeltTask extends StepTask {
    private enum Stage { OPEN, CHECK, LOAD, WAIT, UNLOAD, CLOSE }

    private final String station;
    private final String input;
    private final int target;
    private final GuiPacer pacer = new GuiPacer();
    private final Set<BlockPos> blacklist;
    private StationAccess access;
    private Stage stage = Stage.OPEN;
    private int stageTicks;
    private int inputLoaded, fuelLoaded;
    private int reopen;

    public SmeltTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step);
        this.station = step.station();
        String in = null;
        for (Map.Entry<String, Integer> e : step.inputs.entrySet()) in = e.getKey();
        this.input = in;
        this.target = data.count(step.items) + step.count;
        this.blacklist = blacklist;
    }

    private AbstractFurnaceMenu menu() {
        AbstractContainerMenu m = ctx.player().containerMenu;
        return m instanceof AbstractFurnaceMenu f ? f : null;
    }

    private int have() {
        return data.count(step.items);
    }

    private static String id(ItemStack s) {
        return s.isEmpty() ? null : GameData.id(s.getItem());
    }

    @Override
    protected PathingCommand onTick() {
        stageTicks++;
        if (input == null) return fail("no input for " + step.recipe.id);
        switch (stage) {
            case OPEN: {
                if (menu() != null) {
                    stage = Stage.CHECK;
                    stageTicks = 0;
                    pacer.delay(4);
                    return pause();
                }
                if (access == null) access = new StationAccess(baritone, data, station, blacklist);
                StringBuilder why = new StringBuilder();
                PathingCommand cmd = access.approach(why);
                if (why.length() > 0) return fail(why.toString());
                if (cmd != null) return cmd;
                if (access.clicks() > 4) return fail(station.replace("minecraft:", "") + " didn't open");
                return pause();
            }
            case CHECK: {
                AbstractFurnaceMenu m = menu();
                if (m == null) return reopen();
                if (stageTicks < 3) return pause(); // let the contents sync
                Set<String> ours = new HashSet<>(step.inputs.keySet());
                ours.addAll(step.items);
                if (step.fuel != null) ours.add(step.fuel);
                for (int i = 0; i < 3; i++) {
                    String s = id(m.getSlot(i).getItem());
                    if (s != null && !ours.contains(s)) {
                        // someone else's smelting: don't touch it
                        if (access != null && access.pos != null) blacklist.add(access.pos);
                        ctx.player().closeContainer();
                        return fail("that " + station.replace("minecraft:", "") + " is in use; will use or place another");
                    }
                }
                stage = Stage.LOAD;
                return pause();
            }
            case LOAD: {
                AbstractFurnaceMenu m = menu();
                if (m == null) return reopen();
                if (!pacer.ready()) return pause();
                int needInput = step.batches - inputLoaded;
                int needFuel = step.fuel == null ? 0 : step.fuelCount - fuelLoaded;
                if (needInput > 0) {
                    if (!load(m, 0, input, needInput, true)) return pause();
                    return pause();
                }
                if (needFuel > 0) {
                    load(m, 1, step.fuel, needFuel, false);
                    return pause();
                }
                if (!m.getCarried().isEmpty()) {
                    putAway(m);
                    return pause();
                }
                stage = Stage.WAIT;
                stageTicks = 0;
                return pause();
            }
            case WAIT: {
                AbstractFurnaceMenu m = menu();
                if (m == null) return reopen();
                if (have() >= target) {
                    stage = Stage.UNLOAD;
                    return pause();
                }
                ItemStack out = m.getSlot(2).getItem();
                if (!out.isEmpty() && pacer.ready()) {
                    ctx.playerController().windowClick(m.containerId, 2, 0, ContainerInput.QUICK_MOVE, ctx.player());
                    return pause();
                }
                boolean idle = m.getSlot(0).getItem().isEmpty() && out.isEmpty();
                int cook = step.recipe.cookTime <= 0 ? 200 : step.recipe.cookTime;
                if (idle && stageTicks > 40 || stageTicks > step.batches * cook + 400) {
                    stage = Stage.UNLOAD; // ran out (fuel/inputs) or stuck: re-plan from what we got
                }
                return pause();
            }
            case UNLOAD: {
                AbstractFurnaceMenu m = menu();
                if (m == null) return have() >= target ? finish() : fail("furnace closed before finishing");
                if (!pacer.ready()) return pause();
                for (int slot : new int[]{2, 0, 1}) {
                    if (!m.getSlot(slot).getItem().isEmpty()) {
                        ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
                        return pause();
                    }
                }
                stage = Stage.CLOSE;
                return pause();
            }
            default: {
                if (!pacer.ready()) return pause();
                if (menu() != null) ctx.player().closeContainer();
                return have() >= target ? finish() : fail("smelted fewer " + step.items.get(0).replace("minecraft:", "") + " than planned");
            }
        }
    }

    private PathingCommand reopen() {
        stage = Stage.OPEN;
        stageTicks = 0;
        if (++reopen > 4) return fail(station.replace("minecraft:", "") + " screen closed unexpectedly");
        return pause();
    }

    /**
     * One click towards putting {@code need} of {@code item} into {@code slot}. Whole stack when it fits,
     * otherwise single right-clicks (exact amounts, minimal fuel waste).
     */
    private boolean load(AbstractFurnaceMenu m, int slot, String item, int need, boolean isInput) {
        ItemStack carried = m.getCarried();
        ItemStack inSlot = m.getSlot(slot).getItem();
        if (!inSlot.isEmpty() && !item.equals(id(inSlot))) {
            fail("unexpected item in furnace slot " + slot);
            return false;
        }
        if (carried.isEmpty()) {
            int src = -1;
            for (int i = 3; i < m.slots.size(); i++) {
                ItemStack s = m.getSlot(i).getItem();
                if (item.equals(id(s))) {
                    src = i;
                    break;
                }
            }
            if (src < 0) {
                fail("no " + item.replace("minecraft:", "") + " left to put in the furnace");
                return false;
            }
            ctx.playerController().windowClick(m.containerId, src, 0, ContainerInput.PICKUP, ctx.player());
            return true;
        }
        if (!item.equals(id(carried))) {
            putAway(m);
            return true;
        }
        if (carried.getCount() <= need) {
            int n = carried.getCount();
            ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.PICKUP, ctx.player());
            if (isInput) inputLoaded += n;
            else fuelLoaded += n;
        } else {
            ctx.playerController().windowClick(m.containerId, slot, 1, ContainerInput.PICKUP, ctx.player());
            if (isInput) inputLoaded++;
            else fuelLoaded++;
        }
        return true;
    }

    private void putAway(AbstractFurnaceMenu m) {
        ItemStack carried = m.getCarried();
        int target = -1;
        for (int i = 3; i < m.slots.size(); i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (s.isEmpty() || ItemStack.isSameItemSameComponents(s, carried) && s.getCount() < s.getMaxStackSize()) {
                target = i;
                if (!s.isEmpty()) break;
            }
        }
        if (target >= 0) ctx.playerController().windowClick(m.containerId, target, 0, ContainerInput.PICKUP, ctx.player());
    }

    @Override
    public void stop() {
        if (menu() != null) ctx.player().closeContainer();
    }

    @Override
    public String status() {
        return "smelting " + step.items.get(0).replace("minecraft:", "") + " " + Math.min(have(), target) + "/" + target
                + " in " + station.replace("minecraft:", "");
    }
}
