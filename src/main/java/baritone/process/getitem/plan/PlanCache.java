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

/** Memoises plans by goal + inventory + options (plan A10), so re-planning every step is cheap. */
public final class PlanCache {
    private final Planner planner;
    private final int size;
    private final LinkedHashMap<String, Plan> cache;
    private int hits, misses;

    public PlanCache(Planner planner, int size) {
        this.planner = planner;
        this.size = size;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Plan> eldest) {
                return size() > PlanCache.this.size;
            }
        };
    }

    public synchronized Plan plan(Goal goal, Map<String, Integer> inventory, Planner.Options o) {
        StringBuilder key = new StringBuilder(goal.display).append('x').append(goal.count).append('|').append(goal.options.hashCode()).append('|').append(o.key()).append('|');
        new TreeMap<>(inventory).forEach((k, v) -> { if (v > 0) key.append(k).append('=').append(v).append(','); });
        String k = key.toString();
        Plan p = cache.get(k);
        if (p != null) {
            hits++;
            return p;
        }
        misses++;
        p = planner.plan(goal, inventory, o);
        cache.put(k, p);
        return p;
    }

    public synchronized void clear() {
        cache.clear();
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    public Planner planner() {
        return planner;
    }
}
