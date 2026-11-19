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
 * Where things generate (plan A14): dimension, best Y and band per ore/resource, plus structure/biome notes. Used
 * for exploration depth (A15), vein scoring (A13) and the plan explainer.
 */
public final class BiomeHints {
    public static final class Hint {
        public final String block;
        public final String dimension;
        public final int bestY, minY, maxY;
        public final String note;

        Hint(String block, String dimension, int bestY, int minY, int maxY, String note) {
            this.block = block;
            this.dimension = dimension;
            this.bestY = bestY;
            this.minY = minY;
            this.maxY = maxY;
            this.note = note;
        }

        public boolean underground() {
            return maxY < 60;
        }

        public String describe() {
            String d = dimension.endsWith("nether") ? "Nether" : dimension.endsWith("end") ? "End" : "Overworld";
            return block.replace("minecraft:", "") + ": " + d + ", best y " + bestY + " (" + minY + ".." + maxY + ")" + (note.isEmpty() ? "" : ", " + note);
        }
    }

    private static final Map<String, Hint> HINTS = new HashMap<>();
    private static final String OW = "minecraft:overworld", NE = "minecraft:the_nether", EN = "minecraft:the_end";

    private static void add(String id, String dim, int best, int min, int max, String note) {
        HINTS.put("minecraft:" + id, new Hint("minecraft:" + id, dim, best, min, max, note));
    }

    static {
        add("coal_ore", OW, 96, 0, 256, "exposed in mountains and caves");
        add("deepslate_coal_ore", OW, -5, -8, 0, "");
        add("iron_ore", OW, 16, -24, 72, "also a large band at y 232 in mountains");
        add("deepslate_iron_ore", OW, -8, -64, 0, "");
        add("copper_ore", OW, 48, -16, 112, "most in dripstone caves");
        add("deepslate_copper_ore", OW, -8, -16, 0, "");
        add("gold_ore", OW, -16, -64, 32, "extra in badlands up to y 256");
        add("deepslate_gold_ore", OW, -16, -64, 0, "");
        add("redstone_ore", OW, -58, -64, 16, "");
        add("deepslate_redstone_ore", OW, -58, -64, 0, "");
        add("lapis_ore", OW, 0, -64, 64, "");
        add("deepslate_lapis_ore", OW, -8, -64, 0, "");
        add("diamond_ore", OW, -58, -64, 16, "branch-mine at y -58; also in buried treasure");
        add("deepslate_diamond_ore", OW, -58, -64, 0, "");
        add("emerald_ore", OW, 236, -16, 320, "mountain biomes only; villagers trade emeralds");
        add("deepslate_emerald_ore", OW, -8, -16, 0, "mountain biomes only");
        add("ancient_debris", NE, 15, 8, 22, "blast-resistant, strip mine at y 15; bastions have some in chests");
        add("nether_quartz_ore", NE, 15, 10, 117, "");
        add("nether_gold_ore", NE, 15, 10, 117, "");
        add("glowstone", NE, 100, 30, 125, "ceilings");
        add("obsidian", OW, -40, -64, 64, "make with water on lava pools");
        add("sand", OW, 63, 50, 80, "deserts, beaches");
        add("clay", OW, 58, 40, 64, "riverbeds, swamps, lush caves");
        add("end_stone", EN, 50, 0, 80, "");
        add("amethyst_cluster", OW, -20, -64, 30, "amethyst geodes");
        add("blackstone", NE, 30, 5, 120, "basalt deltas, bastions");
        add("soul_sand", NE, 32, 20, 70, "soul sand valleys");
    }

    private BiomeHints() {
    }

    public static Hint of(String block) {
        return HINTS.get(block);
    }

    /** Y to explore at for these blocks (best Y of the first hinted block), or {@code fallback}. */
    public static int exploreY(List<String> blocks, int fallback) {
        for (String b : blocks) {
            Hint h = HINTS.get(b);
            if (h != null) return h.bestY;
        }
        return fallback;
    }

    public static List<String> describe(Collection<String> blocks) {
        List<String> out = new ArrayList<>();
        for (String b : blocks) {
            Hint h = HINTS.get(b);
            if (h != null && !out.contains(h.describe())) out.add(h.describe());
        }
        return out;
    }
}
