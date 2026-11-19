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
import baritone.process.getitem.knowledge.ProtectPolicy;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;

/**
 * Shared open / protection check / close flow for chest-like containers (A11/A24). Subclasses do their clicks in
 * {@link #work}. A container whose screen title (custom name) matches the protect list is closed untouched and
 * re-indexed as protected.
 */
abstract class ContainerTask extends StepTask {
    protected final GetItemKnowledge knowledge;
    protected final GuiPacer pacer = new GuiPacer();
    protected ContainerAccess access;
    private boolean opened;
    private int openWait;
    private int closeWait = -1;
    private int closeIn = -1;
    private boolean succeeded;
    private String closeFailure;

    ContainerTask(Baritone baritone, GameData data, GetItemKnowledge knowledge, PlanStep step, BlockPos pos) {
        super(baritone, data, step);
        this.knowledge = knowledge;
        this.access = pos == null ? null : new ContainerAccess(baritone, pos);
    }

    protected AbstractContainerMenu menu() {
        AbstractContainerMenu m = ctx.player().containerMenu;
        return m instanceof ChestMenu || m instanceof ShulkerBoxMenu ? m : null;
    }

    protected int containerSize(AbstractContainerMenu m) {
        return m instanceof ChestMenu cm ? cm.getRowCount() * 9 : 27;
    }

    /** One tick of work with the container open. Return true when finished (container is then closed). */
    protected abstract boolean work(AbstractContainerMenu m);

    /** Result once closed: true = finish, false = fail with {@link #closeFailure}. */
    protected abstract boolean succeeded();

    protected void setFailure(String why) {
        closeFailure = why;
    }

    @Override
    protected final PathingCommand onTick() {
        if (access == null) return fail(closeFailure != null ? closeFailure : "no usable container");
        if (closeWait >= 0) {
            if (menu() != null) ctx.player().closeContainer();
            if (closeWait++ < 4) return pause();
            return succeeded ? finish() : fail(closeFailure != null ? closeFailure : "container step failed");
        }
        AbstractContainerMenu m = menu();
        if (m == null) {
            if (opened) {
                succeeded = succeeded();
                closeWait = 0;
                return pause();
            }
            if (ctx.world().getChunkSource().hasChunk(access.pos.getX() >> 4, access.pos.getZ() >> 4)
                    && !GetItemKnowledge.isContainerBlock(ctx.world().getBlockState(access.pos).getBlock())) {
                knowledge.base().containers.remove(data.dimension(), access.pos.getX(), access.pos.getY(), access.pos.getZ());
                return fail("the container at " + access.pos.toShortString() + " is gone");
            }
            StringBuilder why = new StringBuilder();
            PathingCommand cmd = access.approach(why);
            if (why.length() > 0) return fail(why.toString());
            if (cmd != null) return cmd;
            if (access.clicks() > 4) return fail("the container at " + access.pos.toShortString() + " didn't open");
            return pause();
        }
        if (!opened) {
            opened = true;
            openWait = 0;
            int stacks = 0;
            for (int i = 0; i < Math.min(containerSize(m), m.slots.size()); i++) {
                if (!m.getSlot(i).getItem().isEmpty()) stacks++;
            }
            pacer.opened(stacks); // S41: time to "read" the container
        }
        if (openWait++ < 3) return pause(); // let contents sync
        Screen screen = ctx.minecraft().gui.screen();
        String name = screen == null ? null : GetItemKnowledge.customName(screen.getTitle());
        knowledge.indexContainer(access.pos, m, name);
        ProtectPolicy policy = knowledge.policy();
        var c = knowledge.base().containers.get(data.dimension(), access.pos.getX(), access.pos.getY(), access.pos.getZ());
        if (c != null && policy.containerProtected(c)) {
            closeFailure = "\"" + (name == null ? c.type : name) + "\" is protected, not touching it";
            succeeded = false;
            closeWait = 0;
            ctx.player().closeContainer();
            return pause();
        }
        if (!m.getCarried().isEmpty()) {
            if (pacer.ready()) putCarriedBack(m);
            return pause();
        }
        if (closeIn < 0 && work(m)) {
            closeIn = pacer.closeDelay(); // S41: a short beat before closing
        }
        if (closeIn >= 0 && closeIn-- == 0) {
            knowledge.indexContainer(access.pos, m, name);
            succeeded = succeeded();
            closeWait = 0;
            ctx.player().closeContainer();
        }
        return pause();
    }

    private void putCarriedBack(AbstractContainerMenu m) {
        int size = containerSize(m);
        for (int i = size; i < m.slots.size(); i++) {
            if (m.getSlot(i).getItem().isEmpty()) {
                ctx.playerController().windowClick(m.containerId, i, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, ctx.player());
                return;
            }
        }
    }

    @Override
    public void stop() {
        if (menu() != null) ctx.player().closeContainer();
    }
}
