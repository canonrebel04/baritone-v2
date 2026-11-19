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

/**
 * The next thing an advanced goal needs (plan A8/A25/A27), decided from the live inventory each time a step ends:
 * an ordinary item (planned by the normal planner) or a station operation.
 */
public final class Next {
    public enum Kind {
        /** Goal met. */
        DONE,
        /** Get {@code count} more of plain {@code item} through the normal planner. */
        NEED,
        /** Fill {@code count} glass bottles at water. */
        FILL,
        /** Brewing stand: add {@code item} as ingredient to up to {@code count} bottles of {@code from}. */
        BREW,
        /** Anvil: combine stack {@code slot} with stack {@code other} (book or same item). */
        ANVIL,
        /** Anvil: rename stack {@code slot} to {@code name}. */
        RENAME,
        /** Enchanting table: enchant stack {@code slot}; {@code reroll} = take the cheapest offer to change the seed. */
        ENCHANT,
        /** Grindstone: strip stack {@code slot}. */
        GRINDSTONE,
        /** Pick up experience orbs (short of {@code count} levels). */
        XP,
        FAIL
    }

    public final Kind kind;
    public final String item;
    public final int count;
    public final StackDesc from;
    public final int slot;
    public final int other;
    public final String name;
    public final boolean reroll;
    public final String why;

    private Next(Kind kind, String item, int count, StackDesc from, int slot, int other, String name, boolean reroll, String why) {
        this.kind = kind;
        this.item = item;
        this.count = count;
        this.from = from;
        this.slot = slot;
        this.other = other;
        this.name = name;
        this.reroll = reroll;
        this.why = why;
    }

    public static Next done() {
        return new Next(Kind.DONE, null, 0, null, -1, -1, null, false, null);
    }

    public static Next need(String item, int count, String why) {
        return new Next(Kind.NEED, item, count, null, -1, -1, null, false, why);
    }

    public static Next fill(int count) {
        return new Next(Kind.FILL, "minecraft:glass_bottle", count, null, -1, -1, null, false, null);
    }

    public static Next brew(StackDesc from, String ingredient, int count) {
        return new Next(Kind.BREW, ingredient, count, from, -1, -1, null, false, null);
    }

    public static Next anvil(int slot, int other) {
        return new Next(Kind.ANVIL, null, 1, null, slot, other, null, false, null);
    }

    public static Next rename(int slot, String name) {
        return new Next(Kind.RENAME, null, 1, null, slot, -1, name, false, null);
    }

    public static Next enchant(int slot, boolean reroll) {
        return new Next(Kind.ENCHANT, null, 1, null, slot, -1, null, reroll, null);
    }

    public static Next grindstone(int slot) {
        return new Next(Kind.GRINDSTONE, null, 1, null, slot, -1, null, false, null);
    }

    public static Next xp(int levels) {
        return new Next(Kind.XP, null, levels, null, -1, -1, null, false, null);
    }

    public static Next fail(String why) {
        return new Next(Kind.FAIL, null, 0, null, -1, -1, null, false, why);
    }

    @Override
    public String toString() {
        switch (kind) {
            case NEED: return "get " + count + "x " + item.replace("minecraft:", "") + (why != null ? " (" + why + ")" : "");
            case FILL: return "fill " + count + " water bottles";
            case BREW: return "brew " + item.replace("minecraft:", "") + " into " + count + "x " + from.potion;
            case ANVIL: return "combine on an anvil";
            case RENAME: return "rename to \"" + name + "\" on an anvil";
            case ENCHANT: return reroll ? "enchant (cheapest, to reroll the offers)" : "enchant on the enchanting table";
            case GRINDSTONE: return "clean on a grindstone";
            case XP: return "collect experience (" + count + " levels short)";
            case FAIL: return "can't: " + why;
            default: return "done";
        }
    }
}
