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
 * Plan A27: vanilla brewing as a graph (water -> awkward -> effect -> long/strong/corrupted, then splash and
 * lingering) and the state-driven next action for "have N of this potion": fill enough water bottles first, then
 * advance the stage holding the most bottles (three per brew), fuel with blaze powder. Pure.
 */
public final class BrewPlanner {
    public static final String POTION = "minecraft:potion";
    public static final String SPLASH = "minecraft:splash_potion";
    public static final String LINGERING = "minecraft:lingering_potion";
    public static final String WATER = "water";
    public static final String FUEL = "minecraft:blaze_powder";

    /** from potion -> (ingredient -> to potion), insertion order = preference. */
    static final Map<String, Map<String, String>> EDGES = new LinkedHashMap<>();

    private static void edge(String from, String ingredient, String to) {
        EDGES.computeIfAbsent(from, k -> new LinkedHashMap<>()).put("minecraft:" + ingredient, to);
    }

    static {
        edge(WATER, "nether_wart", "awkward");
        edge(WATER, "redstone", "mundane");
        edge(WATER, "glowstone_dust", "thick");
        edge(WATER, "fermented_spider_eye", "weakness");
        String[][] awkward = {
                {"sugar", "swiftness"}, {"rabbit_foot", "leaping"}, {"glistering_melon_slice", "healing"},
                {"spider_eye", "poison"}, {"ghast_tear", "regeneration"}, {"blaze_powder", "strength"},
                {"magma_cream", "fire_resistance"}, {"pufferfish", "water_breathing"}, {"golden_carrot", "night_vision"},
                {"turtle_helmet", "turtle_master"}, {"phantom_membrane", "slow_falling"}, {"breeze_rod", "wind_charged"},
                {"slime_block", "oozing"}, {"stone", "infested"}, {"cobweb", "weaving"}};
        for (String[] a : awkward) edge("awkward", a[0], a[1]);
        String[][] corrupt = {
                {"swiftness", "slowness"}, {"long_swiftness", "long_slowness"}, {"leaping", "slowness"},
                {"long_leaping", "long_slowness"}, {"healing", "harming"}, {"strong_healing", "strong_harming"},
                {"poison", "harming"}, {"long_poison", "harming"}, {"strong_poison", "strong_harming"},
                {"night_vision", "invisibility"}, {"long_night_vision", "long_invisibility"}};
        for (String[] c : corrupt) edge(c[0], "fermented_spider_eye", c[1]);
        for (String p : new String[]{"swiftness", "leaping", "poison", "regeneration", "strength", "fire_resistance",
                "water_breathing", "night_vision", "invisibility", "slowness", "weakness", "turtle_master", "slow_falling"}) {
            edge(p, "redstone", "long_" + p);
        }
        for (String p : new String[]{"swiftness", "leaping", "healing", "harming", "poison", "regeneration", "strength",
                "slowness", "turtle_master"}) {
            edge(p, "glowstone_dust", "strong_" + p);
        }
    }

    /** One brew: {@code ingredient} turns {@code from} into {@code to}. */
    public static final class Brew {
        public final String fromContainer, fromPotion, ingredient, toContainer, toPotion;

        Brew(String fc, String fp, String ingredient, String tc, String tp) {
            this.fromContainer = fc;
            this.fromPotion = fp;
            this.ingredient = ingredient;
            this.toContainer = tc;
            this.toPotion = tp;
        }

        @Override
        public String toString() {
            return fromPotion + " + " + ingredient.replace("minecraft:", "") + " -> " + toContainer.replace("minecraft:", "") + ":" + toPotion;
        }
    }

    public static boolean known(String potion) {
        if (WATER.equals(potion)) return true;
        for (Map<String, String> m : EDGES.values()) if (m.containsValue(potion)) return true;
        return false;
    }

    /** Shortest brewing chain from a water bottle to the target, or null when it can't be brewed. */
    public static List<Brew> path(String container, String potion) {
        if (!ItemSpec.POTION_ITEMS.contains(container)) return null;
        List<String> chain = potionChain(potion);
        if (chain == null) return null;
        List<Brew> out = new ArrayList<>();
        for (int i = 0; i + 1 < chain.size(); i += 2) {
            out.add(new Brew(POTION, chain.get(i), chain.get(i + 1), POTION, chain.get(i + 2)));
        }
        if (!POTION.equals(container)) out.add(new Brew(POTION, potion, "minecraft:gunpowder", SPLASH, potion));
        if (LINGERING.equals(container)) out.add(new Brew(SPLASH, potion, "minecraft:dragon_breath", LINGERING, potion));
        return out;
    }

    /** [water, ingredient, potion, ingredient, ..., target] by BFS, or null. */
    private static List<String> potionChain(String target) {
        if (WATER.equals(target)) return Collections.singletonList(WATER);
        Map<String, String[]> prev = new HashMap<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(WATER);
        prev.put(WATER, null);
        while (!q.isEmpty()) {
            String p = q.poll();
            if (p.equals(target)) break;
            for (Map.Entry<String, String> e : EDGES.getOrDefault(p, Collections.emptyMap()).entrySet()) {
                if (prev.containsKey(e.getValue())) continue;
                prev.put(e.getValue(), new String[]{p, e.getKey()});
                q.add(e.getValue());
            }
        }
        if (!prev.containsKey(target)) return null;
        LinkedList<String> out = new LinkedList<>();
        String cur = target;
        while (cur != null) {
            out.addFirst(cur);
            String[] pr = prev.get(cur);
            if (pr == null) break;
            out.addFirst(pr[1]);
            cur = pr[0];
        }
        return out;
    }

    /**
     * Next action towards {@code want} potions matching {@code spec}.
     *
     * @param inv       inventory stacks
     * @param standFuel the brewing stand is known to hold fuel already
     */
    public static Next next(ItemSpec spec, int want, List<StackDesc> inv, boolean standFuel) {
        List<Brew> path = path(spec.item, spec.potion);
        if (path == null) return Next.fail("don't know how to brew " + spec.display());
        int have = spec.have(inv);
        if (have >= want) return Next.done();
        int remaining = want - have;
        // stage i = bottles holding brews[i].from
        int[] stage = new int[path.size()];
        StackDesc[] sample = new StackDesc[path.size()];
        int pipeline = 0;
        for (int i = 0; i < path.size(); i++) {
            Brew b = path.get(i);
            for (StackDesc s : inv) {
                if (b.fromContainer.equals(s.item) && b.fromPotion.equals(s.potion)) {
                    stage[i] += s.count;
                    if (sample[i] == null) sample[i] = s;
                }
            }
            pipeline += stage[i];
        }
        if (pipeline < remaining) {
            int missing = remaining - pipeline;
            int bottles = count(inv, "minecraft:glass_bottle");
            if (bottles > 0) return Next.fill(Math.min(missing, bottles));
            return Next.need("minecraft:glass_bottle", missing, "for water bottles");
        }
        int best = -1;
        for (int i = 0; i < path.size(); i++) {
            if (stage[i] == 0) continue;
            if (best < 0 || Math.min(3, stage[i]) >= Math.min(3, stage[best])) best = i;
        }
        Brew b = path.get(best);
        if (count(inv, b.ingredient) < 1) return Next.need(b.ingredient, 1, "brewing ingredient");
        int fuelNeed = FUEL.equals(b.ingredient) ? 2 : 1;
        if (!standFuel && count(inv, FUEL) < fuelNeed) return Next.need(FUEL, fuelNeed - count(inv, FUEL), "brewing stand fuel");
        return Next.brew(sample[best], b.ingredient, Math.min(3, stage[best]));
    }

    static int count(List<StackDesc> inv, String item) {
        int n = 0;
        for (StackDesc s : inv) if (item.equals(s.item) && s.potion == null && s.enchants.isEmpty()) n += s.count;
        return n;
    }

    /** Rough ingredient list for the explanation ("1x nether_wart, 1x sugar, 1x glowstone_dust, 1x gunpowder"). */
    public static List<String> describe(ItemSpec spec, int want) {
        List<Brew> path = path(spec.item, spec.potion);
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        int batches = (want + 2) / 3;
        out.add("Brew " + want + "x " + spec.display() + " in " + batches + " batch" + (batches == 1 ? "" : "es") + " (3 bottles each):");
        out.add("  fill " + want + " water bottles, then " + path.size() + " brews of 20s");
        for (Brew b : path) out.add("  " + b);
        out.add("  fuel: blaze powder (1 per 20 brews)");
        return out;
    }
}
