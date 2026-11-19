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

import baritone.process.getitem.data.Ingredient;
import baritone.process.getitem.data.Recipe;
import baritone.process.getitem.data.RecipeType;
import baritone.process.getitem.graph.FuelTable;
import baritone.process.getitem.graph.RecipeGraph;
import baritone.process.getitem.graph.ToolRules;

import java.util.*;

/**
 * Get Item planner (plan A2-A7). Pure Java, deterministic.
 * <ul>
 * <li>Unit costs: Bellman-Ford style relaxation over the recipe graph to a fixed point (handles cycles).</li>
 * <li>Expansion: depth-first over a virtual inventory. Each item tries its routes cheapest first (mine vs. each
 * recipe, A3), with snapshot/restore on dead ends. Tool tiers, stations and furnace fuel are prerequisites (A4).
 * Existing items are used first and tools/ingredients are reserved for their sub-plan (A5). Batches are
 * {@code ceil(need / yield)} and the excess stays in the virtual inventory for later steps (A6/A7).</li>
 * <li>Protected items are never consumed or used as tools.</li>
 * </ul>
 */
public final class Planner {
    /** Recipe types the executor can perform in this milestone. */
    public static final Set<RecipeType> M1_TYPES = Collections.unmodifiableSet(EnumSet.of(
            RecipeType.SHAPED, RecipeType.SHAPELESS, RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING));

    private static final int MAX_ROUTES = 4;
    private static final int MAX_EXPANSIONS = 20000;

    public final RecipeGraph graph;
    public final ToolRules tools;
    public final FuelTable fuel;
    public final CostModel costs;

    /** Settings snapshot used for planning. */
    public static final class Options {
        public Set<RecipeType> types = M1_TYPES;
        public Set<String> protectedItems = Collections.emptySet();
        /** Stations already placed nearby (block ids). */
        public Set<String> stations = Collections.emptySet();
        public String dimension = CostModel.OVERWORLD;
        public int maxDepth = 16;
        public boolean allowPlaceStations = true;
        /** Natural blocks seen near the player (null = unknown); unseen blocks cost more, so nearby wood/ores win. */
        public Set<String> seenBlocks = null;
        /** Items in the user's usable (unprotected) containers nearby (A11/A24); protected items already removed. */
        public Map<String, Integer> containerStock = Collections.emptyMap();
        /** Distance in blocks to the nearest container holding each item (missing = {@link #defaultContainerDistance}). */
        public Map<String, Double> containerDistance = Collections.emptyMap();
        public double defaultContainerDistance = 32;
        /** Dimensions the run may enter (decision 9: Nether/End allowed). The current one is always allowed. */
        public boolean allowNether = true;
        public boolean allowEnd = true;
        /** Blocks never broken as a mine source (A24: chests, spawners, ... and user-protected blocks). */
        public Set<String> protectedBlocks = Collections.emptySet();
        /** M3 world sources: trades, farms, fishing, hunting, trees, mob farms (empty = mine/craft/chests only). */
        public List<Source> sources = Collections.emptyList();

        String sourcesKey() {
            StringBuilder b = new StringBuilder();
            for (Source x : sources) b.append(x.key()).append(';');
            return b.toString();
        }

        String key() {
            return types + "|" + new TreeSet<>(protectedItems) + "|" + new TreeSet<>(stations) + "|" + dimension + "|" + maxDepth + "|" + allowPlaceStations + "|" + (seenBlocks == null ? "-" : new TreeSet<>(seenBlocks).toString())
                    + "|" + new TreeMap<>(containerStock) + "|" + new TreeMap<>(containerDistance) + "|" + defaultContainerDistance + "|" + allowNether + allowEnd + "|" + new TreeSet<>(protectedBlocks) + "|" + sourcesKey();
        }

        String unitKey() {
            return types + "|" + dimension + "|" + (seenBlocks == null ? "-" : new TreeSet<>(seenBlocks).toString()) + "|" + allowNether + allowEnd + "|" + new TreeSet<>(protectedBlocks) + "|" + sourcesKey();
        }

        boolean dimensionAllowed(String dim) {
            if (dim == null || dim.equals(dimension)) return true;
            if (CostModel.NETHER.equals(dim)) return allowNether;
            if (CostModel.END.equals(dim)) return allowEnd;
            return true;
        }
    }

    private final Map<String, Map<String, Double>> unitCostCache = new HashMap<>();

    public Planner(RecipeGraph graph, ToolRules tools, FuelTable fuel, CostModel costs) {
        this.graph = graph;
        this.tools = tools;
        this.fuel = fuel;
        this.costs = costs;
    }

    public static Planner vanilla(RecipeGraph graph) {
        return new Planner(graph, ToolRules.fromTags(graph), FuelTable.vanilla(graph), new CostModel(graph));
    }

    /** Forget cached unit costs (call after merging server recipes). */
    public synchronized void invalidate() {
        unitCostCache.clear();
    }

    // ---------------------------------------------------------------- unit costs

    /** Cost in seconds to get one of each item from scratch (no inventory), INF if unobtainable. */
    public synchronized Map<String, Double> unitCosts(Options o) {
        return unitCosts(o, true);
    }

    /** Unit costs ignoring world sources (what mining / crafting alone would cost). */
    public synchronized Map<String, Double> baseUnitCosts(Options o) {
        return unitCosts(o, false);
    }

    private Map<String, Double> unitCosts(Options o, boolean withSources) {
        String key = (withSources ? "S|" : "B|") + o.unitKey();
        Map<String, Double> cached = unitCostCache.get(key);
        if (cached != null) return cached;
        if (unitCostCache.size() > 16) unitCostCache.clear();
        Map<String, Double> unit = new HashMap<>();
        for (String item : graph.items()) {
            double best = CostModel.INF;
            for (RecipeGraph.MineSource s : graph.mineSources(item)) {
                double c = mineCost(s.block, o);
                if (!tools.acceptableTools(s.block).isEmpty()) c += 0.5; // tool wear
                best = Math.min(best, c / s.expected);
            }
            if (best < CostModel.INF) unit.put(item, best);
        }
        List<Recipe> recipes = new ArrayList<>();
        for (Recipe r : graph.recipes()) if (!r.componentResult && o.types.contains(r.type)) recipes.add(r);
        for (int iter = 0; iter < 200; iter++) {
            boolean changed = false;
            double fuelPerSmelt = fuelCostPerSmelt(unit);
            for (Recipe r : recipes) {
                double c = recipeBatchCost(r, unit, fuelPerSmelt) / r.outputCount;
                if (c < unit.getOrDefault(r.output, CostModel.INF) - 1e-9) {
                    unit.put(r.output, c);
                    changed = true;
                }
            }
            if (withSources) {
                for (Source src : o.sources) {
                    double c = sourceBatchCost(src, unit) / src.perBatch;
                    if (c < unit.getOrDefault(src.item, CostModel.INF) - 1e-9) {
                        unit.put(src.item, c);
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        Map<String, Double> result = Collections.unmodifiableMap(unit);
        unitCostCache.put(key, result);
        return result;
    }

    /** Per-block mine cost under the options: INF for protected blocks and for dimensions the run may not enter. */
    double mineCost(String block, Options o) {
        if (o.protectedBlocks.contains(block)) return CostModel.INF;
        if (!o.dimensionAllowed(requiredDimension(block, o.dimension))) return CostModel.INF;
        return costs.blockSeconds(block, o.dimension, o.seenBlocks);
    }

    /** Dimension a block must be mined in, given where the player is now. */
    String requiredDimension(String block, String here) {
        String only = costs.exclusiveDimension(block);
        if (only != null) return only;
        String h = here == null ? CostModel.OVERWORLD : here;
        if (CostModel.OVERWORLD.equals(h)) return h;
        if (CostModel.NETHER.equals(h) && block.contains("gravel")) return h;
        if (CostModel.END.equals(h) && block.contains("obsidian")) return h;
        return CostModel.OVERWORLD;
    }

    /** Seconds to take {@code count} of an item from a container {@code dist} blocks away. */
    double withdrawSeconds(int count, double dist, boolean tripPlanned) {
        double trip = tripPlanned ? 0 : 3 + dist / 4.3;
        return trip + 0.6 * ceilDiv(Math.max(1, count), 64);
    }

    private double fuelCostPerSmelt(Map<String, Double> unit) {
        double best = CostModel.INF;
        for (String f : fuelCandidates()) {
            int burn = fuel.burnTicks(f);
            if (burn > 0) best = Math.min(best, unit.getOrDefault(f, CostModel.INF) * 200.0 / burn);
        }
        return best == CostModel.INF ? 30 : best;
    }

    private List<String> fuelCandidates() {
        List<String> c = new ArrayList<>(Arrays.asList("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block"));
        c.addAll(graph.itemTag("minecraft:planks"));
        c.addAll(graph.itemTag("minecraft:logs_that_burn"));
        c.add("minecraft:stick");
        return c;
    }

    /** Steady-state seconds per batch of a source (setup amortised over a typical 8 batches). */
    private static double sourceBatchCost(Source src, Map<String, Double> unit) {
        double c = src.batchSeconds + src.setupSeconds / Math.max(1, Math.min(8, src.maxBatches));
        for (Map.Entry<String, Integer> e : src.inputs.entrySet()) {
            c += unit.getOrDefault(e.getKey(), CostModel.INF) * e.getValue();
        }
        if (src.tool != null) c += 0.2;
        return c;
    }

    private double recipeBatchCost(Recipe r, Map<String, Double> unit, double fuelPerSmelt) {
        double c = costs.craftSecondsPerBatch;
        if (r.type.cooking) c += costs.cookSeconds(r.cookTime) + fuelPerSmelt;
        for (Ingredient i : r.ingredients) {
            double best = CostModel.INF;
            for (String opt : i.options) best = Math.min(best, unit.getOrDefault(opt, CostModel.INF));
            c += best * i.count;
        }
        return c;
    }

    // ---------------------------------------------------------------- expansion

    /** Plan to have {@code goal.count} items of the goal in total. */
    public Plan plan(Goal goal, Map<String, Integer> inventory, Options o) {
        Expansion e = new Expansion(goal, inventory, o, unitCosts(o), baseUnitCosts(o));
        return e.run();
    }

    private final class Expansion {
        final Goal goal;
        final Options o;
        final Map<String, Double> unit;
        final Map<String, Double> baseUnit;
        /** Batches of each capped source already planned (index into o.sources). */
        TreeMap<Integer, Integer> sourceUsed = new TreeMap<>();
        TreeMap<String, Integer> inv = new TreeMap<>();
        TreeMap<String, Integer> reserved = new TreeMap<>();
        TreeSet<String> stations;
        List<PlanStep> steps = new ArrayList<>();
        double seconds;
        final Deque<String> stack = new ArrayDeque<>();
        final Set<String> risks = new LinkedHashSet<>();
        String deadEnd;
        int expansions;
        final Map<String, Integer> protectedStock = new HashMap<>();
        /** Container stock not yet planned for withdrawal. */
        TreeMap<String, Integer> chest = new TreeMap<>();
        /** Dimension the player is in at this point of the plan. */
        String dim;

        Expansion(Goal goal, Map<String, Integer> inventory, Options o, Map<String, Double> unit, Map<String, Double> baseUnit) {
            this.goal = goal;
            this.o = o;
            this.unit = unit;
            this.baseUnit = baseUnit;
            inventory.forEach((k, v) -> { if (v > 0) inv.put(k, v); });
            stations = new TreeSet<>(o.stations);
            for (String p : o.protectedItems) if (inv.containsKey(p)) protectedStock.put(p, inv.get(p));
            o.containerStock.forEach((k, v) -> { if (v > 0 && !o.protectedItems.contains(k)) chest.put(k, v); });
            dim = o.dimension == null ? CostModel.OVERWORLD : o.dimension;
        }

        Plan run() {
            Map<String, Integer> start = new TreeMap<>(inv);
            int missing = goal.count - goal.have(inv);
            boolean ok = missing <= 0 || obtainGoal(missing);
            if (!ok) {
                String why = expansions >= MAX_EXPANSIONS ? "plan too complex (search limit)"
                        : deadEnd != null ? deadEnd : "no known way to obtain it";
                return new Plan(goal, false, Collections.emptyList(), 0, Collections.emptyList(), why, Collections.emptyMap());
            }
            Map<String, Integer> leftovers = new TreeMap<>();
            inv.forEach((k, v) -> {
                int extra = v - start.getOrDefault(k, 0);
                if (extra > 0) leftovers.put(k, extra);
            });
            leftovers.keySet().removeAll(goal.options);
            return new Plan(goal, true, hoistWithdrawals(steps), seconds, new ArrayList<>(risks), null, leftovers);
        }

        /** All chest withdrawals happen first, merged per item, so the bot makes one trip to the chests. */
        List<PlanStep> hoistWithdrawals(List<PlanStep> in) {
            Map<String, int[]> counts = new LinkedHashMap<>();
            Map<String, Double> secs = new HashMap<>();
            List<PlanStep> rest = new ArrayList<>();
            for (PlanStep s : in) {
                if (s.kind == PlanStep.Kind.WITHDRAW) {
                    counts.computeIfAbsent(s.items.get(0), k -> new int[1])[0] += s.count;
                    secs.merge(s.items.get(0), s.seconds, Double::sum);
                } else {
                    rest.add(s);
                }
            }
            if (counts.isEmpty()) return in;
            List<PlanStep> out = new ArrayList<>();
            counts.forEach((item, c) -> out.add(PlanStep.withdraw(item, c[0], 0, secs.get(item))));
            out.addAll(rest);
            return out;
        }

        // --- state helpers

        int count(String item) {
            return inv.getOrDefault(item, 0);
        }

        /** Usable count: protected items keep their starting stock (new ones may be gathered and used). */
        int avail(String item) {
            return Math.max(0, count(item) - reserved.getOrDefault(item, 0) - protectedStock.getOrDefault(item, 0));
        }

        /** Owned and allowed as a tool (protected tools are never used). */
        boolean hasTool(String tool) {
            return count(tool) > 0 && !o.protectedItems.contains(tool);
        }

        void add(String item, int n) {
            inv.merge(item, n, Integer::sum);
        }

        void consume(String item, int n) {
            int left = count(item) - n;
            if (left < 0) throw new IllegalStateException("over-consumed " + item);
            if (left == 0) inv.remove(item);
            else inv.put(item, left);
        }

        Object[] snapshot() {
            return new Object[]{new TreeMap<>(inv), new TreeMap<>(reserved), new TreeSet<>(stations), steps.size(), seconds, new LinkedHashSet<>(risks), new TreeMap<>(chest), dim, new TreeMap<>(sourceUsed)};
        }

        @SuppressWarnings("unchecked")
        void restore(Object[] s) {
            inv = (TreeMap<String, Integer>) s[0];
            reserved = (TreeMap<String, Integer>) s[1];
            stations = (TreeSet<String>) s[2];
            steps = new ArrayList<>(steps.subList(0, (int) s[3]));
            seconds = (double) s[4];
            risks.clear();
            risks.addAll((Set<String>) s[5]);
            chest = (TreeMap<String, Integer>) s[6];
            dim = (String) s[7];
            sourceUsed = (TreeMap<Integer, Integer>) s[8];
        }

        boolean tripPlanned() {
            for (PlanStep s : steps) if (s.kind == PlanStep.Kind.WITHDRAW) return true;
            return false;
        }

        /** Take up to {@code missing} of {@code item} from the user's containers when that beats the other routes. */
        int withdraw(String item, int missing, int depth, double otherRoutePerItem) {
            int stock = chest.getOrDefault(item, 0);
            if (stock <= 0 || missing <= 0) return 0;
            int take = Math.min(stock, missing);
            double s = withdrawSeconds(take, o.containerDistance.getOrDefault(item, o.defaultContainerDistance), tripPlanned());
            if (otherRoutePerItem != CostModel.INF && s > otherRoutePerItem * take) return 0;
            if (take == stock) chest.remove(item);
            else chest.put(item, stock - take);
            add(item, take);
            seconds += s;
            steps.add(PlanStep.withdraw(item, take, depth, s));
            return take;
        }

        /** Emit a travel step when the next action needs another dimension. */
        void travelTo(String target, int depth) {
            if (target == null || target.equals(dim)) return;
            double s = 60 * (CostModel.END.equals(target) || CostModel.END.equals(dim) ? 3 : 1);
            seconds += s;
            steps.add(PlanStep.travel(target, depth, s));
            risks.add("travels to " + PlanStep.dimensionName(target) + " (needs a known portal)");
            dim = target;
        }

        double unit(String item) {
            return unit.getOrDefault(item, CostModel.INF);
        }

        // --- goal

        boolean obtainGoal(int missing) {
            if (goal.options.size() > 1) {
                for (String opt : goal.options) {
                    if (missing <= 0) return true;
                    missing -= withdraw(opt, missing, 0, unit(opt));
                }
                if (missing <= 0) return true;
            }
            if (goal.options.size() == 1) return obtain(goal.options.get(0), count(goal.options.get(0)) + missing, 0);
            // tag goal: options that are best mined become one combined MINE step ("any log")
            List<String> byCost = new ArrayList<>(goal.options);
            Map<String, Double> est = new HashMap<>();
            for (String opt : byCost) {
                MineRoute m = mineRoute(opt, Collections.singletonList(opt));
                est.put(opt, Math.min(m == null ? CostModel.INF : m.estimate(missing), recipeEstimate(opt, missing)));
            }
            byCost.sort(Comparator.comparingDouble(est::get)); // stable: preference order on ties
            String cheapest = byCost.get(0);
            if (est.get(cheapest) == CostModel.INF) {
                deadEnd = "no known way to obtain any of " + goal.display;
                return false;
            }
            MineRoute combined = mineRoute(cheapest, byCost);
            if (combined != null && combined.estimate(missing) <= recipeEstimate(cheapest, missing) + 1e-9) {
                Object[] snap = snapshot();
                if (combined.apply(missing, 0)) return true;
                restore(snap);
            }
            for (String opt : byCost) {
                if (est.get(opt) == CostModel.INF) break;
                Object[] snap = snapshot();
                if (obtain(opt, count(opt) + missing, 0)) return true;
                restore(snap);
            }
            return false;
        }

        double recipeEstimate(String item, int missing) {
            double best = CostModel.INF;
            for (Recipe r : graph.producers(item)) if (usable(r)) best = Math.min(best, estimate(r, missing));
            return best;
        }

        // --- core recursion

        /** Make sure at least {@code n} of {@code item} are available (unreserved, unprotected). */
        boolean obtain(String item, int n, int depth) {
            if (avail(item) >= n) return true;
            if (++expansions > MAX_EXPANSIONS) return false;
            if (depth > o.maxDepth) {
                deadEnd = "prerequisite chain deeper than " + o.maxDepth + " at " + item;
                return false;
            }
            if (stack.contains(item)) return false; // cycle
            // INF unit cost can still be craftable from what's in hand (e.g. string you carry): check the recipes too
            boolean noRoute = unit(item) == CostModel.INF && recipeEstimate(item, n - avail(item)) == CostModel.INF;
            if (noRoute && chest.getOrDefault(item, 0) < n - avail(item)) {
                if (chest.getOrDefault(item, 0) == 0) {
                    deadEnd = "no known way to obtain " + item;
                    return false;
                }
            }
            stack.push(item);
            try {
                // A24: the user's own chests first when it's cheaper than making/mining it
                int need0 = n - avail(item);
                double perItem = unit(item);
                withdraw(item, need0, depth, perItem == CostModel.INF ? CostModel.INF : perItem);
                if (avail(item) >= n) return true;
                // M3: limited world sources (trade stock, ripe crops, mobs in sight) when they beat mining / crafting
                useCappedSources(item, n, depth);
                if (avail(item) >= n) return true;
                if (noRoute) {
                    deadEnd = "only " + avail(item) + " of " + item + " in your chests and no other way to get it";
                    return false;
                }
                int missing = n - avail(item);
                List<Route> routes = new ArrayList<>();
                MineRoute mine = mineRoute(item, Collections.singletonList(item));
                if (mine != null) routes.add(mine);
                for (Recipe r : graph.producers(item)) {
                    if (usable(r)) routes.add(new RecipeRoute(r));
                }
                for (int si = 0; si < o.sources.size(); si++) {
                    Source src = o.sources.get(si);
                    if (src.item.equals(item) && !src.capped()) routes.add(new SourceRoute(si));
                }
                routes.removeIf(r -> r.estimate(missing) == CostModel.INF);
                routes.sort(Comparator.comparingDouble(r -> r.estimate(missing)));
                int tried = 0;
                for (Route route : routes) {
                    if (tried++ >= MAX_ROUTES) break;
                    Object[] snap = snapshot();
                    if (route.apply(missing, depth) && avail(item) >= n) return true;
                    restore(snap);
                }
                if (deadEnd == null) deadEnd = "every route to " + item + " is blocked";
                return false;
            } finally {
                stack.pop();
            }
        }

        int sourceLeft(int si) {
            Source src = o.sources.get(si);
            return src.capped() ? Math.max(0, src.maxBatches - sourceUsed.getOrDefault(si, 0)) : Integer.MAX_VALUE;
        }

        /** Seconds to take {@code items} from source {@code si}, buying missing inputs at unit cost. */
        double sourceEstimate(int si, int items) {
            Source src = o.sources.get(si);
            int batches = src.batchesFor(items);
            if (batches > sourceLeft(si)) return CostModel.INF;
            double c = src.setupSeconds + batches * src.batchSeconds;
            for (Map.Entry<String, Integer> e : src.inputs.entrySet()) {
                int need = e.getValue() * batches;
                int buy = Math.max(0, need - avail(e.getKey()));
                if (buy > 0) c += costOf(e.getKey(), buy);
            }
            if (src.tool != null && !hasTool(src.tool)) c += costOf(src.tool, 1);
            return c;
        }

        /** Seconds to get {@code k} more of an item: unit cost, or (if INF) a recipe from what's in hand. */
        double costOf(String item, int k) {
            double u = unit(item);
            return u != CostModel.INF ? u * k : recipeEstimate(item, k);
        }

        /** Plan {@code items} from source {@code si} (inputs and tool obtained recursively). */
        boolean applySource(int si, int items, int depth) {
            Source src = o.sources.get(si);
            int batches = src.batchesFor(items);
            if (batches <= 0 || batches > sourceLeft(si)) return false;
            if (src.tool != null) {
                if (o.protectedItems.contains(src.tool) && count(src.tool) <= 0) return false;
                if (!hasTool(src.tool) && !obtain(src.tool, 1, depth + 1)) return false;
                if (reserved.getOrDefault(src.tool, 0) < 1) reserved.merge(src.tool, 1, Integer::sum);
            }
            Map<String, Integer> used = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : src.inputs.entrySet()) {
                int need = e.getValue() * batches;
                if (!obtain(e.getKey(), need, depth + 1)) return false;
                consume(e.getKey(), need);
                used.put(e.getKey(), need);
            }
            int produced = Math.max(items, (int) Math.floor(batches * src.perBatch + 1e-9));
            add(src.item, produced);
            if (src.capped()) sourceUsed.merge(si, batches, Integer::sum);
            double s = src.setupSeconds + batches * src.batchSeconds;
            seconds += s;
            steps.add(PlanStep.gather(src, produced, batches, used, depth, s));
            if (src.kind == Source.Kind.HUNT) risks.add("fights " + src.ref.replace("minecraft:", "") + " (attacks via Meteor)");
            if (src.kind == Source.Kind.TREE && !src.inputs.containsKey("minecraft:bone_meal")) risks.add("waits for a sapling to grow");
            return true;
        }

        /** A28/A33/A39: take what limited sources can give when cheaper per item than mining / crafting. */
        void useCappedSources(String item, int n, int depth) {
            List<Integer> idx = new ArrayList<>();
            for (int si = 0; si < o.sources.size(); si++) {
                Source src = o.sources.get(si);
                if (src.item.equals(item) && src.capped() && sourceLeft(si) > 0) idx.add(si);
            }
            if (idx.isEmpty()) return;
            idx.sort(Comparator.comparingDouble((Integer si) -> sourceEstimate(si, Math.min(Math.max(1, n - avail(item)),
                    (int) Math.floor(sourceLeft(si) * o.sources.get(si).perBatch + 1e-9))) / Math.max(1, Math.min(n - avail(item),
                    (int) Math.floor(sourceLeft(si) * o.sources.get(si).perBatch + 1e-9)))).thenComparing(si -> si));
            double base = baseUnit.getOrDefault(item, CostModel.INF);
            for (int si : idx) {
                int missing = n - avail(item);
                if (missing <= 0) return;
                Source src = o.sources.get(si);
                int take = Math.min(missing, (int) Math.floor(sourceLeft(si) * src.perBatch + 1e-9));
                if (take <= 0) continue;
                double est = sourceEstimate(si, take);
                if (est == CostModel.INF || (base != CostModel.INF && est > base * take)) continue;
                Object[] snap = snapshot();
                if (!applySource(si, take, depth)) restore(snap);
            }
        }

        final class SourceRoute implements Route {
            final int si;

            SourceRoute(int si) {
                this.si = si;
            }

            @Override
            public double estimate(int missing) {
                return sourceEstimate(si, missing);
            }

            @Override
            public boolean apply(int missing, int depth) {
                return applySource(si, missing, depth);
            }
        }

        boolean usable(Recipe r) {
            if (!o.types.contains(r.type) || r.componentResult) return false;
            String st = r.station();
            return st == null || stations.contains(st) || o.allowPlaceStations;
        }

        double estimate(Recipe r, int missing) {
            int batches = ceilDiv(missing, r.outputCount);
            double c = batches * costs.craftSecondsPerBatch;
            if (r.type.cooking) c += batches * (costs.cookSeconds(r.cookTime) + 1.5);
            for (Ingredient i : r.ingredients) {
                int need = i.count * batches;
                int have = 0;
                double best = CostModel.INF;
                for (String opt : i.options) {
                    have += avail(opt);
                    best = Math.min(best, unit(opt));
                }
                int buy = Math.max(0, need - have);
                if (buy > 0) c += buy * best;
            }
            String st = r.station();
            if (st != null && !stations.contains(st)) c += costs.placeStationSeconds + (avail(st) > 0 ? 0 : unit(st));
            else if (st != null) c += costs.stationUseSeconds;
            return c;
        }

        interface Route {
            double estimate(int missing);

            boolean apply(int missing, int depth);
        }

        MineRoute mineRoute(String primary, List<String> items) {
            List<RecipeGraph.MineSource> sources = new ArrayList<>();
            for (String item : items) {
                for (RecipeGraph.MineSource s : graph.mineSources(item)) {
                    if (costs.isNatural(s.block) && mineCost(s.block, o) != CostModel.INF) sources.add(s);
                }
            }
            if (sources.isEmpty()) return null;
            sources.sort(Comparator.comparingDouble((RecipeGraph.MineSource s) -> mineCost(s.block, o) / s.expected)
                    .thenComparing(s -> s.block));
            RecipeGraph.MineSource best = sources.get(0);
            double bestCost = mineCost(best.block, o) / best.expected;
            // the tool for the best block decides which of the others are also fine (same tier, similar cost)
            List<String> tierTools = tools.acceptableTools(best.block);
            String tool = null;
            if (!tierTools.isEmpty()) {
                for (String t : tierTools) if (hasTool(t)) { tool = t; break; }
                if (tool == null) {
                    tool = tierTools.stream().filter(t -> !o.protectedItems.contains(t))
                            .min(Comparator.comparingDouble(this::unit)).orElse(null);
                    if (tool == null || unit(tool) == CostModel.INF) return null;
                }
            }
            List<String> blocks = new ArrayList<>();
            double expected = 0;
            for (RecipeGraph.MineSource s : sources) {
                double c = mineCost(s.block, o) / s.expected;
                if (!requiredDimension(s.block, o.dimension).equals(requiredDimension(best.block, o.dimension))) continue;
                if (c > bestCost * 2.5 || blocks.contains(s.block)) continue;
                if (tool == null ? tools.requiresTool(s.block) : tools.requiresTool(s.block) && !tools.canHarvest(tool, s.block)) continue;
                blocks.add(s.block);
                expected = blocks.size() == 1 ? s.expected : Math.min(expected, s.expected);
            }
            return new MineRoute(primary, items, blocks, expected, tool, bestCost);
        }

        final class MineRoute implements Route {
            final String primary;
            final List<String> items, blocks;
            final double expected, perItem;
            final String tool;

            MineRoute(String primary, List<String> items, List<String> blocks, double expected, String tool, double perItem) {
                this.primary = primary;
                this.items = items;
                this.blocks = blocks;
                this.expected = expected;
                this.tool = tool;
                this.perItem = perItem;
            }

            @Override
            public double estimate(int missing) {
                double c = perItem * missing;
                if (tool != null && !hasTool(tool)) c += unit(tool);
                return c;
            }

            @Override
            public boolean apply(int missing, int depth) {
                if (tool != null) {
                    if (!hasTool(tool) && !obtain(tool, 1, depth + 1)) return false;
                    if (reserved.getOrDefault(tool, 0) < 1) reserved.merge(tool, 1, Integer::sum);
                }
                int blockCount = (int) Math.ceil(missing / expected - 1e-9);
                travelTo(requiredDimension(blocks.get(0), o.dimension), depth);
                double s = perItem * missing;
                seconds += s;
                add(primary, missing);
                steps.add(PlanStep.mine(new ArrayList<>(items), missing, blocks, blockCount, tool, depth, s));
                String b = blocks.get(0);
                String only = costs.exclusiveDimension(b);
                if (only != null && !only.equals(o.dimension)) {
                    risks.add("needs " + b.substring(b.indexOf(':') + 1) + " from " + PlanStep.dimensionName(only));
                }
                if (b.contains("diamond") || b.contains("redstone") || b.contains("lapis") || b.contains("ancient_debris") || b.contains("emerald") || (b.contains("gold") && !b.contains("nether"))) {
                    risks.add("deep mining for " + b.substring(b.indexOf(':') + 1) + " (lava, caves, mobs)");
                }
                return true;
            }
        }

        final class RecipeRoute implements Route {
            final Recipe r;

            RecipeRoute(Recipe r) {
                this.r = r;
            }

            @Override
            public double estimate(int missing) {
                return Planner.Expansion.this.estimate(r, missing);
            }

            @Override
            public boolean apply(int missing, int depth) {
                int batches = ceilDiv(missing, r.outputCount);
                String st = r.station();
                if (st != null && !stations.contains(st)) {
                    if (!o.allowPlaceStations) return false;
                    if (!obtain(st, 1, depth + 1)) return false;
                    consume(st, 1);
                    stations.add(st);
                    seconds += costs.placeStationSeconds;
                    steps.add(PlanStep.place(st, depth + 1, costs.placeStationSeconds));
                }
                Map<String, Integer> inputs = new LinkedHashMap<>();
                for (Ingredient ing : r.ingredients) {
                    int need = ing.count * batches;
                    // use what we have first, biggest stacks first (keeps the inventory tidy), tag order on ties
                    List<String> opts = new ArrayList<>(ing.options);
                    opts.sort(Comparator.comparingInt(Expansion.this::avail).reversed());
                    for (String opt : opts) {
                        if (need == 0) break;
                        int take = Math.min(avail(opt), need);
                        if (take > 0) {
                            consume(opt, take);
                            inputs.merge(opt, take, Integer::sum);
                            need -= take;
                        }
                    }
                    if (need > 0) {
                        List<String> buy = new ArrayList<>(ing.options);
                        buy.removeIf(x -> unit(x) == CostModel.INF);
                        buy.sort(Comparator.comparingDouble(Expansion.this::unit));
                        boolean got = false;
                        for (String opt : buy) {
                            Object[] snap = snapshot();
                            if (obtain(opt, need, depth + 1)) {
                                consume(opt, need);
                                inputs.merge(opt, need, Integer::sum);
                                got = true;
                                break;
                            }
                            restore(snap);
                            if (buy.indexOf(opt) >= 1) break; // try at most two alternatives per ingredient
                        }
                        if (!got) return false;
                    }
                }
                String fuelItem = null;
                int fuelCount = 0;
                if (r.type.cooking) {
                    int ticks = batches * (r.cookTime <= 0 ? 200 : r.cookTime);
                    String[] pick = pickFuel(ticks, depth);
                    if (pick == null) return false;
                    fuelItem = pick[0];
                    fuelCount = Integer.parseInt(pick[1]);
                }
                add(r.output, batches * r.outputCount);
                double s = batches * costs.craftSecondsPerBatch + (st != null ? costs.stationUseSeconds : 0);
                if (r.type.cooking) s += batches * costs.cookSeconds(r.cookTime);
                seconds += s;
                steps.add(PlanStep.craft(r, batches, inputs, fuelItem, fuelCount, depth, s));
                return true;
            }
        }

        /** Choose fuel for {@code ticks} of cooking: owned fuel with least waste first, else the cheapest to get. */
        String[] pickFuel(int ticks, int depth) {
            String best = null;
            int bestUnits = 0;
            int bestWaste = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : new ArrayList<>(inv.entrySet())) {
                String f = e.getKey();
                int burn = fuel.burnTicks(f);
                if (burn <= 0 || stack.contains(f) || goal.options.contains(f)) continue;
                int units = ceilDiv(ticks, burn);
                if (avail(f) < units) continue;
                int waste = units * burn - ticks;
                if (waste < bestWaste || (waste == bestWaste && f.compareTo(best) < 0)) {
                    best = f;
                    bestUnits = units;
                    bestWaste = waste;
                }
            }
            if (best != null) {
                consume(best, bestUnits);
                return new String[]{best, String.valueOf(bestUnits)};
            }
            List<String> cands = new ArrayList<>(fuelCandidates());
            cands.removeIf(f -> stack.contains(f) || goal.options.contains(f) || unit(f) == CostModel.INF);
            cands.sort(Comparator.comparingDouble((String f) -> unit(f) * ceilDiv(ticks, fuel.burnTicks(f))).thenComparing(f -> f));
            for (int i = 0; i < Math.min(3, cands.size()); i++) {
                String f = cands.get(i);
                int units = ceilDiv(ticks, fuel.burnTicks(f));
                Object[] snap = snapshot();
                if (obtain(f, units, depth + 1)) {
                    consume(f, units);
                    return new String[]{f, String.valueOf(units)};
                }
                restore(snap);
            }
            deadEnd = "no fuel available for smelting";
            return null;
        }
    }

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
