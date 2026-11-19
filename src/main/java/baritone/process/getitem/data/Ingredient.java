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

package baritone.process.getitem.data;

import java.util.Collections;
import java.util.List;

/**
 * One ingredient of a recipe: {@code count} items, each of which may be any of {@code options} (tag-aware, plan A2).
 * Options keep the tag's order, which is the preference order when nothing better is known.
 */
public final class Ingredient {
    /** Resolved item ids. */
    public final List<String> options;
    /** The tag this ingredient came from ("minecraft:planks"), or null. */
    public final String tag;
    public final int count;

    public Ingredient(List<String> options, String tag, int count) {
        this.options = Collections.unmodifiableList(options);
        this.tag = tag;
        this.count = count;
    }

    public boolean accepts(String item) {
        return options.contains(item);
    }

    /** Short form for plan output: "#minecraft:planks", "minecraft:stick" or "a|b". */
    public String describe() {
        if (tag != null) return "#" + tag;
        return options.size() == 1 ? options.get(0) : String.join("|", options);
    }

    @Override
    public String toString() {
        return count + "x" + describe();
    }
}
