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

package baritone.utils;

import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.Goal;
import baritone.cache.CachedWorld;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import java.awt.*;
import java.util.List;

/**
 * In-world rendering of the frontier exploration state: frontier chunks as colored quads
 * on a horizontal slice at the exploration Y band — cave-exposed = orange, plain = cyan,
 * current goal = green. The ExynAI-style growing-map visualization, drawn through the same
 * Iris-safe fallback as all Baritone lines.
 */
public final class FrontierMapRenderer implements IRenderer {

    private static final Color FRONTIER_COLOR = new Color(60, 200, 255, 90);       // cyan
    private static final Color CAVE_FRONTIER_COLOR = new Color(255, 140, 40, 110); // orange
    private static final Color GOAL_COLOR = new Color(60, 255, 120, 130);          // green

    private FrontierMapRenderer() {}

    /**
     * Draws frontier chunk markers at the slice Y. Called from the render pass while a
     * frontier mission is active.
     *
     * @param event     the render event (pose stack camera-relative)
     * @param world     cached world (known-column source)
     * @param frontiers current frontier candidates, each {chunkX, chunkZ, caveExposed(0/1)}
     * @param goal      the currently selected frontier goal (may be null)
     * @param renderY   world Y to draw the slice at
     */
    public static void render(RenderEvent event, CachedWorld world, List<long[]> frontiers,
                              Goal goal, int renderY) {
        if (world == null || frontiers == null || frontiers.isEmpty()) return;

        PoseStack stack = event.getModelViewStack();

        for (long[] f : frontiers) {
            int chunkX = (int) f[0];
            int chunkZ = (int) f[1];
            boolean caveExposed = f[2] != 0;
            boolean isGoal = goal != null
                    && goal.isInGoal(chunkX << 4 | 8, renderY, chunkZ << 4 | 8);

            Color color = isGoal ? GOAL_COLOR : (caveExposed ? CAVE_FRONTIER_COLOR : FRONTIER_COLOR);
            drawChunkOutline(stack, chunkX, chunkZ, renderY, color);
        }
    }

    /** Draws a chunk-boundary outline with an inner cross at the given Y. */
    private static void drawChunkOutline(PoseStack stack, int chunkX, int chunkZ, int y, Color color) {
        float x0 = chunkX << 4;
        float z0 = chunkZ << 4;
        float x1 = x0 + 16;
        float z1 = z0 + 16;

        BufferBuilder bb = IRenderer.startLines(color, (color.getAlpha() / 255f) * 0.6f);
        IRenderer.emitLine(bb, stack, x0, y, z0, x1, y, z0, 2f);
        IRenderer.emitLine(bb, stack, x1, y, z0, x1, y, z1, 2f);
        IRenderer.emitLine(bb, stack, x1, y, z1, x0, y, z1, 2f);
        IRenderer.emitLine(bb, stack, x0, y, z1, x0, y, z0, 2f);
        IRenderer.emitLine(bb, stack, x0, y, z0, x1, y, z1, 1f);
        IRenderer.emitLine(bb, stack, x1, y, z0, x0, y, z1, 1f);
        IRenderer.endLines(bb, false);
    }
}
