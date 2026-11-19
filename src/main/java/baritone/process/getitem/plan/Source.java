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

import java.util.*;

/**
 * A non-mining, non-crafting way to obtain an item that the world currently offers (plan M3): a remembered
 * villager trade (A28), fishing (A29), hunting a mob seen nearby (A30), shearing / milking (A35), a crop farm
 * with mature plants (A33/A39), growing a tree from a sapling (A34) or a user's mob farm (A38). Built game-side by
 * {@link baritone.process.getitem.sources.SourceCatalog}; the planner weighs it against mining and recipes by
 * time (A37).
 */
public final class Source {
    public enum Kind { TRADE, FISH, HUNT, SHEAR, MILK, HARVEST, TREE, MOB_FARM, COMPOST }

    public final Kind kind;
    /** Item produced. */
    public final String item;
    /** Items produced per batch (one trade, one catch, one kill, one tree, one harvested plant...). */
    public final double perBatch;
    /** Seconds per batch once there. */
    public final double batchSeconds;
    /** One-off seconds to get there / set up. */
    public final double setupSeconds;
    /** Max batches available right now (trade stock, mature crops, mobs seen); {@link Integer#MAX_VALUE} = unlimited. */
    public final int maxBatches;
    /** Items consumed per batch (trade costs, sapling + bone meal, a bucket...). */
    public final Map<String, Integer> inputs;
    /** Item that must be held but isn't consumed (fishing rod, shears); null = none. */
    public final String tool;
    /** Where: villager uuid, "x,y,z" of a farm / spot, an entity type...; free-form for the executor. */
    public final String ref;
    public final String label;

    public Source(Kind kind, String item, double perBatch, double batchSeconds, double setupSeconds, int maxBatches,
                  Map<String, Integer> inputs, String tool, String ref, String label) {
        this.kind = kind;
        this.item = item;
        this.perBatch = perBatch;
        this.batchSeconds = batchSeconds;
        this.setupSeconds = setupSeconds;
        this.maxBatches = maxBatches;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.tool = tool;
        this.ref = ref;
        this.label = label;
    }

    public boolean capped() {
        return maxBatches != Integer.MAX_VALUE;
    }

    /** Max items available now. */
    public int maxItems() {
        return capped() ? (int) Math.floor(maxBatches * perBatch + 1e-9) : Integer.MAX_VALUE;
    }

    public int batchesFor(int items) {
        return (int) Math.ceil(items / perBatch - 1e-9);
    }

    String key() {
        return kind + ":" + item + ":" + perBatch + ":" + batchSeconds + ":" + setupSeconds + ":" + maxBatches + ":" + new TreeMap<>(inputs) + ":" + tool + ":" + ref;
    }

    @Override
    public String toString() {
        return label;
    }
}
