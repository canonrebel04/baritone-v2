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

    /**
     * @return a short progress line for HUDs ("iron_pickaxe 0/1 · step 3/9 · mining iron_ore · ETA 2m"), or null
     * when idle (plan A50)
     */
    default String progressLine() {
        return null;
    }

    /**
     * Pause the current goal (it stays active and continues on {@link #resume()}).
     */
    default void pause() {
    }

    default void resume() {
    }

    /**
     * @return why Get Item is currently not working (user pause, yielding to #goto, fleeing mobs, eating, ...),
     * or null when it's working / idle
     */
    default String pauseReason() {
        return null;
    }

    /**
     * @return true while hostiles made Get Item pause (getItemHostileAction "pause"); used by Meteor's combat
     * hand-off
     */
    default boolean isPausedForHostiles() {
        return false;
    }

    /**
     * @return where the item is known to be: containers (protected ones marked), villagers selling it
     */
    default List<String> where(String item) {
        return List.of();
    }

    /**
     * @return a summary of what Get Item remembers about this world (containers, stations, farms, ...)
     */
    default List<String> knowledge() {
        return List.of();
    }

    /**
     * A30: an outside module (Meteor's Get Item) that performs attacks registers itself here. Without one, hunting
     * is never planned: Baritone itself never attacks.
     */
    default void setKillHandler(boolean available) {
    }

    /** A30: entity id the kill handler should attack now (the hunt target in reach), or -1. */
    default int huntTargetId() {
        return -1;
    }
}
