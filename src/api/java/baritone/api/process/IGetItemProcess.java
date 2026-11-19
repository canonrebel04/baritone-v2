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

package baritone.api.process;

import java.util.List;

/**
 * Get Item: obtain N of an item (or any item of a tag) by planning and running mining, crafting and smelting
 * steps, re-planning from the real inventory after every step.
 */
public interface IGetItemProcess extends IBaritoneProcess {

    /**
     * Start getting items.
     *
     * @param item   item id ("minecraft:iron_pickaxe", "iron_pickaxe") or tag ("#logs", "#minecraft:planks")
     * @param amount total amount to have in the inventory
     * @return null when started, otherwise why it couldn't start (unknown item, no plan, ...)
     */
    String get(String item, int amount);

    /**
     * Plan without executing (dry run).
     *
     * @return human readable plan lines (tree, ETA, risks) or the reason it can't be done
     */
    List<String> plan(String item, int amount);

    /**
     * @return a one-line status (current goal, step and progress), or "idle"
     */
    String status();

    /**
     * @return the current plan explained, or the last failure reason
     */
    List<String> why();

    /**
     * Stop and release control.
     */
    void cancel();
}
