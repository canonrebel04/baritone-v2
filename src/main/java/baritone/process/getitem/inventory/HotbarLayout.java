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
 * Plan A43: hotbar tool layout. Each hotbar slot may name a kind (pickaxe, axe, shovel, hoe, sword, shears, food,
 * blocks; "-" = leave alone); the best matching item (tool tier first) is swapped into that slot. Pure: works on
 * slot descriptions and returns the swaps to make, one at a time.
 */
public final class HotbarLayout {
    public static final List<String> KINDS = Arrays.asList("pickaxe", "axe", "shovel", "hoe", "sword", "shears", "food", "blocks");
    private static final List<String> TIERS = Arrays.asList("wooden", "golden", "stone", "copper", "iron", "diamond", "netherite");

    /** One inventory slot (0-8 hotbar, 9-35 main). */
    public static final class Item {
        public final int slot;
        public final String id;
        public final boolean food;
        public final boolean block;

        public Item(int slot, String id, boolean food, boolean block) {
            this.slot = slot;
            this.id = id;
            this.food = food;
            this.block = block;
        }
    }

    public static String normalize(String kind) {
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if (k.endsWith("s") && !k.equals("shears") && !k.equals("blocks")) k = k.substring(0, k.length() - 1);
        if (k.equals("pick")) k = "pickaxe";
        if (k.equals("block")) k = "blocks";
        return KINDS.contains(k) ? k : null;
    }

    public static boolean matches(String kind, Item it) {
        String path = it.id.substring(it.id.indexOf(':') + 1);
        switch (kind) {
            case "food": return it.food;
            case "blocks": return it.block;
            case "shears": return path.equals("shears");
            case "axe": return path.endsWith("_axe") && !path.endsWith("pickaxe");
            default: return path.endsWith("_" + kind);
        }
    }

    /** Higher is better (tool tier; stack-size-free kinds all 0). */
    public static int rank(Item it) {
        String path = it.id.substring(it.id.indexOf(':') + 1);
        int u = path.indexOf('_');
        return u < 0 ? 0 : TIERS.indexOf(path.substring(0, u)) + 1;
    }

    /**
     * Next swap as {from inventory slot, to hotbar slot}, or null when the layout is satisfied (or can't be
     * improved). A slot already holding a matching item is left alone unless a strictly better tool exists in the
     * main inventory; items already placed for an earlier slot are never pulled away.
     */
    public static int[] nextSwap(List<String> layout, List<Item> items) {
        Map<Integer, Item> bySlot = new HashMap<>();
        for (Item i : items) bySlot.put(i.slot, i);
        for (int h = 0; h < Math.min(9, layout.size()); h++) {
            String kind = normalize(layout.get(h));
            if (kind == null) continue;
            Item cur = bySlot.get(h);
            Item best = null;
            for (Item i : items) {
                if (i.slot == h || !matches(kind, i) || placed(layout, i)) continue;
                if (best == null || rank(i) > rank(best)) best = i;
            }
            boolean curOk = cur != null && matches(kind, cur);
            if (best != null && (!curOk || rank(best) > rank(cur))) return new int[]{best.slot, h};
        }
        return null;
    }

    /** Already sitting in a hotbar slot whose layout kind it matches (don't pull it away). */
    private static boolean placed(List<String> layout, Item i) {
        if (i.slot >= 9 || i.slot >= layout.size()) return false;
        String k = normalize(layout.get(i.slot));
        return k != null && matches(k, i);
    }
}
