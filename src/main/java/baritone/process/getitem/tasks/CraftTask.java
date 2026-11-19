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
import baritone.process.getitem.data.Recipe;
import baritone.process.getitem.plan.PlanStep;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.*;

import java.util.*;

/**
 * A22: craft in the 2x2 inventory grid or at a crafting table (located nearby; the planner adds a placement step
 * when there is none). The grid is filled by the recipe book (one request per batch) when the recipe is known and
 * {@code getItemUseRecipeBook} is on, otherwise by individual legal clicks; the result is shift-clicked out. All
 * clicks are paced by {@link GuiPacer}.
 */
public final class CraftTask extends StepTask {
    private enum Stage { OPEN, FILL, TAKE, CLOSE }

    private final Recipe recipe;
    private final boolean table;
    private final int target;
    private final GuiPacer pacer = new GuiPacer();
    private final Set<BlockPos> blacklist;
    private StationAccess access;
    private Stage stage = Stage.OPEN;
    private int stageTicks;
    private int bookRequests;
    private boolean manual;
    private int cycles;
    /** Item id per grid cell (row-major over the grid's width), null = must stay empty. */
    private String[] cells;

    public CraftTask(Baritone baritone, GameData data, PlanStep step, Set<BlockPos> blacklist) {
        super(baritone, data, step);
        this.recipe = step.recipe;
        this.table = recipe.needsTable();
        this.target = data.count(step.items) + step.count;
        this.blacklist = blacklist;
        this.manual = !Baritone.settings().getItemUseRecipeBook.value;
    }

    private int gridWidth() {
        return table ? 3 : 2;
    }

    private int resultCount() {
        return data.count(step.items);
    }

    private AbstractContainerMenu menu() {
        AbstractContainerMenu m = ctx.player().containerMenu;
        if (table) return m instanceof CraftingMenu ? m : null;
        return m instanceof InventoryMenu && Minecraft.getInstance().gui.screen() instanceof InventoryScreen ? m : null;
    }

    @Override
    protected PathingCommand onTick() {
        stageTicks++;
        if (stage != Stage.CLOSE && resultCount() >= target) {
            stage = Stage.CLOSE;
        }
        switch (stage) {
            case OPEN: {
                if (menu() != null) {
                    stage = Stage.FILL;
                    stageTicks = 0;
                    pacer.opened(0); // S41: "reaction" before the first click
                    return pause();
                }
                if (!table) {
                    Minecraft.getInstance().gui.setScreen(new InventoryScreen(ctx.player()));
                    return pause();
                }
                if (access == null) access = new StationAccess(baritone, data, "minecraft:crafting_table", blacklist);
                StringBuilder why = new StringBuilder();
                PathingCommand cmd = access.approach(why);
                if (why.length() > 0) return fail(why.toString());
                if (cmd != null) return cmd;
                if (access.clicks() > 4) return fail("crafting table didn't open");
                return pause();
            }
            case FILL: {
                AbstractContainerMenu m = menu();
                if (m == null) return reopen();
                if (!pacer.ready()) return pause();
                ItemStack result = m.getSlot(0).getItem();
                if (!result.isEmpty() && GameData.id(result.getItem()).equals(recipe.output) && gridHas(m, batchesNow())) {
                    stage = Stage.TAKE;
                    return pause();
                }
                if (!manual) {
                    RecipeDisplayEntry entry = findBookEntry();
                    if (entry == null || bookRequests >= batchesNow() + 3) {
                        manual = true;
                        return pause();
                    }
                    Minecraft.getInstance().gameMode.handlePlaceRecipe(m.containerId, entry.id(), false);
                    bookRequests++;
                    return pause();
                }
                if (cells == null) {
                    cells = layout();
                    if (cells == null) return fail("can't lay out " + recipe.id + " with " + step.inputs);
                }
                if (!manualClick(m)) {
                    // grid complete per layout; wait for the result slot to update
                    if (stageTicks > 200) return fail("crafting result never appeared for " + recipe.output);
                }
                return pause();
            }
            case TAKE: {
                AbstractContainerMenu m = menu();
                if (m == null) return reopen();
                if (!pacer.ready()) return pause();
                ItemStack result = m.getSlot(0).getItem();
                if (!result.isEmpty()) {
                    ctx.playerController().windowClick(m.containerId, 0, 0, ContainerInput.QUICK_MOVE, ctx.player());
                    return pause();
                }
                // grid used up (or inventory full); go round again if more is needed
                if (resultCount() < target) {
                    if (++cycles > 4) return fail("crafted fewer " + recipe.output + " than planned (inventory full?)");
                    stage = Stage.FILL;
                    bookRequests = 0;
                    cells = null;
                    stageTicks = 0;
                }
                return pause();
            }
            default: {
                if (!pacer.ready()) return pause();
                close();
                return finish();
            }
        }
    }

    private PathingCommand reopen() {
        if (resultCount() >= target) return finish();
        stage = Stage.OPEN;
        stageTicks = 0;
        if (++cycles > 6) return fail("crafting screen closed unexpectedly");
        return pause();
    }

    /** Batches still needed (a grid slot holds at most 64). */
    private int batchesNow() {
        int remaining = Math.max(0, target - resultCount());
        int b = (remaining + recipe.outputCount - 1) / recipe.outputCount;
        return Math.max(1, Math.min(64, b));
    }

    private boolean gridHas(AbstractContainerMenu m, int batches) {
        int cellsUsed = 0;
        int min = Integer.MAX_VALUE;
        for (int i = 1; i <= gridWidth() * gridWidth(); i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty()) {
                cellsUsed++;
                min = Math.min(min, s.getCount());
            }
        }
        return cellsUsed > 0 && (min >= batches || manual || bookRequests >= batches);
    }

    private RecipeDisplayEntry findBookEntry() {
        ContextMap context = SlotDisplayContext.fromLevel(ctx.world());
        for (RecipeDisplayEntry e : data.recipeBookEntries()) {
            RecipeDisplay d = e.display();
            if (!(d instanceof ShapedCraftingRecipeDisplay || d instanceof ShapelessCraftingRecipeDisplay)) continue;
            if (d instanceof ShapedCraftingRecipeDisplay s && !table && (s.width() > 2 || s.height() > 2)) continue;
            if (d instanceof ShapelessCraftingRecipeDisplay s && !table && s.ingredients().size() > 4) continue;
            ItemStack out = d.result().resolveForFirstStack(context);
            if (out.isEmpty() || !GameData.id(out.getItem()).equals(recipe.output) || out.getCount() != recipe.outputCount) continue;
            if (e.craftingRequirements().isPresent()) {
                boolean ok = true;
                for (net.minecraft.world.item.crafting.Ingredient ing : e.craftingRequirements().get()) {
                    boolean any = false;
                    for (String in : step.inputs.keySet()) {
                        if (ing.test(new ItemStack(GameData.item(in)))) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
            }
            return e;
        }
        return null;
    }

    /** Assign concrete input items to grid cells for one batch-set (shaped: dataset pattern; shapeless: in order). */
    String[] layout() {
        return GridLayout.layout(recipe, step.inputs, gridWidth(), batchesNow());
    }

    /** One manual click towards the layout. Returns false when the grid matches it and nothing is carried. */
    private boolean manualClick(AbstractContainerMenu m) {
        int batches = batchesNow();
        ItemStack carried = m.getCarried();
        int invStart = table ? 10 : 9, invEnd = table ? 45 : 44; // inclusive
        for (int i = 0; i < cells.length; i++) {
            int slot = 1 + i;
            ItemStack inSlot = m.getSlot(slot).getItem();
            String want = cells[i];
            boolean wrong = !inSlot.isEmpty() && (want == null || !GameData.id(inSlot.getItem()).equals(want));
            if (wrong) {
                if (!carried.isEmpty()) return putAway(m, carried, invStart, invEnd);
                ctx.playerController().windowClick(m.containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
                return true;
            }
            if (want != null && inSlot.getCount() < batches) {
                if (carried.isEmpty()) {
                    int src = findSource(m, want, invStart, invEnd);
                    if (src < 0) {
                        cells = null; // inventory changed; re-layout next tick
                        return true;
                    }
                    ctx.playerController().windowClick(m.containerId, src, 0, ContainerInput.PICKUP, ctx.player());
                    return true;
                }
                if (!GameData.id(carried.getItem()).equals(want)) return putAway(m, carried, invStart, invEnd);
                ctx.playerController().windowClick(m.containerId, slot, 1, ContainerInput.PICKUP, ctx.player());
                return true;
            }
        }
        if (!carried.isEmpty()) return putAway(m, carried, invStart, invEnd);
        return false;
    }

    private int findSource(AbstractContainerMenu m, String item, int from, int to) {
        int best = -1;
        for (int i = from; i <= to; i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty() && GameData.id(s.getItem()).equals(item) && (best < 0 || s.getCount() > m.getSlot(best).getItem().getCount())) best = i;
        }
        return best;
    }

    private boolean putAway(AbstractContainerMenu m, ItemStack carried, int from, int to) {
        int target = -1;
        for (int i = from; i <= to; i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, carried) && s.getCount() < s.getMaxStackSize()) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            for (int i = from; i <= to; i++) {
                if (m.getSlot(i).getItem().isEmpty()) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) return true; // nowhere to put it: closing the screen returns it
        ctx.playerController().windowClick(m.containerId, target, 0, ContainerInput.PICKUP, ctx.player());
        return true;
    }

    private void close() {
        if (ctx.player().containerMenu != ctx.player().inventoryMenu || Minecraft.getInstance().gui.screen() instanceof InventoryScreen) {
            ctx.player().closeContainer();
        }
    }

    @Override
    public void stop() {
        if (menu() != null) close();
    }

    @Override
    public String status() {
        return "crafting " + recipe.output.replace("minecraft:", "") + " " + Math.min(resultCount(), target) + "/" + target
                + (table ? " at crafting table" : "") + (manual ? " (manual)" : "");
    }
}
