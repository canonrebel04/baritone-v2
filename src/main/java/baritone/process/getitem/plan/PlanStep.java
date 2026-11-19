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

import baritone.process.getitem.data.Recipe;

import java.util.*;

/** One executable step of a Get Item plan (plan 4.2 task queue). */
public final class PlanStep {
    public enum Kind { MINE, PLACE_STATION, CRAFT, SMELT, WITHDRAW, TRAVEL, GATHER, OPERATE }

    public final Kind kind;
    /** Items this step produces (a MINE step for a tag goal may produce any of several). */
    public final List<String> items;
    /** How many items the step produces. */
    public final int count;
    /** MINE: blocks to break (any of them), cheapest first. PLACE_STATION: the station block. TRAVEL: the dimension. */
    public final List<String> blocks;
    /** MINE: expected number of blocks to break. */
    public final int blockCount;
    /** MINE: tool the plan expects to use (null = any/hand). */
    public final String tool;
    /** CRAFT/SMELT. */
    public final Recipe recipe;
    public final int batches;
    /** CRAFT/SMELT: concrete input items chosen for the recipe's ingredients (merged counts). */
    public final Map<String, Integer> inputs;
    /** SMELT: fuel item and count. */
    public final String fuel;
    public final int fuelCount;
    public final int depth;
    public final double seconds;
    /** GATHER: the world source used (trade, farm, fishing, hunting, ...). */
    public final Source source;
    /** OPERATE: what is done at the station (brew, anvil, enchant, ...), for display. */
    public final String label;

    private PlanStep(Kind kind, List<String> items, int count, List<String> blocks, int blockCount, String tool,
                     Recipe recipe, int batches, Map<String, Integer> inputs, String fuel, int fuelCount, int depth,
                     double seconds) {
        this.kind = kind;
        this.items = Collections.unmodifiableList(items);
        this.count = count;
        this.blocks = Collections.unmodifiableList(blocks);
        this.blockCount = blockCount;
        this.tool = tool;
        this.recipe = recipe;
        this.batches = batches;
        this.inputs = Collections.unmodifiableMap(inputs);
        this.fuel = fuel;
        this.fuelCount = fuelCount;
        this.depth = depth;
        this.seconds = seconds;
        this.source = null;
        this.label = null;
    }

    private PlanStep(String label, String station, List<String> items, int count) {
        this.kind = Kind.OPERATE;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.count = count;
        this.blocks = station == null ? Collections.emptyList() : Collections.singletonList(station);
        this.blockCount = 0;
        this.tool = null;
        this.recipe = null;
        this.batches = 1;
        this.inputs = Collections.emptyMap();
        this.fuel = null;
        this.fuelCount = 0;
        this.depth = 0;
        this.seconds = 0;
        this.source = null;
        this.label = label;
    }

    /** An advanced-goal operation (A8/A25/A27/A31/A43/A45): brewing, anvil, enchanting, filling bottles... */
    public static PlanStep operate(String label, String station, List<String> items, int count) {
        return new PlanStep(label, station, items, count);
    }

    private PlanStep(Source source, int count, int batches, Map<String, Integer> inputs, int depth, double seconds) {
        this.kind = Kind.GATHER;
        this.items = Collections.singletonList(source.item);
        this.count = count;
        this.blocks = Collections.emptyList();
        this.blockCount = 0;
        this.tool = source.tool;
        this.recipe = null;
        this.batches = batches;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.fuel = null;
        this.fuelCount = 0;
        this.depth = depth;
        this.seconds = seconds;
        this.source = source;
        this.label = null;
    }

    /** Obtain {@code count} items from a world source in {@code batches} batches, consuming {@code inputs}. */
    public static PlanStep gather(Source source, int count, int batches, Map<String, Integer> inputs, int depth, double seconds) {
        return new PlanStep(source, count, batches, inputs, depth, seconds);
    }

    public static PlanStep mine(List<String> items, int count, List<String> blocks, int blockCount, String tool, int depth, double seconds) {
        return new PlanStep(Kind.MINE, items, count, blocks, blockCount, tool, null, 0, Collections.emptyMap(), null, 0, depth, seconds);
    }

    public static PlanStep place(String station, int depth, double seconds) {
        return new PlanStep(Kind.PLACE_STATION, Collections.singletonList(station), 1, Collections.singletonList(station), 1, null, null, 0, Collections.emptyMap(), null, 0, depth, seconds);
    }

    public static PlanStep craft(Recipe recipe, int batches, Map<String, Integer> inputs, String fuel, int fuelCount, int depth, double seconds) {
        Kind kind = recipe.type.cooking ? Kind.SMELT : Kind.CRAFT;
        String station = recipe.station();
        return new PlanStep(kind, Collections.singletonList(recipe.output), batches * recipe.outputCount,
                station == null ? Collections.emptyList() : Collections.singletonList(station), 0, null, recipe, batches,
                new LinkedHashMap<>(inputs), fuel, fuelCount, depth, seconds);
    }

    /** Take {@code count} items from the user's (unprotected) containers (A24). */
    public static PlanStep withdraw(String item, int count, int depth, double seconds) {
        return new PlanStep(Kind.WITHDRAW, Collections.singletonList(item), count, Collections.emptyList(), 0, null, null, 0, Collections.emptyMap(), null, 0, depth, seconds);
    }

    /** Go through a portal to {@code dimension} (Nether/End allowed, decision 9). */
    public static PlanStep travel(String dimension, int depth, double seconds) {
        return new PlanStep(Kind.TRAVEL, Collections.emptyList(), 0, Collections.singletonList(dimension), 0, null, null, 0, Collections.emptyMap(), null, 0, depth, seconds);
    }

    public String dimension() {
        return kind == Kind.TRAVEL ? blocks.get(0) : null;
    }

    public static String dimensionName(String dim) {
        if (dim == null) return "?";
        if (dim.endsWith("the_nether")) return "the Nether";
        if (dim.endsWith("the_end")) return "the End";
        if (dim.endsWith("overworld")) return "the Overworld";
        return shortId(dim);
    }

    public String station() {
        if (kind == Kind.TRAVEL || kind == Kind.WITHDRAW || kind == Kind.GATHER) return null;
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case MINE:
                sb.append("Mine ").append(blockCount).append("x ").append(shortId(blocks.get(0)));
                if (blocks.size() > 1) sb.append(" (or ").append(blocks.size() - 1).append(" similar)");
                sb.append(" -> ").append(count).append("x ").append(items.size() > 1 ? "any of " + items.size() + " items" : shortId(items.get(0)));
                if (tool != null) sb.append(" [").append(shortId(tool)).append("]");
                break;
            case OPERATE:
                sb.append(label);
                break;
            case PLACE_STATION:
                sb.append("Place ").append(shortId(blocks.get(0)));
                break;
            case WITHDRAW:
                sb.append("Take ").append(count).append("x ").append(shortId(items.get(0))).append(" from your chests");
                break;
            case TRAVEL:
                sb.append("Travel to ").append(dimensionName(blocks.get(0)));
                break;
            case GATHER: {
                sb.append(source.label).append(" -> ").append(count).append("x ").append(shortId(items.get(0)));
                if (!inputs.isEmpty()) {
                    StringJoiner j = new StringJoiner(", ");
                    inputs.forEach((k, v) -> j.add(v + "x " + shortId(k)));
                    sb.append(" (uses ").append(j).append(")");
                }
                if (tool != null) sb.append(" [").append(shortId(tool)).append("]");
                break;
            }
            default:
                sb.append(verb()).append(count).append("x ").append(shortId(recipe.output)).append(" from ");
                StringJoiner j = new StringJoiner(", ");
                inputs.forEach((k, v) -> j.add(v + "x " + shortId(k)));
                sb.append(j);
                if (fuel != null) sb.append(" + ").append(fuelCount).append("x ").append(shortId(fuel)).append(" fuel");
                String st = station();
                sb.append(st == null ? " (inventory grid)" : " (" + shortId(st) + ")");
        }
        return sb.toString();
    }

    private String verb() {
        if (recipe != null) {
            switch (recipe.type) {
                case CAMPFIRE_COOKING: return "Cook ";
                case STONECUTTING: return "Cut ";
                case SMITHING: return "Smith ";
                default: break;
            }
        }
        return kind == Kind.SMELT ? "Smelt " : "Craft ";
    }

    @Override
    public String toString() {
        return describe();
    }
}
