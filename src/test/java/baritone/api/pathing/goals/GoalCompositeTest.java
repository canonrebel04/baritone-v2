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

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalCompositeTest {

    @Test
    public void testLargeCompositeMatchesNaive() {
        Random rng = new Random(42L);
        Goal[] goals = new Goal[64];
        for (int i = 0; i < goals.length; i++) {
            int x = rng.nextInt(300) - 150;
            int y = rng.nextInt(120) - 20;
            int z = rng.nextInt(300) - 150;
            goals[i] = switch (i % 6) {
                case 0 -> new GoalBlock(x, y, z);
                case 1 -> new GoalTwoBlocks(x, y, z);
                case 2 -> new GoalGetToBlock(new BlockPos(x, y, z));
                case 3 -> new GoalNear(new BlockPos(x, y, z), 3);
                case 4 -> new GoalXZ(x, z);
                default -> new Goal() {
                    @Override
                    public boolean isInGoal(int qx, int qy, int qz) {
                        return qx == x && qy == y && qz == z;
                    }

                    @Override
                    public double heuristic(int qx, int qy, int qz) {
                        return Math.abs(qx - x) + Math.abs(qy - y) + Math.abs(qz - z) + 50.0;
                    }
                };
            };
        }

        GoalComposite composite = new GoalComposite(goals);

        // Test random query points
        for (int q = 0; q < 500; q++) {
            int x = rng.nextInt(400) - 200;
            int y = rng.nextInt(140) - 30;
            int z = rng.nextInt(400) - 200;
            assertMatchesNaive(goals, composite, x, y, z);
        }

        // Test points directly on and adjacent to each sub-goal
        for (Goal g : goals) {
            if (g instanceof GoalBlock b) {
                assertMatchesNaive(goals, composite, b.x, b.y, b.z);
                assertMatchesNaive(goals, composite, b.x + 1, b.y, b.z);
            } else if (g instanceof GoalTwoBlocks b) {
                assertMatchesNaive(goals, composite, b.x, b.y, b.z);
                assertMatchesNaive(goals, composite, b.x, b.y - 1, b.z);
            } else if (g instanceof GoalGetToBlock b) {
                assertMatchesNaive(goals, composite, b.x + 1, b.y, b.z);
                assertMatchesNaive(goals, composite, b.x, b.y + 1, b.z);
            } else if (g instanceof GoalXZ b) {
                assertMatchesNaive(goals, composite, b.getX(), 64, b.getZ());
            }
        }
    }

    @Test
    public void testEmptyAndSmallComposite() {
        GoalComposite empty = new GoalComposite();
        assertFalse(empty.isInGoal(0, 64, 0));
        if (Minecraft.getInstance() != null) {
            assertEquals(Double.MAX_VALUE, empty.heuristic(0, 64, 0), 0.0);
        }

        Goal[] two = new Goal[] { new GoalBlock(10, 64, 10), new GoalBlock(-10, 64, -10) };
        GoalComposite small = new GoalComposite(two);
        assertTrue(small.isInGoal(10, 64, 10));
        assertFalse(small.isInGoal(0, 64, 0));
        assertMatchesNaive(two, small, 5, 64, 5);
    }

    private static void assertMatchesNaive(Goal[] goals, GoalComposite composite, int x, int y, int z) {
        boolean naiveInGoal = false;
        for (Goal g : goals) {
            if (g.isInGoal(x, y, z)) {
                naiveInGoal = true;
                break;
            }
        }
        assertEquals("isInGoal mismatch at " + x + "," + y + "," + z, naiveInGoal, composite.isInGoal(x, y, z));
        if (Minecraft.getInstance() != null) {
            double naiveMin = Double.MAX_VALUE;
            for (Goal g : goals) {
                naiveMin = Math.min(naiveMin, g.heuristic(x, y, z));
            }
            assertEquals("heuristic mismatch at " + x + "," + y + "," + z, naiveMin, composite.heuristic(x, y, z), 1e-9);
        }
    }
}
