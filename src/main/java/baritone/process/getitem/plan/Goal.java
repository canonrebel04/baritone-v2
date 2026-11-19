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

package baritone.process.getitem.plan;

import baritone.process.getitem.graph.RecipeGraph;

import java.util.*;

/**
 * What the user asked for: one item or any item of a tag (plan A2), and the total amount to have.
 * Tag options keep preference order (an explicit preference list first, then tag order).
 */
public final class Goal {
    public final String display;
    public final List<String> options;
    public final int count;

    public Goal(String display, List<String> options, int count) {
        if (options.isEmpty()) throw new IllegalArgumentException("Goal " + display + " has no items");
        this.display = display;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.count = Math.max(1, count);
    }

    public static Goal item(String item, int count) {
        return new Goal(item, Collections.singletonList(item), count);
    }

    /**
     * Parse "minecraft:oak_log", "oak_log", "#logs" or "#minecraft:logs".
     *
     * @param preference items to try first when the goal is a tag (may be empty)
     * @return the goal, or null when the item/tag is unknown
     */
    public static Goal parse(String text, int count, RecipeGraph graph, List<String> preference) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("#")) {
            String tag = withNamespace(t.substring(1));
            List<String> items = graph.itemTag(tag);
            if (items.isEmpty()) return null;
            List<String> ordered = new ArrayList<>();
            for (String p : preference) if (items.contains(p)) ordered.add(p);
            for (String i : items) if (!ordered.contains(i)) ordered.add(i);
            return new Goal("#" + tag, ordered, count);
        }
        String id = withNamespace(t);
        return graph.knows(id) ? item(id, count) : null;
    }

    public static String withNamespace(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    public boolean isTag() {
        return options.size() > 1 || display.startsWith("#");
    }

    public int have(Map<String, Integer> inventory) {
        int n = 0;
        for (String o : options) n += inventory.getOrDefault(o, 0);
        return n;
    }

    @Override
    public String toString() {
        return count + "x" + display;
    }
}
