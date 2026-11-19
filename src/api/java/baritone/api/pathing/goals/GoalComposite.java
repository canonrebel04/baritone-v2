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

package baritone.api.pathing.goals;

import java.util.Arrays;

/**
 * A composite of many goals, any one of which satisfies the composite.
 * For example, a GoalComposite of block goals for every oak log in loaded chunks
 * would result in it pathing to the easiest oak log to get to
 *
 * @author avecowa
 */
public class GoalComposite implements Goal {

    /**
     * An array of goals that any one of must be satisfied
     */
    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals;
    }

    private transient volatile Bounds bounds;
    private transient int lastBestIndex;

    private static final class Bounds {
        final int[] gx;
        final int[] gy;
        final int[] gz;
        final int[] slackXZ;
        final int[] slackY;
        final boolean[] hasXZ;
        final boolean[] hasInGoalBounds;

        Bounds(Goal[] goals) {
            int n = goals.length;
            this.gx = new int[n];
            this.gy = new int[n];
            this.gz = new int[n];
            this.slackXZ = new int[n];
            this.slackY = new int[n];
            this.hasXZ = new boolean[n];
            this.hasInGoalBounds = new boolean[n];
            for (int i = 0; i < n; i++) {
                Goal g = goals[i];
                Class<?> cls = g.getClass();
                if (cls == GoalBlock.class) {
                    GoalBlock b = (GoalBlock) g;
                    gx[i] = b.x;
                    gy[i] = b.y;
                    gz[i] = b.z;
                    hasXZ[i] = true;
                    hasInGoalBounds[i] = true;
                } else if (cls == GoalTwoBlocks.class) {
                    GoalTwoBlocks b = (GoalTwoBlocks) g;
                    gx[i] = b.x;
                    gy[i] = b.y;
                    gz[i] = b.z;
                    slackY[i] = 1;
                    hasXZ[i] = true;
                    hasInGoalBounds[i] = true;
                } else if (g instanceof GoalTwoBlocks && cls.getName().equals("baritone.process.MineProcess$GoalThreeBlocks")) {
                    GoalTwoBlocks b = (GoalTwoBlocks) g;
                    gx[i] = b.x;
                    gy[i] = b.y;
                    gz[i] = b.z;
                    slackY[i] = 2;
                    hasXZ[i] = true;
                    hasInGoalBounds[i] = true;
                } else if (cls == GoalGetToBlock.class) {
                    GoalGetToBlock b = (GoalGetToBlock) g;
                    gx[i] = b.x;
                    gy[i] = b.y;
                    gz[i] = b.z;
                    slackXZ[i] = 1;
                    slackY[i] = 1;
                    hasXZ[i] = true;
                    hasInGoalBounds[i] = true;
                } else if (cls == GoalNear.class) {
                    net.minecraft.core.BlockPos pos = ((GoalNear) g).getGoalPos();
                    gx[i] = pos.getX();
                    gy[i] = pos.getY();
                    gz[i] = pos.getZ();
                    hasXZ[i] = true;
                } else if (cls == GoalXZ.class) {
                    GoalXZ b = (GoalXZ) g;
                    gx[i] = b.getX();
                    gz[i] = b.getZ();
                    hasXZ[i] = true;
                }
            }
        }
    }

    private Bounds getBounds() {
        Bounds b = this.bounds;
        if (b == null) {
            b = new Bounds(goals);
            this.bounds = b;
        }
        return b;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int len = goals.length;
        if (len == 0) {
            return false;
        }
        int hint = lastBestIndex;
        if (hint >= 0 && hint < len && goals[hint].isInGoal(x, y, z)) {
            return true;
        }
        if (len <= 4) {
            for (int i = 0; i < len; i++) {
                if (i != hint && goals[i].isInGoal(x, y, z)) {
                    lastBestIndex = i;
                    return true;
                }
            }
            return false;
        }
        Bounds b = getBounds();
        for (int i = 0; i < len; i++) {
            if (i == hint) {
                continue;
            }
            if (b.hasInGoalBounds[i]) {
                if (Math.abs(x - b.gx[i]) > b.slackXZ[i]
                        || Math.abs(z - b.gz[i]) > b.slackXZ[i]
                        || Math.abs(y - b.gy[i]) > b.slackY[i]) {
                    continue;
                }
            }
            if (goals[i].isInGoal(x, y, z)) {
                lastBestIndex = i;
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int len = goals.length;
        if (len == 0) {
            return Double.MAX_VALUE;
        }
        if (len <= 4) {
            double min = Double.MAX_VALUE;
            for (Goal g : goals) {
                // TODO technically this isn't admissible...?
                min = Math.min(min, g.heuristic(x, y, z)); // whichever is closest
            }
            return min;
        }
        Bounds b = getBounds();
        double min = Double.MAX_VALUE;
        int bestIdx = -1;
        int hint = lastBestIndex;
        if (hint >= 0 && hint < len) {
            min = goals[hint].heuristic(x, y, z);
            bestIdx = hint;
        }
        double costHeuristic = baritone.api.BaritoneAPI.getSettings().costHeuristic.value;
        double maxDist = costHeuristic > 0 ? min / costHeuristic : Double.POSITIVE_INFINITY;
        for (int i = 0; i < len; i++) {
            if (i == hint) {
                continue;
            }
            if (b.hasXZ[i]) {
                if (Math.abs(x - b.gx[i]) > maxDist || Math.abs(z - b.gz[i]) > maxDist) {
                    continue;
                }
            }
            // TODO technically this isn't admissible...?
            double h = goals[i].heuristic(x, y, z); // whichever is closest
            if (h < min) {
                min = h;
                bestIdx = i;
                maxDist = costHeuristic > 0 ? min / costHeuristic : Double.POSITIVE_INFINITY;
            }
        }
        if (bestIdx >= 0) {
            lastBestIndex = bestIdx;
        }
        return min;
    }

    @Override
    public double heuristic() {
        double min = Double.MAX_VALUE;
        for (Goal g : goals) {
            // just take the highest value that is guaranteed to be inside the goal
            min = Math.min(min, g.heuristic());
        }
        return min;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GoalComposite goal = (GoalComposite) o;
        return Arrays.equals(goals, goal.goals);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(goals);
    }

    @Override
    public String toString() {
        return "GoalComposite" + Arrays.toString(goals);
    }

    public Goal[] goals() {
        return goals;
    }
}
