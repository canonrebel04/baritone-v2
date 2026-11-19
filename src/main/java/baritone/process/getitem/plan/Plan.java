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

/** Result of planning: ordered steps, estimate and risks (plan A9 explainer), or why it failed. */
public final class Plan {
    public final Goal goal;
    public final boolean success;
    public final List<PlanStep> steps;
    public final double seconds;
    public final List<String> risks;
    public final String failure;
    /** Items left over by batching that the plan doesn't consume (A6/A7). */
    public final Map<String, Integer> leftovers;

    Plan(Goal goal, boolean success, List<PlanStep> steps, double seconds, List<String> risks, String failure, Map<String, Integer> leftovers) {
        this.goal = goal;
        this.success = success;
        this.steps = Collections.unmodifiableList(steps);
        this.seconds = seconds;
        this.risks = Collections.unmodifiableList(risks);
        this.failure = failure;
        this.leftovers = Collections.unmodifiableMap(leftovers);
    }

    public boolean isDone() {
        return success && steps.isEmpty();
    }

    public PlanStep first() {
        return steps.isEmpty() ? null : steps.get(0);
    }

    public static String formatEta(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60 == 0 ? "" : " " + (s % 60) + "s");
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /** Human readable tree for {@code #get plan} / {@code #get why}. */
    public List<String> explain() {
        List<String> out = new ArrayList<>();
        if (!success) {
            out.add("Can't get " + goal + ": " + failure);
            return out;
        }
        if (steps.isEmpty()) {
            out.add("Already have " + goal);
            return out;
        }
        out.add("Plan for " + goal + ": " + steps.size() + " steps, ETA ~" + formatEta(seconds));
        int i = 1;
        for (PlanStep s : steps) {
            out.add(String.format("%2d. %s%s (~%s)", i++, "  ".repeat(Math.max(0, Math.min(6, s.depth))), s.describe(), formatEta(s.seconds)));
        }
        if (!leftovers.isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            leftovers.forEach((k, v) -> j.add(v + "x " + (k.startsWith("minecraft:") ? k.substring(10) : k)));
            out.add("Left over: " + j);
        }
        for (String r : risks) out.add("Risk: " + r);
        return out;
    }
}
