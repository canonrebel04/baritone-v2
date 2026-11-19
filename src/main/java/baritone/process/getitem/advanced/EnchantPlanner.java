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
 * Plan A8/A25: the next action for an enchant / rename / clean goal, from the live inventory. Order of routes:
 * enchanted books (or a second enchanted copy of the item) on an anvil, then an enchanting table with a bounded
 * number of grindstone rerolls; renames and cleaning are one anvil / grindstone use. Anvil costs use the vanilla
 * prior-work penalty to refuse "Too Expensive!" combinations up front. Pure.
 */
public final class EnchantPlanner {
    public static final String BOOK = "minecraft:enchanted_book";
    public static final String LAPIS = "minecraft:lapis_lazuli";

    /** Mutually exclusive groups (vanilla exclusive_set tags). */
    static final List<Set<String>> EXCLUSIVE = Arrays.asList(
            set("fortune", "silk_touch"),
            set("protection", "fire_protection", "blast_protection", "projectile_protection"),
            set("sharpness", "smite", "bane_of_arthropods", "density", "breach"),
            set("infinity", "mending"),
            set("depth_strider", "frost_walker"),
            set("riptide", "loyalty"),
            set("riptide", "channeling"),
            set("multishot", "piercing"));

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    public static final class Options {
        public boolean anvil = true;
        public boolean table = true;
        public int maxAnvilCost = 39;
        public int rerolls = 3;
        public int rerollsUsed;
        public int levels;
        public boolean collectXp = true;
    }

    public static boolean conflicts(String a, String b) {
        if (a.equals(b)) return false;
        for (Set<String> g : EXCLUSIVE) if (g.contains(a) && g.contains(b)) return true;
        return false;
    }

    /** Vanilla prior-work penalty after {@code uses} anvil uses: 2^n - 1. */
    public static int penaltyAfter(int uses) {
        return (1 << Math.min(uses, 30)) - 1;
    }

    /** Estimated anvil level cost to put {@code add}'s enchants on {@code base} (book multipliers ~1 per level). */
    public static int estimateCost(StackDesc base, StackDesc add, Map<String, Integer> wanted) {
        int c = base.repairCost + add.repairCost;
        for (Map.Entry<String, Integer> e : add.enchants.entrySet()) {
            if (wanted.containsKey(e.getKey()) || base.level(e.getKey()) > 0) c += Math.max(1, e.getValue());
        }
        return c;
    }

    public static Next next(ItemSpec spec, int want, List<StackDesc> inv, Options o) {
        if (spec.isPotion()) throw new IllegalArgumentException("potion spec");
        int have = spec.have(inv);
        if (have >= want) return Next.done();
        StackDesc base = pickBase(spec, inv);
        if (base == null) return Next.need(spec.item, 1, "to enchant");
        if (spec.clean) return Next.grindstone(base.slot);
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : spec.enchants.entrySet()) {
            if (base.level(e.getKey()) < e.getValue()) missing.put(e.getKey(), e.getValue());
        }
        if (missing.isEmpty()) {
            // only the name is left
            int cost = base.repairCost + 1;
            if (cost > o.maxAnvilCost) return Next.fail("renaming would cost " + cost + " levels (too expensive)");
            if (o.levels < cost) return xpOrFail(o, cost);
            return Next.rename(base.slot, spec.name);
        }
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            for (String has : base.enchants.keySet()) {
                if (conflicts(e.getKey(), has)) {
                    if (o.rerollsUsed < o.rerolls || o.table) return Next.grindstone(base.slot);
                    return Next.fail(has + " on the item conflicts with " + e.getKey());
                }
            }
        }
        if (o.anvil) {
            StackDesc best = null;
            int bestCost = Integer.MAX_VALUE;
            for (StackDesc s : inv) {
                if (s == base || s.slot == base.slot) continue;
                if (!s.isBook() && !s.item.equals(base.item)) continue;
                boolean helps = false, clash = false;
                for (Map.Entry<String, Integer> e : missing.entrySet()) if (s.level(e.getKey()) >= e.getValue()) helps = true;
                for (String a : s.enchants.keySet()) {
                    for (String b : base.enchants.keySet()) if (conflicts(a, b)) clash = true;
                    for (String b : spec.enchants.keySet()) if (conflicts(a, b)) clash = true;
                }
                if (!helps || clash) continue;
                int cost = estimateCost(base, s, spec.enchants);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = s;
                }
            }
            if (best != null) {
                if (bestCost > o.maxAnvilCost) {
                    return Next.fail("the anvil would cost about " + bestCost + " levels (limit " + o.maxAnvilCost + ")");
                }
                if (o.levels < bestCost) return xpOrFail(o, bestCost);
                return Next.anvil(base.slot, best.slot);
            }
        }
        if (o.table) {
            if (!base.enchants.isEmpty()) {
                if (o.rerollsUsed >= o.rerolls) return Next.fail("the enchanting table didn't give " + text(missing) + " after " + o.rerollsUsed + " rerolls");
                return Next.grindstone(base.slot);
            }
            int lapis = BrewPlanner.count(inv, LAPIS);
            if (lapis < 3) return Next.need(LAPIS, 3 - lapis, "for the enchanting table");
            if (o.levels < 30) return xpOrFail(o, 30);
            return Next.enchant(base.slot, false);
        }
        return Next.fail("no enchanted book with " + text(missing) + " and the enchanting table is off");
    }

    private static Next xpOrFail(Options o, int levels) {
        if (o.collectXp) return Next.xp(levels - o.levels);
        return Next.fail("needs " + levels + " levels (have " + o.levels + ")");
    }

    /** The copy of the item closest to the goal (most wanted enchant levels, fewest uses). */
    static StackDesc pickBase(ItemSpec spec, List<StackDesc> inv) {
        StackDesc best = null;
        int bestScore = Integer.MIN_VALUE;
        for (StackDesc s : inv) {
            if (!spec.item.equals(s.item)) continue;
            int score = 0;
            for (Map.Entry<String, Integer> e : spec.enchants.entrySet()) score += 10 * Math.min(e.getValue(), s.level(e.getKey()));
            score -= s.repairCost;
            if (spec.clean) score = -s.enchants.size();
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * Which enchanting-table slot (0..2) to take given the offers ({@code clue[i]} enchant id, {@code clueLevel[i]},
     * {@code cost[i]} levels; cost 0 = empty slot): a slot whose shown enchant is wanted at a high enough level, else
     * -1 (reroll with the cheapest slot or give up).
     */
    public static int pickOffer(Map<String, Integer> wanted, String[] clue, int[] clueLevel, int[] cost, int levels, int lapis) {
        int best = -1;
        for (int i = 2; i >= 0; i--) {
            if (cost[i] <= 0 || cost[i] > levels || lapis < i + 1 || clue[i] == null) continue;
            Integer w = wanted.get(clue[i]);
            if (w != null && clueLevel[i] >= w) return i;
        }
        return best;
    }

    /** Cheapest available offer for a reroll, or -1. */
    public static int cheapestOffer(int[] cost, int levels, int lapis) {
        for (int i = 0; i < 3; i++) if (cost[i] > 0 && cost[i] <= levels && lapis >= i + 1) return i;
        return -1;
    }

    static String text(Map<String, Integer> m) {
        StringJoiner j = new StringJoiner(", ");
        m.forEach((k, v) -> j.add(k.replace('_', ' ') + " " + v));
        return j.toString();
    }
}
