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
import baritone.api.process.IGetItemProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.Goal;
import baritone.process.getitem.plan.Plan;
import baritone.process.getitem.plan.PlanStep;
import baritone.process.getitem.plan.Planner;
import baritone.process.getitem.tasks.*;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Get Item (plan Part 4, milestone M1): plans how to obtain an item and runs the plan one step at a time,
 * re-planning from the real inventory after every step (A10). Mining defers to {@link MineProcess}; crafting and
 * smelting drive containers with paced, legal clicks. Priority 0, just above MineProcess.
 */
public final class GetItemProcess extends BaritoneProcessHelper implements IGetItemProcess {
    private static final int MAX_STEP_FAILURES = 3;

    private final GameData data;
    private Goal goal;
    private StepTask task;
    private Plan lastPlan;
    private String lastFailure;
    private int stepFailures;
    private int stepsDone;
    private int idleTicks;
    private final Set<BlockPos> stationBlacklist = new HashSet<>();
    private final Map<String, Integer> blockFailures = new HashMap<>();
    private Set<String> seenCache;
    private int seenAge;

    public GetItemProcess(Baritone baritone) {
        super(baritone);
        this.data = new GameData(baritone.getPlayerContext());
    }

    // ---------------------------------------------------------------- API

    @Override
    public synchronized String get(String item, int amount) {
        if (ctx.player() == null) return "not in game";
        onLostControl();
        Goal g = Goal.parse(item, amount, data.planner().graph, Collections.emptyList());
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
        logDirect("Get Item: " + g + " (" + p.steps.size() + " steps, ETA ~" + Plan.formatEta(p.seconds) + ")");
        return null;
    }

    @Override
    public List<String> plan(String item, int amount) {
        if (ctx.player() == null) return Collections.singletonList("not in game");
        Goal g = Goal.parse(item, amount, data.planner().graph, Collections.emptyList());
        if (g == null) return Collections.singletonList("unknown item or tag: " + item);
        Plan p = makePlan(g);
        lastPlan = p;
        return p.explain();
    }

    @Override
    public synchronized String status() {
        if (goal == null) return lastFailure == null ? "idle" : "idle (last: " + lastFailure + ")";
        int have = goal.have(data.inventory());
        return "getting " + goal.display + " " + have + "/" + goal.count + ", step " + (stepsDone + 1)
                + (task != null ? ": " + task.status() : "");
    }

    @Override
    public List<String> why() {
        List<String> out = new ArrayList<>();
        if (lastFailure != null) out.add("Last failure: " + lastFailure);
        if (lastPlan != null) out.addAll(lastPlan.explain());
        if (out.isEmpty()) out.add("Nothing planned yet");
        return out;
    }

    @Override
    public synchronized void cancel() {
        onLostControl();
    }

    // ---------------------------------------------------------------- planning

    private Planner.Options options() {
        Planner.Options o = new Planner.Options();
        o.protectedItems = data.protectedItems();
        o.dimension = data.dimension();
        o.maxDepth = Baritone.settings().getItemMaxPlanDepth.value;
        o.allowPlaceStations = Baritone.settings().getItemPlaceStations.value;
        o.stations = data.nearbyStations(stationBlacklist);
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

    private Plan makePlan(Goal g) {
        return data.cache().plan(g, data.inventory(), options());
    }

    // ---------------------------------------------------------------- execution

    @Override
    public boolean isActive() {
        return goal != null;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (goal == null || ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (task == null) {
            if (goal.have(data.inventory()) >= goal.count) {
                logDirect("Get Item: done, have " + goal);
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (idleTicks > 0) {
                idleTicks--; // let pickups / inventory sync settle between steps
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            Plan p = makePlan(goal);
            lastPlan = p;
            if (!p.success) {
                return stop("no plan: " + p.failure);
            }
            PlanStep step = p.first();
            if (step == null) {
                return stop("plan is empty but the goal isn't met");
            }
            task = create(step);
            logDebug("Get Item step " + (stepsDone + 1) + ": " + step.describe());
        }
        PathingCommand cmd = task.tick();
        if (task.isDone()) {
            task.stop();
            if (task.step.kind == PlanStep.Kind.MINE) task.step.blocks.forEach(blockFailures::remove);
            task = null;
            stepsDone++;
            stepFailures = 0;
            idleTicks = 3;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (task.failure() != null) {
            String why = task.failure();
            PlanStep step = task.step;
            task.stop();
            task = null;
            lastFailure = why;
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

    private StepTask create(PlanStep step) {
        switch (step.kind) {
            case MINE:
                return new MineTask(baritone, data, step);
            case PLACE_STATION:
                return new PlaceStationTask(baritone, data, step);
            case SMELT:
                return new SmeltTask(baritone, data, step, stationBlacklist);
            default:
                return new CraftTask(baritone, data, step, stationBlacklist);
        }
    }

    private PathingCommand stop(String why) {
        lastFailure = why;
        logDirect("Get Item: stopped, " + why);
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override
    public synchronized void onLostControl() {
        if (task != null) {
            task.stop();
            task = null;
        }
        MineTask.clearToolHooks();
        goal = null;
        idleTicks = 0;
        stationBlacklist.clear();
        blockFailures.clear();
        seenCache = null;
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
