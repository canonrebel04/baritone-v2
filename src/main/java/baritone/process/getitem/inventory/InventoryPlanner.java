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

package baritone.process.getitem.inventory;

import java.util.*;

/**
 * Inventory manager (plan A43): keeps free slots for the plan. First junk the plan doesn't need is dropped, then
 * (if allowed) the biggest stacks the plan doesn't need are stashed in a container. Protected items, tools and
 * anything the plan needs are never moved.
 */
public final class InventoryPlanner {
    /** One inventory slot. */
    public static final class Slot {
        public final int index;
        public final String item;
        public final int count;
        public final boolean tool;

        public Slot(int index, String item, int count, boolean tool) {
            this.index = index;
            this.item = item;
            this.count = count;
            this.tool = tool;
        }
    }

    public static final class Decision {
        public final List<Integer> drop = new ArrayList<>();
        public final List<Integer> stash = new ArrayList<>();

        public boolean isEmpty() {
            return drop.isEmpty() && stash.isEmpty();
        }
    }

    private InventoryPlanner() {
    }

    public static int freeSlots(List<Slot> slots, int size) {
        return size - slots.size();
    }

    public static Decision decide(List<Slot> slots, int size, int minFree, Set<String> junk, Set<String> needed,
                                  Set<String> protectedItems, boolean dropJunk, boolean stash) {
        Decision d = new Decision();
        int free = freeSlots(slots, size);
        if (free >= minFree) return d;
        List<Slot> movable = new ArrayList<>();
        for (Slot s : slots) {
            if (needed.contains(s.item) || protectedItems.contains(s.item) || s.tool) continue;
            movable.add(s);
        }
        if (dropJunk) {
            for (Slot s : movable) {
                if (free >= minFree) break;
                if (junk.contains(s.item)) {
                    d.drop.add(s.index);
                    free++;
                }
            }
        }
        if (stash && free < minFree) {
            List<Slot> rest = new ArrayList<>();
            for (Slot s : movable) if (!d.drop.contains(s.index)) rest.add(s);
            rest.sort(Comparator.comparingInt((Slot s) -> -s.count).thenComparingInt(s -> s.index));
            for (Slot s : rest) {
                if (free >= minFree) break;
                d.stash.add(s.index);
                free++;
            }
        }
        return d;
    }
}
