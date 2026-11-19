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
 * An item goal with data the plain item id can't express (plan A8/A25/A27):
 * <ul>
 *     <li>{@code potion:swiftness}, {@code splash_potion:strong_healing}, {@code lingering_potion:long_poison}</li>
 *     <li>{@code diamond_pickaxe@fortune:3,unbreaking:3} (enchantments; {@code fortune} alone = level 1)</li>
 *     <li>{@code enchanted_book@mending} is not brewed or crafted, only found: use plain enchant goals on tools</li>
 *     <li>{@code @name:Text} rename on an anvil; {@code @clean} strip enchantments on a grindstone</li>
 * </ul>
 * Parsing and matching are pure.
 */
public final class ItemSpec {
    public static final List<String> POTION_ITEMS = Arrays.asList("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion");

    public final String item;
    public final String potion;
    public final Map<String, Integer> enchants;
    public final String name;
    public final boolean clean;

    private ItemSpec(String item, String potion, Map<String, Integer> enchants, String name, boolean clean) {
        this.item = item;
        this.potion = potion;
        this.enchants = Collections.unmodifiableMap(new LinkedHashMap<>(enchants));
        this.name = name;
        this.clean = clean;
    }

    /** True when the text is an advanced goal (has data beyond an item id or tag). */
    public static boolean looksAdvanced(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("#")) return false;
        if (t.indexOf('@') > 0) return true;
        String head = t.startsWith("minecraft:") ? t.substring(10) : t;
        int c = head.indexOf(':');
        return c > 0 && POTION_ITEMS.contains("minecraft:" + head.substring(0, c));
    }

    /** @return the spec, or null when the text isn't a valid advanced goal. */
    public static ItemSpec parse(String text) {
        String raw = text.trim();
        String t = raw.toLowerCase(Locale.ROOT);
        if (!looksAdvanced(t)) return null;
        int at = t.indexOf('@');
        if (at < 0) {
            String head = t.startsWith("minecraft:") ? t.substring(10) : t;
            int c = head.indexOf(':');
            String potion = head.substring(c + 1).replace("minecraft:", "");
            if (potion.isEmpty() || !potion.matches("[a-z_]+")) return null;
            return new ItemSpec("minecraft:" + head.substring(0, c), potion, Collections.emptyMap(), null, false);
        }
        String item = t.substring(0, at);
        if (item.indexOf(':') < 0) item = "minecraft:" + item;
        if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return null;
        Map<String, Integer> ench = new LinkedHashMap<>();
        String name = null;
        boolean clean = false;
        String rest = raw.substring(at + 1);
        int nameAt = rest.toLowerCase(Locale.ROOT).indexOf("name:");
        if (nameAt >= 0) {
            name = rest.substring(nameAt + 5).trim();
            rest = rest.substring(0, nameAt);
            if (name.isEmpty() || name.length() > 50) return null;
        }
        for (String part : rest.toLowerCase(Locale.ROOT).split("[,@]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.equals("clean")) {
                clean = true;
                continue;
            }
            String[] kv = p.split("[:=]");
            String e = kv[0].replace("minecraft:", "").replace(' ', '_');
            if (!e.matches("[a-z_]+")) return null;
            int lvl = 1;
            if (kv.length > 1) {
                lvl = roman(kv[1]);
                if (lvl <= 0) return null;
            }
            ench.put(e, lvl);
        }
        if (ench.isEmpty() && name == null && !clean) return null;
        if (clean && !ench.isEmpty()) return null;
        return new ItemSpec(item, null, ench, name, clean);
    }

    /** "3" or "III" to 3; 0 when invalid. */
    static int roman(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.matches("\\d{1,3}")) return Integer.parseInt(t);
        switch (t) {
            case "I": return 1;
            case "II": return 2;
            case "III": return 3;
            case "IV": return 4;
            case "V": return 5;
            default: return 0;
        }
    }

    public boolean isPotion() {
        return potion != null;
    }

    public boolean matches(StackDesc s) {
        if (!item.equals(s.item)) return false;
        if (potion != null) return potion.equals(s.potion);
        if (clean) return s.enchants.isEmpty();
        for (Map.Entry<String, Integer> e : enchants.entrySet()) if (s.level(e.getKey()) < e.getValue()) return false;
        return name == null || name.equals(s.name);
    }

    public int have(Collection<StackDesc> inv) {
        int n = 0;
        for (StackDesc s : inv) if (matches(s)) n += s.count;
        return n;
    }

    /** User-facing text ("splash potion of strong healing", "diamond pickaxe with fortune 3"). */
    public String display() {
        String base = item.replace("minecraft:", "").replace('_', ' ');
        if (potion != null) return base + " of " + potion.replace('_', ' ');
        StringBuilder sb = new StringBuilder(base);
        if (!enchants.isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            enchants.forEach((k, v) -> j.add(k.replace('_', ' ') + " " + v));
            sb.append(" with ").append(j);
        }
        if (clean) sb.append(" (no enchantments)");
        if (name != null) sb.append(" named \"").append(name).append('"');
        return sb.toString();
    }

    @Override
    public String toString() {
        return display();
    }
}
