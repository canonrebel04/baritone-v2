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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IGetItemProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.getitem.GameData;
import baritone.process.getitem.GetItemKnowledge;
import baritone.process.getitem.inventory.InventoryPlanner;
import baritone.process.getitem.knowledge.*;
import baritone.process.getitem.plan.*;
import baritone.process.getitem.safety.BudgetGuard;
import baritone.process.getitem.safety.SafetyMonitor;
import baritone.process.getitem.tasks.*;
import baritone.process.getitem.sources.SourceCatalog;
import baritone.process.getitem.sources.Yields;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import baritone.process.getitem.data.RecipeType;
import baritone.process.getitem.advanced.*;
import baritone.process.getitem.inventory.HotbarLayout;
import baritone.process.getitem.sources.Compost;
import java.util.*;

/**
 * Get Item (plan Part 4, milestones M1+M2): plans how to obtain an item and runs the plan one step at a time,
 * re-planning from the real inventory after every step (A10). M2 adds world knowledge (A11-A20): your own chests
 * are used as stock with a protect list (A24), dropped items are picked up, unknown blocks are explored for,
 * Nether/End trips go through known portals; plus inventory management (A43), tool wear (A44), eating and mob
 * safety (A45/A46), yielding to manual commands (A47), budgets (A49), progress and resume (A50).
 * Priority 0, just above MineProcess.
 */
public final class GetItemProcess extends BaritoneProcessHelper implements IGetItemProcess {
    private static final int MAX_STEP_FAILURES = 3;
    private static final int MAX_PEEKS = 6;

    private final GameData data;
    private final GetItemKnowledge knowledge;
    private Goal goal;
    private StepTask task;
    private Plan lastPlan;
    private String lastFailure;
    private int stepFailures;
    private int stepsDone;
    private int idleTicks;
    private final Set<BlockPos> stationBlacklist = new HashSet<>();
    private final Map<String, Integer> blockFailures = new HashMap<>();
    private final Map<String, Integer> exploreCount = new HashMap<>();
    private Set<String> seenCache;
    private int seenAge;
    // M2 state
    private boolean userPaused;
    private String pauseReason;
    private boolean pausedForHostiles;
    private boolean peeked;
    private final Deque<BlockPos> peekQueue = new ArrayDeque<>();
    private final Set<Integer> skippedDrops = new HashSet<>();
    private long startMillis;
    private BlockPos startPos;
    private String startDim;
    private boolean returning;
    private int resumeDelay = -1;
    private String resumeGoal;
    private int resumeAmount;
    private List<Block> savedDisallow;
    private Boolean savedAvoidance;
    private int stashCooldown;
    /** M3: world sources that failed this run ("KIND|ref"), not planned again. */
    private final Set<String> sourceBlacklist = new HashSet<>();
    /** A41: harvest by-products to put away after a gather step. */
    private final Set<String> storeQueue = new HashSet<>();
    /** A30: Meteor registers a kill handler; Baritone never attacks itself. */
    private volatile boolean killHandler;
    /** M4 (A8/A25/A27): enchant / potion / rename goal, or null for plain item goals. */
    private ItemSpec spec;
    private String specText;
    /** A27: the brewing stand used last still holds fuel. */
    private boolean standFuel;
    /** A25: grindstone rerolls spent on this goal. */
    private int rerollsUsed;
    private int xpTries;
    private int hotbarCooldown;
    private int xpCooldown;
    private int packCooldown;

    public GetItemProcess(Baritone baritone) {
        super(baritone);
        this.data = new GameData(baritone.getPlayerContext());
        this.knowledge = new GetItemKnowledge(baritone);
        baritone.getGameEventHandler().registerEventListener(knowledge);
        StationAccess.knownStations = (block, center) -> {
            List<BlockPos> out = new ArrayList<>();
            for (StationRegistry.Station s : knowledge.base().stations.near(block, data.dimension(), center.getX(), center.getY(), center.getZ(),
                    Baritone.settings().getItemContainerRadius.value)) {
                out.add(new BlockPos(s.x, s.y, s.z));
            }
            return out;
        };
    }

    public GameData data() {
        return data;
    }

    public GetItemKnowledge knowledgeCapture() {
        return knowledge;
    }

    // ---------------------------------------------------------------- API

    @Override
    public synchronized String get(String item, int amount) {
        if (ctx.player() == null) return "not in game";
        onLostControl();
        if (ItemSpec.looksAdvanced(item)) return getAdvanced(item, amount);
        Goal g = parse(item, amount);
        if (g == null) return "unknown item or tag: " + item;
        Plan p = makePlan(g);
        lastPlan = p;
        if (!p.success) {
            lastFailure = p.failure;
            return p.failure;
        }
        goal = g;
        stepsDone = 0;
        stepFailures = 0;
        lastFailure = null;
        startMillis = System.currentTimeMillis();
        startPos = ctx.playerFeet();
        startDim = data.dimension();
        applyRunSettings();
        knowledge.setPending(g.isTag() ? g.display : g.options.get(0), g.count);
        logDirect("Get Item: " + g + " (" + p.steps.size() + " steps, ETA ~" + Plan.formatEta(p.seconds) + ")");
        return null;
    }

    /** M4: start an enchant / potion / rename goal; the steps are decided from the inventory as it goes. */
    private String getAdvanced(String text, int amount) {
        ItemSpec sp = ItemSpec.parse(text);
        if (sp == null) return "can't read \"" + text + "\" (try potion:swiftness, splash_potion:strong_healing, diamond_pickaxe@fortune:3,unbreaking:3, ...@name:Text)";
        Item it = GameData.item(sp.item);
        if (it == null || it == net.minecraft.world.item.Items.AIR) return "unknown item: " + sp.item;
        if (sp.isPotion()) {
            if (!Baritone.settings().getItemBrewing.value) return "brewing is off (getItemBrewing)";
            if (BrewPlanner.path(sp.item, sp.potion) == null) return "don't know how to brew " + sp.display();
        }
        Next n = advancedNext(sp, amount);
        if (n.kind == Next.Kind.FAIL) {
            lastFailure = n.why;
            return n.why;
        }
        spec = sp;
        specText = text.trim();
        goal = new Goal(sp.display(), Collections.singletonList(sp.item), amount);
        stepsDone = 0;
        stepFailures = 0;
        lastFailure = null;
        lastPlan = null;
        startMillis = System.currentTimeMillis();
        startPos = ctx.playerFeet();
        startDim = data.dimension();
        applyRunSettings();
        knowledge.setPending(specText, amount);
        logDirect("Get Item: " + amount + "x " + sp.display() + " (next: " + n + ")");
        return null;
    }

    private Next advancedNext(ItemSpec sp, int amount) {
        List<StackDesc> inv = data.stacks();
        if (sp.isPotion()) return BrewPlanner.next(sp, amount, inv, standFuel);
        EnchantPlanner.Options o = new EnchantPlanner.Options();
        o.anvil = Baritone.settings().getItemUseAnvil.value;
        o.table = Baritone.settings().getItemUseEnchantingTable.value;
        o.maxAnvilCost = Baritone.settings().getItemMaxAnvilCost.value;
        o.rerolls = Baritone.settings().getItemEnchantRerolls.value;
        o.rerollsUsed = rerollsUsed;
        o.levels = ctx.player().getAbilities().instabuild ? 1000 : ctx.player().experienceLevel;
        o.collectXp = Baritone.settings().getItemCollectXp.value && xpTries < 3;
        return EnchantPlanner.next(sp, amount, inv, o);
    }

    private int goalHave() {
        if (goal == null) return 0;
        return spec != null ? spec.have(data.stacks()) : goal.have(data.inventory());
    }

    /** M4: the task for the next advanced-goal action, or a stop command (as a failure string). */
    private Object advancedTask() {
        Next n = advancedNext(spec, goal.count);
        logDebug("Get Item advanced: " + n);
        switch (n.kind) {
            case DONE:
                return null;
            case FAIL:
                return n.why;
            case NEED: {
                int have = data.inventory().getOrDefault(n.item, 0);
                Plan p = makePlan(Goal.item(n.item, have + n.count));
                lastPlan = p;
                if (!p.success) return "no plan for " + n.item.replace("minecraft:", "") + " (" + n.why + "): " + p.failure;
                PlanStep step = p.first();
                if (step == null) return "plan for " + n.item.replace("minecraft:", "") + " is empty";
                return create(step, p);
            }
            case FILL:
                return new FillBottleTask(baritone, data, n.count, stationBlacklist);
            case BREW:
                return new BrewTask(baritone, data, n.from, n.item, n.count, stationBlacklist);
            case ANVIL:
                return new AnvilTask(baritone, data, spec.item, n.slot, n.other, null, stationBlacklist);
            case RENAME:
                return new AnvilTask(baritone, data, spec.item, n.slot, -1, n.name, stationBlacklist);
            case ENCHANT:
                return new EnchantTask(baritone, data, spec.item, n.slot, spec.enchants,
                        rerollsUsed < Baritone.settings().getItemEnchantRerolls.value, stationBlacklist);
            case GRINDSTONE:
                if (!spec.clean) rerollsUsed++;
                return new GrindstoneTask(baritone, data, spec.item, n.slot, stationBlacklist);
            case XP:
                xpTries++;
                if (XpOrbTask.nearest(baritone) == null) return "needs " + n.count + " more levels and there are no experience orbs nearby";
                return new XpOrbTask(baritone, data);
            default:
                return "unexpected " + n.kind;
        }
    }

    /** Known items/tags, plus any registry item (it may still come from a chest). */
    private Goal parse(String item, int amount) {
        Goal g = Goal.parse(item, amount, data.planner().graph, Collections.emptyList());
        if (g != null || item.startsWith("#")) return g;
        Item it = GameData.item(Goal.withNamespace(item.trim().toLowerCase(Locale.ROOT)));
        return it == null || it == net.minecraft.world.item.Items.AIR ? null : Goal.item(GameData.id(it), amount);
    }

    @Override
    public List<String> plan(String item, int amount) {
        if (ctx.player() == null) return Collections.singletonList("not in game");
        if (ItemSpec.looksAdvanced(item)) {
            ItemSpec sp = ItemSpec.parse(item);
            if (sp == null) return Collections.singletonList("can't read \"" + item + "\"");
            List<String> out = new ArrayList<>();
            if (sp.isPotion()) out.addAll(BrewPlanner.describe(sp, amount));
            out.add("Next: " + advancedNext(sp, amount));
            return out;
        }
        Goal g = parse(item, amount);
        if (g == null) return Collections.singletonList("unknown item or tag: " + item);
        Plan p = makePlan(g);
        lastPlan = p;
        List<String> out = new ArrayList<>(p.explain());
        Set<String> blocks = new LinkedHashSet<>();
        for (PlanStep s : p.steps) if (s.kind == PlanStep.Kind.MINE) blocks.addAll(s.blocks.subList(0, 1));
        for (String h : BiomeHints.describe(blocks)) out.add("Where: " + h);
        return out;
    }

    @Override
    public synchronized String status() {
        if (goal == null) return lastFailure == null ? "idle" : "idle (last: " + lastFailure + ")";
        int have = goalHave();
        String why = pauseReason();
        return "getting " + goal.display + " " + have + "/" + goal.count + ", step " + (stepsDone + 1)
                + (why != null ? " [" + why + "]" : task != null ? ": " + task.status() : "");
    }

    @Override
    public synchronized String progressLine() {
        if (goal == null) return null;
        int have = goalHave();
        StringBuilder sb = new StringBuilder(goal.display.replace("minecraft:", "")).append(' ').append(have).append('/').append(goal.count);
        if (lastPlan != null && lastPlan.success) {
            sb.append(" · step ").append(stepsDone + 1).append('/').append(stepsDone + Math.max(1, lastPlan.steps.size()));
        }
        String why = pauseReason();
        if (why != null) sb.append(" · ").append(why);
        else if (task != null) sb.append(" · ").append(task.status());
        if (lastPlan != null && lastPlan.success) sb.append(" · ETA ").append(Plan.formatEta(lastPlan.seconds));
        return sb.toString();
    }

    @Override
    public List<String> why() {
        List<String> out = new ArrayList<>();
        if (pauseReason() != null) out.add("Paused: " + pauseReason());
        if (lastFailure != null) out.add("Last failure: " + lastFailure);
        if (lastPlan != null) out.addAll(lastPlan.explain());
        if (out.isEmpty()) out.add("Nothing planned yet");
        return out;
    }

    @Override
    public synchronized void cancel() {
        onLostControl();
    }

    @Override
    public synchronized void pause() {
        userPaused = true;
        if (task != null) {
            task.stop();
            task = null;
        }
    }

    @Override
    public synchronized void resume() {
        userPaused = false;
    }

    @Override
    public String pauseReason() {
        if (goal == null) return null;
        if (userPaused) return "paused (#get resume)";
        return pauseReason;
    }

    @Override
    public boolean isPausedForHostiles() {
        return goal != null && pausedForHostiles;
    }

    @Override
    public List<String> where(String item) {
        List<String> out = new ArrayList<>();
        if (ctx.player() == null) return out;
        String id = Goal.withNamespace(item.trim().toLowerCase(Locale.ROOT));
        ProtectPolicy policy = knowledge.policy();
        BlockPos me = ctx.playerFeet();
        String dim = data.dimension();
        List<ContainerIndex.Container> all = knowledge.base().containers.all();
        all.sort(Comparator.comparingDouble(c -> c.dist2(me.getX(), me.getY(), me.getZ())));
        for (ContainerIndex.Container c : all) {
            Integer n = c.items.get(id);
            if (n == null) continue;
            String where = Objects.equals(c.dim, dim) ? Math.round(Math.sqrt(c.dist2(me.getX(), me.getY(), me.getZ()))) + "m" : c.dim;
            out.add("Chest: " + n + "x in " + c.type + (c.name != null ? " \"" + c.name + "\"" : "") + " at " + c.x + "," + c.y + "," + c.z
                    + " (" + where + ")" + (policy.containerProtected(c) || policy.itemProtected(id) ? " [protected]" : ""));
            if (out.size() >= 10) break;
        }
        for (String s : knowledge.base().villagers.sellers(id)) out.add("Trade: " + s);
        BiomeHints.Hint h = BiomeHints.of(id.replace("_ingot", "_ore").replace("raw_", ""));
        if (h == null) h = BiomeHints.of(id + "_ore");
        if (h != null) out.add("Generates: " + h.describe());
        if (out.isEmpty()) out.add("Nothing known about " + id.replace("minecraft:", "") + " yet (open chests to index them)");
        return out;
    }

    @Override
    public List<String> knowledge() {
        KnowledgeBase kb = knowledge.base();
        List<String> out = new ArrayList<>();
        ProtectPolicy policy = knowledge.policy();
        int prot = 0;
        for (ContainerIndex.Container c : kb.containers.all()) if (policy.containerProtected(c)) prot++;
        out.add("Containers: " + kb.containers.size() + " indexed (" + prot + " protected)");
        out.add("Stations: " + kb.stations.size() + " known (" + kb.stations.all().stream().filter(s -> s.placedByUs).count() + " placed by Get Item)");
        out.add("Villagers: " + kb.villagers.all().size() + " with remembered trades");
        out.add("Farms: " + kb.farms.all().size() + (kb.farms.all().isEmpty() ? "" : " " + kb.farms.types(data.dimension())));
        out.add("Hazards: " + kb.hazards.all().size());
        if (kb.pendingGoal != null) out.add("Pending goal (resume): " + kb.pendingAmount + "x " + kb.pendingGoal);
        return out;
    }

    // ---------------------------------------------------------------- knowledge hooks

    /** Called when a world's knowledge file is loaded (join / reconnect). */
    public synchronized void onKnowledgeLoaded(KnowledgeBase kb) {
        if (goal == null && kb.pendingGoal != null && Baritone.settings().getItemResume.value) {
            resumeGoal = kb.pendingGoal;
            resumeAmount = kb.pendingAmount;
            resumeDelay = 100; // let the world and inventory load
        }
    }

    /** Called when leaving the world: stop without forgetting the pending goal. */
    public synchronized void onDisconnect() {
        if (goal != null || resumeDelay >= 0) {
            String g = goal != null ? (goal.isTag() ? goal.display : goal.options.get(0)) : resumeGoal;
            int n = goal != null ? goal.count : resumeAmount;
            reset();
            knowledge.setPending(g, n);
        }
    }

    // ---------------------------------------------------------------- planning

    private ProtectPolicy policy() {
        return knowledge.policy();
    }

    private Planner.Options options() {
        Planner.Options o = new Planner.Options();
        o.protectedItems = data.protectedItems();
        o.dimension = data.dimension();
        o.maxDepth = Baritone.settings().getItemMaxPlanDepth.value;
        o.allowPlaceStations = Baritone.settings().getItemPlaceStations.value;
        Set<RecipeType> types = EnumSet.copyOf(Planner.M4_TYPES);
        if (!Baritone.settings().getItemUseStonecutter.value) types.remove(RecipeType.STONECUTTING);
        if (!Baritone.settings().getItemUseSmithing.value) types.remove(RecipeType.SMITHING);
        if (!Baritone.settings().getItemUseCampfire.value) types.remove(RecipeType.CAMPFIRE_COOKING);
        o.types = types;
        Set<String> st = new TreeSet<>(data.nearbyStations(stationBlacklist));
        if (ctx.player() != null) {
            BlockPos me = ctx.playerFeet();
            int r = Math.min(Baritone.settings().getItemContainerRadius.value, 48);
            for (StationRegistry.Station s : knowledge.base().stations.near(null, o.dimension, me.getX(), me.getY(), me.getZ(), r)) {
                if (!stationBlacklist.contains(new BlockPos(s.x, s.y, s.z))) st.add(GameData.stationOf(s.block));
            }
            if (Baritone.settings().getItemUseContainers.value) {
                ProtectPolicy policy = policy();
                int radius = Baritone.settings().getItemContainerRadius.value;
                o.containerStock = knowledge.base().containers.stock(policy, o.dimension, me.getX(), me.getY(), me.getZ(), radius);
                o.containerDistance = knowledge.base().containers.nearest(policy, o.dimension, me.getX(), me.getY(), me.getZ(), radius);
            }
        }
        o.stations = st;
        o.allowNether = Baritone.settings().getItemAllowNether.value;
        o.allowEnd = Baritone.settings().getItemAllowEnd.value;
        Set<String> pb = new HashSet<>();
        for (Block b : Baritone.settings().getItemProtectBlocks.value) pb.add(GameData.id(b));
        o.protectedBlocks = pb;
        o.sources = sources(o.dimension);
        if (Baritone.settings().getItemPreferNearbyBlocks.value) {
            if (seenCache == null || seenAge++ > 20) {
                seenCache = data.seenNaturalBlocks(data.planner().costs, 4);
                seenAge = 0;
            }
            Set<String> seen = new HashSet<>(seenCache);
            // blocks MineProcess repeatedly failed to reach count as unseen
            blockFailures.forEach((b, n) -> { if (n >= 2) seen.remove(b); });
            o.seenBlocks = seen;
        }
        return o;
    }

    /** M3: world sources from what the knowledge has seen (trades, ripe farms, water, mobs, sheep, cows, mob farms). */
    private List<Source> sources(String dim) {
        if (ctx.player() == null) return Collections.emptyList();
        BlockPos me = ctx.playerFeet();
        int r = Baritone.settings().getItemSourceRadius.value;
        List<Source> out = new ArrayList<>();
        Census census = knowledge.census();
        if (Baritone.settings().getItemUseTrades.value) {
            out.addAll(SourceCatalog.trades(knowledge.base().villagers.all(), dim, me.getX(), me.getY(), me.getZ(), r));
        }
        if (Baritone.settings().getItemUseFarms.value) {
            out.addAll(SourceCatalog.farms(knowledge.base().farms.all(), dim, me.getX(), me.getY(), me.getZ(), r));
        }
        if (Baritone.settings().getItemUseFishing.value) out.addAll(SourceCatalog.fishing(census.waterDist));
        if (Baritone.settings().getItemUseHunting.value && killHandler) {
            out.addAll(SourceCatalog.hunts(census.mobs, Baritone.settings().getItemHuntHostiles.value));
        }
        if (Baritone.settings().getItemUseAnimals.value) out.addAll(SourceCatalog.animals(census.sheep, census.cows));
        if (Baritone.settings().getItemUseTrees.value) out.addAll(SourceCatalog.trees(Baritone.settings().getItemTreeBoneMeal.value));
        if (Baritone.settings().getItemCompost.value) {
            List<BlockPos> composters = data.findBlocks(Collections.singleton(Blocks.COMPOSTER), Baritone.settings().getItemStationSearchRadius.value);
            if (!composters.isEmpty()) {
                BlockPos c = composters.get(0);
                Set<String> keep = new HashSet<>(data.protectedItems());
                if (goal != null) keep.addAll(goal.options);
                out.addAll(Compost.sources(data.inventory(), keep, 3 + Math.sqrt(c.distSqr(me)) / 4.3, c.getX() + "," + c.getY() + "," + c.getZ()));
            }
        }
        out.addAll(SourceCatalog.mobFarms(Baritone.settings().getItemMobFarms.value, me.getX(), me.getY(), me.getZ()));
        out.removeIf(x -> sourceBlacklist.contains(x.kind + "|" + x.ref) || data.protectedItems().contains(x.item));
        return out;
    }

    @Override
    public void setKillHandler(boolean available) {
        killHandler = available;
    }

    public boolean hasKillHandler() {
        return killHandler;
    }

    @Override
    public int huntTargetId() {
        return task instanceof AnimalTask at ? at.huntTarget() : -1;
    }

    private Plan makePlan(Goal g) {
        return data.cache().plan(g, data.planningInventory(Baritone.settings().getItemMinToolDurability.value), options());
    }

    // ---------------------------------------------------------------- run settings (A20/A24)

    private void applyRunSettings() {
        restoreRunSettings();
        savedDisallow = new ArrayList<>(Baritone.settings().blocksToDisallowBreaking.value);
        List<Block> merged = new ArrayList<>(savedDisallow);
        for (Block b : Baritone.settings().getItemProtectBlocks.value) if (!merged.contains(b)) merged.add(b);
        Baritone.settings().blocksToDisallowBreaking.value = merged;
        if (Baritone.settings().getItemAvoidHazards.value) {
            savedAvoidance = Baritone.settings().avoidance.value;
            Baritone.settings().avoidance.value = true;
        }
    }

    private void restoreRunSettings() {
        if (savedDisallow != null) {
            Baritone.settings().blocksToDisallowBreaking.value = savedDisallow;
            savedDisallow = null;
        }
        if (savedAvoidance != null) {
            Baritone.settings().avoidance.value = savedAvoidance;
            savedAvoidance = null;
        }
    }

    // ---------------------------------------------------------------- execution

    @Override
    public boolean isActive() {
        return goal != null || resumeDelay >= 0;
    }

    /** A47: a Baritone process the user started (not one Get Item drives) that is active. */
    private String manualProcess() {
        if (!Baritone.settings().getItemYieldToManual.value) return null;
        boolean ownMine = task instanceof MineTask;
        if (baritone.getCustomGoalProcess().isActive()) return "goto";
        if (baritone.getExploreProcess().isActive()) return "explore";
        if (baritone.getFollowProcess().isActive()) return "follow";
        if (baritone.getFarmProcess().isActive()) return "farm";
        if (baritone.getBuilderProcess().isActive()) return "build";
        if (baritone.getGetToBlockProcess().isActive()) return "goto block";
        if (baritone.getMineProcess().isActive() && !ownMine) return "mine";
        if (baritone.getElytraProcess().isActive()) return "elytra";
        if (baritone.frontierExplorerProcess.isActive()) return "explore";
        if (baritone.portalRouterProcess.isActive()) return "portal route";
        return null;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (resumeDelay >= 0 && goal == null) {
            if (ctx.player() == null) return new PathingCommand(null, PathingCommandType.DEFER);
            if (resumeDelay-- > 0) return new PathingCommand(null, PathingCommandType.DEFER);
            resumeDelay = -1;
            String err = get(resumeGoal, resumeAmount);
            logDirect("Get Item: resuming " + resumeAmount + "x " + resumeGoal + (err == null ? "" : " failed: " + err));
            if (err != null) knowledge.setPending(null, 0);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (goal == null || ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (!ctx.player().isAlive()) {
            pauseReason = "dead, waiting for respawn";
            dropTask();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (userPaused) return new PathingCommand(null, PathingCommandType.DEFER);
        String manual = manualProcess();
        if (manual != null) {
            pauseReason = "waiting for #" + manual;
            if (task != null && !(task instanceof MineTask)) dropTask();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        // A49 budgets
        BudgetGuard.Limits limits = new BudgetGuard.Limits();
        limits.maxMinutes = Baritone.settings().getItemMaxMinutes.value;
        limits.maxDistance = Baritone.settings().getItemMaxDistance.value;
        limits.minY = Baritone.settings().getItemMinY.value;
        limits.allowNether = Baritone.settings().getItemAllowNether.value || CostModel.NETHER.equals(startDim);
        limits.allowEnd = Baritone.settings().getItemAllowEnd.value || CostModel.END.equals(startDim);
        double horiz = Objects.equals(startDim, data.dimension()) && startPos != null
                ? Math.sqrt(Math.pow(ctx.player().getX() - startPos.getX(), 2) + Math.pow(ctx.player().getZ() - startPos.getZ(), 2)) : 0;
        String over = BudgetGuard.check(limits, (System.currentTimeMillis() - startMillis) / 1000.0, horiz, ctx.player().getY(), data.dimension());
        if (over != null) return stop(over);
        // A45/A46 safety
        PathingCommand safety = safety();
        if (safety != null) return safety;
        pauseReason = null;
        pausedForHostiles = false;

        if (task == null) {
            if (goalHave() >= goal.count) return done();
            if (idleTicks > 0) {
                idleTicks--; // let pickups / inventory sync settle between steps
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            StepTask side = sideTask();
            if (side != null) {
                task = side;
            } else if (spec != null) {
                Object t = advancedTask();
                if (t == null) return done();
                if (t instanceof String why) return stop(why);
                task = (StepTask) t;
                logDebug("Get Item step " + (stepsDone + 1) + ": " + task.step.describe());
            } else {
                Plan p = makePlan(goal);
                lastPlan = p;
                if (!p.success) return stop("no plan: " + p.failure);
                PlanStep step = p.first();
                if (step == null) return stop("plan is empty but the goal isn't met");
                task = create(step, p);
                logDebug("Get Item step " + (stepsDone + 1) + ": " + step.describe());
            }
        }
        PathingCommand cmd = task.tick();
        if (task.isDone()) {
            StepTask t = task;
            t.stop();
            task = null;
            if (t instanceof PlaceStationTask pst && pst.placedAt() != null) {
                BlockPos p = pst.placedAt();
                knowledge.base().stations.markPlaced(pst.step.blocks.get(0), p.getX(), p.getY(), p.getZ(), data.dimension(), System.currentTimeMillis());
                knowledge.base().markDirty();
            }
            if (isPlanStep(t)) {
                if (t.step.kind == PlanStep.Kind.MINE) t.step.blocks.forEach(blockFailures::remove);
                if (t.step.kind == PlanStep.Kind.GATHER) queueHarvestStore(t.step);
                stepsDone++;
                stepFailures = 0;
            }
            if (t instanceof TravelTask && returning) return finishReturn();
            if (t instanceof BrewTask bt) standFuel = bt.fuelLeft() > 0;
            idleTicks = 3;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (task.failure() != null) {
            String why = task.failure();
            StepTask t = task;
            t.stop();
            task = null;
            if (t instanceof TravelTask && returning) {
                logDirect("Get Item: couldn't go back: " + why);
                return finishReturn();
            }
            if (!isPlanStep(t)) {
                logDebug("Get Item: side task failed: " + why);
                idleTicks = 5;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            PlanStep step = t.step;
            lastFailure = why;
            if (step.kind == PlanStep.Kind.GATHER) sourceBlacklist.add(step.source.kind + "|" + step.source.ref);
            if (step.kind == PlanStep.Kind.MINE && why.startsWith("couldn't find") && explore(step)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Get Item: step failed: " + why);
            if (step.kind == PlanStep.Kind.MINE) {
                step.blocks.forEach(b -> blockFailures.merge(b, 1, Integer::sum));
                seenCache = null;
            }
            data.cache().clear();
            if (++stepFailures >= MAX_STEP_FAILURES) {
                return stop("giving up after " + stepFailures + " failed attempts: " + why);
            }
            idleTicks = 10;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return cmd;
    }

    /** A41: by-products of a farm / tree / mob step (seeds, extra crops, drops) to store afterwards. */
    private void queueHarvestStore(PlanStep step) {
        if (!Baritone.settings().getItemStoreHarvest.value) return;
        switch (step.source.kind) {
            case HARVEST:
            case TREE:
            case MOB_FARM:
            case HUNT:
                break;
            default:
                return;
        }
        for (Object[] c : Yields.CROPS.values()) storeQueue.add((String) c[0]);
        storeQueue.addAll(Arrays.asList("minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:melon_seeds",
                "minecraft:pumpkin_seeds", "minecraft:apple", "minecraft:poisonous_potato"));
        for (Yields.Drop d : Yields.MOB_DROPS) storeQueue.add(d.item);
        storeQueue.removeAll(goal.options);
    }

    private boolean isPlanStep(StepTask t) {
        return !(t instanceof EatTask || t instanceof PickupTask || t instanceof PeekTask || t instanceof DropJunkTask
                || t instanceof StashTask || t instanceof ExploreTask || t instanceof HotbarLayoutTask
                || t instanceof ShulkerPackTask || t instanceof XpOrbTask && spec == null);
    }

    private void dropTask() {
        if (task != null) {
            task.stop();
            if (task instanceof MineTask) baritone.getMineProcess().cancel();
            task = null;
        }
    }

    /** A45/A46: eat, flee or pause; null when it's safe to work. */
    private PathingCommand safety() {
        if (task instanceof EatTask) return null;
        SafetyMonitor.Settings s = new SafetyMonitor.Settings();
        s.autoEat = Baritone.settings().getItemAutoEat.value;
        s.minFood = Baritone.settings().getItemMinFood.value;
        s.minHealth = Baritone.settings().getItemMinHealth.value;
        s.hostileAction = SafetyMonitor.HostileAction.parse(Baritone.settings().getItemHostileAction.value);
        s.hostileRadius = Baritone.settings().getItemHostileRadius.value;
        s.avoidNight = Baritone.settings().getItemAvoidNight.value;
        SafetyMonitor.Inputs in = new SafetyMonitor.Inputs();
        in.health = ctx.player().getHealth();
        in.maxHealth = ctx.player().getMaxHealth();
        in.food = ctx.player().getFoodData().getFoodLevel();
        in.hasFood = EatTask.bestFood(ctx.player(), data.protectedItems()) >= 0;
        List<Entity> hostiles = s.hostileAction == SafetyMonitor.HostileAction.IGNORE ? List.of()
                : ctx.world().getEntities((Entity) null, new AABB(ctx.playerFeet()).inflate(s.hostileRadius),
                e -> e instanceof Enemy && e.isAlive() && !e.isSpectator() && Math.abs(e.getY() - ctx.player().getY()) < 6);
        in.hostilesNear = hostiles.size();
        double nearest = 99;
        for (Entity e : hostiles) nearest = Math.min(nearest, e.distanceTo(ctx.player()));
        in.nearestHostile = nearest;
        in.night = ctx.world().isDarkOutside();
        in.skyVisible = ctx.world().canSeeSky(ctx.playerFeet().above());
        SafetyMonitor.Verdict v = SafetyMonitor.evaluate(in, s);
        switch (v.action) {
            case OK:
                return null;
            case EAT:
                dropTask();
                task = new EatTask(baritone, data);
                pauseReason = v.reason;
                return null;
            case FLEE: {
                pauseReason = "fleeing: " + v.reason;
                dropTask();
                BlockPos[] from = hostiles.stream().map(Entity::blockPosition).toArray(BlockPos[]::new);
                return new PathingCommand(new GoalRunAway(s.hostileRadius + 8, from), PathingCommandType.SET_GOAL_AND_PATH);
            }
            default:
                pauseReason = v.reason;
                pausedForHostiles = in.hostilesNear > 0;
                dropTask();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    /** Between plan steps: peek into unknown chests (A11), keep inventory space (A43), pick up drops (A16). */
    private StepTask sideTask() {
        BlockPos me = ctx.playerFeet();
        String dim = data.dimension();
        if (!peeked) {
            peeked = true;
            if (Baritone.settings().getItemUseContainers.value && Baritone.settings().getItemScanNearbyContainers.value) {
                Set<Block> peekable = new HashSet<>(Arrays.asList(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL));
                Set<BlockPos> taken = new HashSet<>();
                for (BlockPos p : data.findBlocks(peekable, 16)) {
                    if (peekQueue.size() >= MAX_PEEKS) break;
                    if (taken.contains(p) || indexedHere(p, dim)) continue;
                    boolean neighbourKnown = false;
                    for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west()}) {
                        if (ctx.world().getBlockState(n).getBlock() == ctx.world().getBlockState(p).getBlock()) {
                            taken.add(n);
                            if (indexedHere(n, dim)) neighbourKnown = true;
                        }
                    }
                    if (!neighbourKnown) peekQueue.add(p);
                }
            }
        }
        if (!peekQueue.isEmpty()) return new PeekTask(baritone, data, knowledge, peekQueue.poll());
        // A41: put harvest by-products away
        if (!storeQueue.isEmpty()) {
            Set<String> items = new HashSet<>(storeQueue);
            storeQueue.clear();
            items.removeAll(neededItems());
            items.removeAll(data.protectedItems());
            items.removeIf(i -> data.inventory().getOrDefault(i, 0) <= 0);
            if (!items.isEmpty() && Baritone.settings().getItemUseContainers.value) {
                ContainerIndex.Container c = knowledge.base().containers.stashFor(items.iterator().next(), policy(), dim, me.getX(), me.getY(), me.getZ(),
                        Math.min(32, Baritone.settings().getItemContainerRadius.value));
                if (c != null && !ContainerIndex.ENDER_CHEST.equals(c.type)) {
                    return new StashTask(baritone, data, knowledge, new BlockPos(c.x, c.y, c.z), items);
                }
            }
        }
        // inventory space
        if (stashCooldown > 0) stashCooldown--;
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        List<InventoryPlanner.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty()) slots.add(new InventoryPlanner.Slot(i, GameData.id(s.getItem()), s.getCount(), s.isDamageableItem()));
        }
        int minFree = Baritone.settings().getItemMinFreeSlots.value;
        if (InventoryPlanner.freeSlots(slots, 36) < minFree && stashCooldown == 0) {
            Set<String> junk = new HashSet<>();
            for (Item i : Baritone.settings().getItemJunkItems.value) junk.add(GameData.id(i));
            InventoryPlanner.Decision d = InventoryPlanner.decide(slots, 36, minFree, junk, neededItems(), data.protectedItems(),
                    Baritone.settings().getItemDropJunk.value, Baritone.settings().getItemStashOverflow.value && Baritone.settings().getItemUseContainers.value);
            if (!d.drop.isEmpty()) return new DropJunkTask(baritone, data, d.drop);
            if (!d.stash.isEmpty()) {
                stashCooldown = 200;
                Set<String> items = new HashSet<>();
                for (int idx : d.stash) items.add(GameData.id(inv.get(idx).getItem()));
                ContainerIndex.Container c = knowledge.base().containers.stashFor(items.iterator().next(), policy(), dim, me.getX(), me.getY(), me.getZ(),
                        Math.min(32, Baritone.settings().getItemContainerRadius.value));
                if (c != null && !ContainerIndex.ENDER_CHEST.equals(c.type)) {
                    return new StashTask(baritone, data, knowledge, new BlockPos(c.x, c.y, c.z), items);
                }
            }
        }
        // A43: overflow into a carried shulker box when there's nowhere to stash
        if (Baritone.settings().getItemShulkerPack.value && InventoryPlanner.freeSlots(slots, 36) < minFree && packCooldown-- <= 0) {
            String box = null;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.get(i);
                if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                    box = GameData.id(s.getItem());
                    break;
                }
            }
            if (box != null) {
                packCooldown = 200;
                Set<String> junk = new HashSet<>();
                for (Item i : Baritone.settings().getItemJunkItems.value) junk.add(GameData.id(i));
                InventoryPlanner.Decision d = InventoryPlanner.decide(slots, 36, minFree, junk, neededItems(), data.protectedItems(), false, true);
                Set<String> items = new HashSet<>();
                for (int idx : d.stash) items.add(GameData.id(inv.get(idx).getItem()));
                items.remove(box);
                if (!items.isEmpty()) return new ShulkerPackTask(baritone, data, knowledge, box, items);
            }
        }
        // A43: hotbar tool layout
        if (!Baritone.settings().getItemHotbarLayout.value.isEmpty() && hotbarCooldown-- <= 0) {
            hotbarCooldown = 100;
            if (HotbarLayout.nextSwap(Baritone.settings().getItemHotbarLayout.value, HotbarLayoutTask.items(ctx.player().getInventory())) != null) {
                return new HotbarLayoutTask(baritone, data);
            }
        }
        // A45: experience orbs
        if (Baritone.settings().getItemCollectXpAlways.value && xpCooldown-- <= 0 && XpOrbTask.nearest(baritone) != null) {
            xpCooldown = 200;
            return new XpOrbTask(baritone, data);
        }
        // drops
        if (Baritone.settings().getItemPickupDrops.value) {
            Set<String> needed = neededItems();
            int r = Baritone.settings().getItemDropRadius.value;
            List<DropPicker.Drop> drops = new ArrayList<>();
            Map<DropPicker.Drop, ItemEntity> ents = new IdentityHashMap<>();
            for (Entity e : ctx.world().getEntities((Entity) null, new AABB(me).inflate(r), e -> e instanceof ItemEntity && e.isAlive())) {
                ItemEntity ie = (ItemEntity) e;
                if (skippedDrops.contains(ie.getId())) continue;
                DropPicker.Drop d = new DropPicker.Drop(GameData.id(ie.getItem().getItem()), ie.getX(), ie.getY(), ie.getZ(), ie.getItem().getCount(), ie.getAge());
                drops.add(d);
                ents.put(d, ie);
            }
            DropPicker.Drop d = DropPicker.pick(drops, needed, ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(), r, 4);
            if (d != null && InventoryPlanner.freeSlots(slots, 36) > 0) {
                ItemEntity ie = ents.get(d);
                skippedDrops.add(ie.getId()); // one attempt per entity
                return new PickupTask(baritone, data, ie);
            }
        }
        return null;
    }

    private boolean indexedHere(BlockPos p, String dim) {
        return knowledge.base().containers.get(dim, p.getX(), p.getY(), p.getZ()) != null;
    }

    /** Items the goal or the current plan uses or produces. */
    private Set<String> neededItems() {
        Set<String> out = new HashSet<>(goal.options);
        if (spec != null) {
            out.addAll(Arrays.asList("minecraft:glass_bottle", "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion",
                    "minecraft:blaze_powder", "minecraft:lapis_lazuli", "minecraft:enchanted_book", "minecraft:nether_wart"));
        }
        if (lastPlan != null) {
            for (PlanStep s : lastPlan.steps) {
                out.addAll(s.items);
                out.addAll(s.inputs.keySet());
                if (s.fuel != null) out.add(s.fuel);
                if (s.tool != null) out.add(s.tool);
                if (s.kind == PlanStep.Kind.PLACE_STATION) out.addAll(s.blocks);
            }
        }
        return out;
    }

    /** A13/A15: go look for the blocks of a MINE step that MineProcess couldn't find. */
    private boolean explore(PlanStep step) {
        if (!Baritone.settings().getItemExplore.value) return false;
        String key = step.blocks.get(0);
        int n = exploreCount.getOrDefault(key, 0);
        if (n >= Baritone.settings().getItemExploreMaxWaypoints.value) return false;
        exploreCount.put(key, n + 1);
        int[] wp = null;
        String label;
        // first try the best remembered vein (Baritone's cache), scored by size, distance, depth and hazards
        if (n == 0) {
            var world = baritone.getWorldProvider().getCurrentWorld();
            BlockPos me = ctx.playerFeet();
            if (world != null) {
                List<int[]> pos = new ArrayList<>();
                for (String b : step.blocks) {
                    for (BlockPos p : world.getCachedWorld().getLocationsOf(b.replace("minecraft:", ""), 64, me.getX(), me.getZ(), 4)) {
                        pos.add(new int[]{p.getX(), p.getY(), p.getZ()});
                    }
                }
                List<OreLocator.Vein> veins = OreLocator.veins(key, pos, me.getX(), me.getY(), me.getZ(),
                        Baritone.settings().getItemAvoidHazards.value ? knowledge.base().hazards : null, data.dimension());
                if (!veins.isEmpty()) wp = new int[]{veins.get(0).x, veins.get(0).y, veins.get(0).z};
            }
        }
        if (wp != null) {
            label = "remembered vein";
        } else {
            BlockPos c = startPos != null && Objects.equals(startDim, data.dimension()) ? startPos : ctx.playerFeet();
            wp = ExplorationPlanner.waypoint(step.blocks, c.getX(), c.getZ(), n, 40, Math.max(ctx.world().getMinY() + 5, Baritone.settings().getItemMinY.value));
            label = "waypoint " + (n + 1) + "/" + Baritone.settings().getItemExploreMaxWaypoints.value;
        }
        logDirect("Get Item: no " + key.replace("minecraft:", "") + " nearby, exploring (" + label + ")");
        task = new ExploreTask(baritone, data, step, wp, label);
        return true;
    }

    private StepTask create(PlanStep step, Plan plan) {
        switch (step.kind) {
            case MINE:
                return new MineTask(baritone, data, step);
            case PLACE_STATION:
                return new PlaceStationTask(baritone, data, step);
            case SMELT:
                if (step.recipe != null && step.recipe.type == RecipeType.CAMPFIRE_COOKING) return new CampfireTask(baritone, data, step, stationBlacklist);
                return new SmeltTask(baritone, data, step, stationBlacklist);
            case WITHDRAW: {
                Map<String, Integer> also = new LinkedHashMap<>();
                for (PlanStep s : plan.steps) if (s.kind == PlanStep.Kind.WITHDRAW) also.merge(s.items.get(0), s.count, Integer::sum);
                return new WithdrawTask(baritone, data, knowledge, step, withdrawFrom(step.items.get(0)), also);
            }
            case TRAVEL:
                return new TravelTask(baritone, data, step);
            case GATHER:
                switch (step.source.kind) {
                    case TRADE:
                        return new TradeTask(baritone, data, knowledge, step);
                    case HARVEST:
                        return new HarvestTask(baritone, data, step);
                    case TREE:
                        return new TreeTask(baritone, data, step);
                    case FISH:
                        return new FishTask(baritone, data, knowledge, step);
                    case MOB_FARM:
                        return new MobFarmTask(baritone, data, step);
                    case COMPOST:
                        return new ComposterTask(baritone, data, step, stationBlacklist);
                    default:
                        return new AnimalTask(baritone, data, step);
                }
            default:
                if (step.recipe != null && step.recipe.type == RecipeType.STONECUTTING) return new CutTask(baritone, data, step, stationBlacklist);
                if (step.recipe != null && step.recipe.type == RecipeType.SMITHING) return new SmithTask(baritone, data, step, stationBlacklist);
                return new CraftTask(baritone, data, step, stationBlacklist);
        }
    }

    /** Nearest usable container holding the item (an ender chest's contents via the nearest ender chest block). */
    private BlockPos withdrawFrom(String item) {
        BlockPos me = ctx.playerFeet();
        String dim = data.dimension();
        for (ContainerIndex.Container c : knowledge.base().containers.holding(item, policy(), dim, me.getX(), me.getY(), me.getZ(),
                Baritone.settings().getItemContainerRadius.value)) {
            if (!ContainerIndex.ENDER_CHEST.equals(c.type)) return new BlockPos(c.x, c.y, c.z);
            List<BlockPos> ec = data.findBlocks(Collections.singleton(Blocks.ENDER_CHEST), 32);
            if (!ec.isEmpty()) return ec.get(0);
        }
        return null;
    }

    private PathingCommand done() {
        logDirect("Get Item: done, have " + goal);
        knowledge.setPending(null, 0);
        if (Baritone.settings().getItemPickupStations.value) {
            BlockPos me = ctx.playerFeet();
            for (StationRegistry.Station s : knowledge.base().stations.placedByUs(data.dimension(), me.getX(), me.getY(), me.getZ(), 24)) {
                BlockPos p = new BlockPos(s.x, s.y, s.z);
                baritone.getBuilderProcess().clearArea(p, p);
                knowledge.base().stations.remove(s.dim, s.x, s.y, s.z);
                break; // one at a time; the rest are picked up next time
            }
        }
        if (Baritone.settings().getItemReturnHome.value && startDim != null && !startDim.equals(data.dimension()) && !returning) {
            returning = true;
            logDirect("Get Item: going back to " + PlanStep.dimensionName(startDim));
            task = new TravelTask(baritone, data, PlanStep.travel(startDim, 0, 0));
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand finishReturn() {
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand stop(String why) {
        lastFailure = why;
        logDirect("Get Item: stopped, " + why);
        knowledge.setPending(null, 0);
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Clears run state (keeps the pending goal). */
    private void reset() {
        if (task != null) {
            task.stop();
            task = null;
        }
        MineTask.clearToolHooks();
        restoreRunSettings();
        goal = null;
        spec = null;
        specText = null;
        standFuel = false;
        rerollsUsed = 0;
        xpTries = 0;
        hotbarCooldown = 0;
        xpCooldown = 0;
        packCooldown = 0;
        idleTicks = 0;
        stationBlacklist.clear();
        blockFailures.clear();
        exploreCount.clear();
        seenCache = null;
        userPaused = false;
        pauseReason = null;
        pausedForHostiles = false;
        peeked = false;
        peekQueue.clear();
        skippedDrops.clear();
        returning = false;
        resumeDelay = -1;
        stashCooldown = 0;
    }

    @Override
    public synchronized void onLostControl() {
        boolean had = goal != null || resumeDelay >= 0;
        if (had && (ctx.player() == null || ctx.world() == null)) {
            onDisconnect(); // leaving the world: keep the goal for A50 resume
            return;
        }
        reset();
        if (had) knowledge.setPending(null, 0);
    }

    @Override
    public String displayName0() {
        return "Get Item" + (goal != null ? " " + goal.display + (task != null ? " (" + task.status() + ")" : "") : "");
    }

    @Override
    public double priority() {
        return 0;
    }
}
