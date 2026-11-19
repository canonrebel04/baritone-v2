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

package baritone.process.getitem.sources;

import java.util.*;

/** Static yield tables for M3 world sources (expected values, no Looting / Fortune). */
public final class Yields {
    private Yields() {
    }

    public static final class Drop {
        public final String entity;
        public final String item;
        public final double perKill;
        public final boolean hostile;

        Drop(String entity, String item, double perKill, boolean hostile) {
            this.entity = entity;
            this.item = item;
            this.perKill = perKill;
            this.hostile = hostile;
        }
    }

    /** A30 mob drops. */
    public static final List<Drop> MOB_DROPS;
    /** A33 crops: farm type (block id) -> {item, expected net items per harvested plant (after replanting)}. */
    public static final Map<String, Object[]> CROPS;
    /** A34 trees: sapling -> {log, expected logs per tree}. */
    public static final Map<String, Object[]> TREES;
    /** A29 fishing: item -> chance per catch. */
    public static final Map<String, Double> FISH;
    /** Bone meal per sapling grown (two 45% stage advances). */
    public static final int BONE_MEAL_PER_TREE = 4;

    static {
        List<Drop> d = new ArrayList<>();
        hostile(d, "zombie", "rotten_flesh", 1.0);
        hostile(d, "husk", "rotten_flesh", 1.0);
        hostile(d, "drowned", "rotten_flesh", 1.0);
        hostile(d, "skeleton", "bone", 1.0);
        hostile(d, "skeleton", "arrow", 1.0);
        hostile(d, "stray", "bone", 1.0);
        hostile(d, "spider", "string", 1.0);
        hostile(d, "spider", "spider_eye", 0.33);
        hostile(d, "cave_spider", "string", 1.0);
        hostile(d, "creeper", "gunpowder", 1.0);
        hostile(d, "enderman", "ender_pearl", 0.5);
        hostile(d, "blaze", "blaze_rod", 0.5);
        hostile(d, "slime", "slime_ball", 1.0);
        hostile(d, "magma_cube", "magma_cream", 0.25);
        hostile(d, "ghast", "ghast_tear", 0.5);
        hostile(d, "ghast", "gunpowder", 1.0);
        hostile(d, "wither_skeleton", "coal", 0.33);
        hostile(d, "wither_skeleton", "bone", 1.0);
        hostile(d, "zombified_piglin", "rotten_flesh", 1.0);
        hostile(d, "zombified_piglin", "gold_nugget", 0.5);
        hostile(d, "phantom", "phantom_membrane", 0.5);
        hostile(d, "guardian", "prismarine_shard", 1.0);
        hostile(d, "witch", "glowstone_dust", 0.4);
        hostile(d, "witch", "redstone", 0.4);
        passive(d, "cow", "leather", 1.0);
        passive(d, "cow", "beef", 2.0);
        passive(d, "pig", "porkchop", 2.0);
        passive(d, "chicken", "feather", 1.0);
        passive(d, "chicken", "chicken", 1.0);
        passive(d, "sheep", "mutton", 1.5);
        passive(d, "rabbit", "rabbit_hide", 0.5);
        passive(d, "rabbit", "rabbit", 0.5);
        passive(d, "squid", "ink_sac", 2.0);
        passive(d, "glow_squid", "glow_ink_sac", 2.0);
        passive(d, "cod", "cod", 1.0);
        passive(d, "salmon", "salmon", 1.0);
        MOB_DROPS = Collections.unmodifiableList(d);

        Map<String, Object[]> c = new LinkedHashMap<>();
        c.put("minecraft:wheat", new Object[]{"minecraft:wheat", 1.0});
        c.put("minecraft:carrots", new Object[]{"minecraft:carrot", 1.7});
        c.put("minecraft:potatoes", new Object[]{"minecraft:potato", 1.7});
        c.put("minecraft:beetroots", new Object[]{"minecraft:beetroot", 1.0});
        c.put("minecraft:sugar_cane", new Object[]{"minecraft:sugar_cane", 1.0});
        c.put("minecraft:bamboo", new Object[]{"minecraft:bamboo", 1.0});
        c.put("minecraft:cactus", new Object[]{"minecraft:cactus", 1.0});
        c.put("minecraft:melon_stem", new Object[]{"minecraft:melon_slice", 5.0});
        c.put("minecraft:pumpkin_stem", new Object[]{"minecraft:pumpkin", 1.0});
        c.put("minecraft:nether_wart", new Object[]{"minecraft:nether_wart", 2.0});
        c.put("minecraft:cocoa", new Object[]{"minecraft:cocoa_beans", 2.0});
        c.put("minecraft:sweet_berry_bush", new Object[]{"minecraft:sweet_berries", 2.0});
        CROPS = Collections.unmodifiableMap(c);

        Map<String, Object[]> t = new LinkedHashMap<>();
        t.put("minecraft:oak_sapling", new Object[]{"minecraft:oak_log", 5.0});
        t.put("minecraft:birch_sapling", new Object[]{"minecraft:birch_log", 6.0});
        t.put("minecraft:spruce_sapling", new Object[]{"minecraft:spruce_log", 7.0});
        t.put("minecraft:jungle_sapling", new Object[]{"minecraft:jungle_log", 5.0});
        t.put("minecraft:acacia_sapling", new Object[]{"minecraft:acacia_log", 6.0});
        t.put("minecraft:cherry_sapling", new Object[]{"minecraft:cherry_log", 7.0});
        TREES = Collections.unmodifiableMap(t);

        Map<String, Double> f = new LinkedHashMap<>();
        f.put("minecraft:cod", 0.51);
        f.put("minecraft:salmon", 0.21);
        f.put("minecraft:pufferfish", 0.11);
        f.put("minecraft:tropical_fish", 0.017);
        FISH = Collections.unmodifiableMap(f);
    }

    private static void hostile(List<Drop> d, String e, String item, double n) {
        d.add(new Drop("minecraft:" + e, "minecraft:" + item, n, true));
    }

    private static void passive(List<Drop> d, String e, String item, double n) {
        d.add(new Drop("minecraft:" + e, "minecraft:" + item, n, false));
    }

    public static List<Drop> dropsOf(String entity) {
        List<Drop> out = new ArrayList<>();
        for (Drop x : MOB_DROPS) if (x.entity.equals(entity)) out.add(x);
        return out;
    }

    /** Crop item for a farm type (e.g. carrots -> carrot), or null. */
    public static String cropItem(String farmType) {
        Object[] c = CROPS.get(farmType);
        return c == null ? null : (String) c[0];
    }
}
