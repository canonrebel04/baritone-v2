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

package baritone.process.getitem.knowledge;

import java.util.*;

/**
 * Protect list (plan A24/A49, decision 11): containers whose custom name matches a pattern, containers at listed
 * positions and protected items are never touched by Get Item: nothing is taken out of them, put into them or
 * used up. Ender chests are opt-in.
 */
public final class ProtectPolicy {
    public final List<String> namePatterns;
    public final Set<String> items;
    public final Set<String> positions;
    public final boolean allowEnderChest;

    public ProtectPolicy(Collection<String> namePatterns, Collection<String> items, Collection<String> positions, boolean allowEnderChest) {
        List<String> n = new ArrayList<>();
        for (String p : namePatterns) if (p != null && !p.isBlank()) n.add(p.trim().toLowerCase(Locale.ROOT));
        this.namePatterns = Collections.unmodifiableList(n);
        this.items = Collections.unmodifiableSet(new HashSet<>(items));
        this.positions = Collections.unmodifiableSet(new HashSet<>(positions));
        this.allowEnderChest = allowEnderChest;
    }

    public static ProtectPolicy none() {
        return new ProtectPolicy(Collections.emptyList(), Collections.emptySet(), Collections.emptySet(), true);
    }

    public static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** A custom container name is protected when it contains any pattern (case-insensitive). */
    public boolean nameProtected(String customName) {
        if (customName == null || customName.isEmpty()) return false;
        String n = customName.toLowerCase(Locale.ROOT);
        for (String p : namePatterns) if (n.contains(p)) return true;
        return false;
    }

    public boolean containerProtected(ContainerIndex.Container c) {
        if (c == null) return true;
        if (ContainerIndex.ENDER_CHEST.equals(c.type) && !allowEnderChest) return true;
        return nameProtected(c.name) || positions.contains(posKey(c.x, c.y, c.z));
    }

    public boolean itemProtected(String item) {
        return items.contains(item);
    }
}
