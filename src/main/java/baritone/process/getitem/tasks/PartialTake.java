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

import java.util.ArrayList;
import java.util.List;

/**
 * Exact-count withdrawal: the vanilla clicks that move exactly {@code want} items out of a stack of {@code have}
 * (pure). Left-click picks the stack up; each right-click with a carried stack drops one item; a right-click on a
 * stack with an empty cursor picks up half (rounded up). Uses whichever sequence needs fewer clicks.
 */
public final class PartialTake {
    /** One click: menu slot and mouse button (0 = left, 1 = right). */
    public static final class Click {
        public final int slot, button;

        Click(int slot, int button) {
            this.slot = slot;
            this.button = button;
        }

        @Override
        public String toString() {
            return (button == 0 ? "L" : "R") + slot;
        }
    }

    private PartialTake() {
    }

    /**
     * @param from container slot holding {@code have} items
     * @param to   empty player inventory slot
     */
    public static List<Click> clicks(int from, int to, int have, int want) {
        List<Click> out = new ArrayList<>();
        if (want <= 0) return out;
        if (want >= have) {
            out.add(new Click(from, 0)); // pick all up, put all down
            out.add(new Click(to, 0));
            return out;
        }
        int half = (have + 1) / 2;
        if (want == half) {
            out.add(new Click(from, 1)); // right-click takes the bigger half
            out.add(new Click(to, 0));
            return out;
        }
        if (want < half) {
            // carry the bigger half, drop `want` one by one into our slot, put the rest back
            out.add(new Click(from, 1));
            for (int i = 0; i < want; i++) out.add(new Click(to, 1));
            out.add(new Click(from, 0));
        } else {
            // carry everything, give back (have - want) one by one, put the rest in our slot
            out.add(new Click(from, 0));
            for (int i = 0; i < have - want; i++) out.add(new Click(from, 1));
            out.add(new Click(to, 0));
        }
        return out;
    }

    /** Simulate the clicks: returns {left in container slot, in player slot, on cursor}. */
    public static int[] simulate(List<Click> clicks, int from, int to, int have) {
        int a = have, b = 0, cursor = 0;
        for (Click c : clicks) {
            boolean onFrom = c.slot == from;
            int slotCount = onFrom ? a : b;
            if (c.button == 0) {
                if (cursor == 0) {
                    cursor = slotCount;
                    slotCount = 0;
                } else {
                    slotCount += cursor;
                    cursor = 0;
                }
            } else {
                if (cursor == 0) {
                    int take = (slotCount + 1) / 2;
                    cursor = take;
                    slotCount -= take;
                } else {
                    slotCount++;
                    cursor--;
                }
            }
            if (onFrom) a = slotCount;
            else b = slotCount;
        }
        return new int[]{a, b, cursor};
    }
}
