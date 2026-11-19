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

package baritone.process.getitem.advanced;

import java.util.*;

/**
 * Plain description of one inventory stack for the advanced (enchant / potion / rename) goals (plan A8, A25, A27):
 * item id, potion id, enchantments (or the stored enchantments of an enchanted book), custom name and anvil work
 * penalty. Built game-side; everything that reasons about it is pure and unit tested.
 */
public final class StackDesc {
    public final int slot;
    public final String item;
    public final int count;
    /** Potion id without namespace ("swiftness", "water"); null for non-potions. */
    public final String potion;
    /** Enchantment (no namespace) to level: the item's own, or a book's stored ones. */
    public final Map<String, Integer> enchants;
    public final String name;
    /** Anvil prior-work penalty ({@code repair_cost} component). */
    public final int repairCost;

    public StackDesc(int slot, String item, int count, String potion, Map<String, Integer> enchants, String name, int repairCost) {
        this.slot = slot;
        this.item = item;
        this.count = count;
        this.potion = potion;
        this.enchants = Collections.unmodifiableMap(new TreeMap<>(enchants));
        this.name = name;
        this.repairCost = repairCost;
    }

    public static StackDesc of(String item, int count) {
        return new StackDesc(-1, item, count, null, Collections.emptyMap(), null, 0);
    }

    public static StackDesc potion(String container, String potion, int count) {
        return new StackDesc(-1, container, count, potion, Collections.emptyMap(), null, 0);
    }

    public static StackDesc enchanted(String item, Map<String, Integer> enchants, int repairCost) {
        return new StackDesc(-1, item, 1, null, enchants, null, repairCost);
    }

    public boolean isBook() {
        return "minecraft:enchanted_book".equals(item);
    }

    public int level(String enchant) {
        return enchants.getOrDefault(enchant, 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(item);
        if (potion != null) sb.append('[').append(potion).append(']');
        if (!enchants.isEmpty()) sb.append(enchants);
        if (name != null) sb.append(" \"").append(name).append('"');
        return sb.append(" x").append(count).toString();
    }
}
