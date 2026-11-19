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
import java.util.Map;

/** Resolves item tags. The dataset provides vanilla tags; at runtime the game's (possibly datapacked) tags win. */
@FunctionalInterface
public interface TagLookup {
    /** Items in the tag (no leading '#'), in tag order; empty if unknown. */
    List<String> items(String tag);

    static TagLookup of(Map<String, List<String>> tags) {
        return tag -> tags.getOrDefault(tag, Collections.emptyList());
    }

    /** This lookup, falling back to {@code other} when it doesn't know a tag. */
    default TagLookup orElse(TagLookup other) {
        return tag -> {
            List<String> l = items(tag);
            return l.isEmpty() ? other.items(tag) : l;
        };
    }
}
