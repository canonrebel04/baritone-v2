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

/** Kinds of recipe the Get Item planner understands (plan A1). */
public enum RecipeType {
    SHAPED(true, false),
    SHAPELESS(true, false),
    SMELTING(false, true),
    BLASTING(false, true),
    SMOKING(false, true),
    CAMPFIRE_COOKING(false, true),
    STONECUTTING(false, false),
    SMITHING(false, false);

    public final boolean crafting;
    public final boolean cooking;

    RecipeType(boolean crafting, boolean cooking) {
        this.crafting = crafting;
        this.cooking = cooking;
    }

    /** Cooking that burns fuel (furnace, blast furnace, smoker); a campfire cooks for free. */
    public boolean needsFuel() {
        return cooking && this != CAMPFIRE_COOKING;
    }

    /** Wall-clock ticks to cook {@code batches} items: a campfire cooks four at once. */
    public int cookTicks(int batches, int cookTime) {
        int t = cookTime <= 0 ? (this == CAMPFIRE_COOKING ? 600 : 200) : cookTime;
        return this == CAMPFIRE_COOKING ? (batches + 3) / 4 * t : batches * t;
    }

    /** Dataset type name ("shaped", "smelting", ...) to enum, or null. */
    public static RecipeType fromDataset(String name) {
        if (name == null) return null;
        switch (name) {
            case "shaped": return SHAPED;
            case "shapeless": return SHAPELESS;
            case "smelting": return SMELTING;
            case "blasting": return BLASTING;
            case "smoking": return SMOKING;
            case "campfire_cooking": return CAMPFIRE_COOKING;
            case "stonecutting": return STONECUTTING;
            case "smithing": return SMITHING;
            default: return null;
        }
    }

    /** The station block a recipe of this type needs, or null for the 2x2 inventory grid. */
    public String station(boolean needsTable) {
        switch (this) {
            case SHAPED:
            case SHAPELESS:
                return needsTable ? "minecraft:crafting_table" : null;
            case SMELTING: return "minecraft:furnace";
            case BLASTING: return "minecraft:blast_furnace";
            case SMOKING: return "minecraft:smoker";
            case CAMPFIRE_COOKING: return "minecraft:campfire";
            case STONECUTTING: return "minecraft:stonecutter";
            case SMITHING: return "minecraft:smithing_table";
            default: return null;
        }
    }
}
