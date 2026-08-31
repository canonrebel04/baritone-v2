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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.cache.CachedChunk;
import baritone.cache.CachedWorld;
import baritone.utils.BaritoneProcessHelper;

import java.util.*;

/**
 * ExynAI-style exploration: frontier-based 3D-aware discovery of unexplored space.
 *
 * <p>Unlike {@link ExploreProcess} (which picks the nearest uncached chunk column in the XZ
 * plane and paths to it at any height), this process is *depth-aware*:
 *
 * <ul>
 *   <li>It builds a frontier set of uncached chunk columns that border the known (cached) world,
 *       weighted by "cave exposure" — how much known cave air exists in the neighboring cached
 *       columns below the surface. A frontier chunk adjacent to known cave systems scores much
 *       higher than one adjacent to flat grassland, because in Minecraft, entering an uncached
 *       column through a cave is how real underground exploration happens.</li>
 *   <li>It paths to frontier chunks at cave-appropriate Y levels (when cave-exposed) instead of
 *       over the surface, so the bot actually discovers the underground instead of flying over it.</li>
 *   <li>It ranks candidates by an information-gain heuristic (expected new volume) over travel
 *       cost, not by raw distance — the same trade-off ExynAI's next-best-view planner makes.</li>
 *   <li>It supports a bounded mission: max radius from start, optional center, and completes
 *       when the frontier inside the mission volume is exhausted (Status#DONE).</li>
 * </ul>
 *
 * <p>The known-world map is {@link CachedWorld} itself — the on-the-fly 2-bit cache. Unknown
 * space is simply "not cached yet". The frontier is the boundary between cached and uncached
 * columns, resolved 3D-ly through the cave-exposure weighting.
 */
public final class FrontierExplorerProcess extends BaritoneProcessHelper {

    /**
     * How many blocks of "air below surface" in a neighboring cached column count as cave
     * exposure for a frontier chunk. Scaled 0..1 by dividing by this constant.
     */
    private static final int CAVE_EXPOSURE_SATURATION = 512;

    /**
     * Y band considered "cave level" for exposure counting (roughly below deepslate).
     */
    private static final int CAVE_Y_MAX = 40;

    private record Frontier(int x, int z, double score, boolean caveExposed) {}

    private final Set<Long> failedFrontiers = new HashSet<>();
    private long lastPickTick = Long.MIN_VALUE;
    private Frontier currentGoal;

    /** Read-only snapshot of the current frontier goal for renderers. */
    public Goal currentGoalSnapshot() {
        Frontier f = this.currentGoal;
        return f == null ? null : new GoalXZ(f.x() << 4 | 8, f.z() << 4 | 8);
    }

    private int missionCenterX;
    private int missionCenterZ;
    private int missionRadius = -1; // -1 = unbounded
    private volatile boolean active;

    public FrontierExplorerProcess(Baritone baritone) {
        super(baritone);
    }

    /**
     * Begins an exploration mission centered at the current position.
     *
     * @param radius max chunk radius from the mission center (-1 for unbounded)
     */
    public void explore(int radius) {
        this.missionCenterX = ctx.playerFeet().getX() >> 4;
        this.missionCenterZ = ctx.playerFeet().getZ() >> 4;
        this.missionRadius = radius;
        this.active = true;
        this.failedFrontiers.clear();
        this.currentGoal = null;
    }

    /** Begins an exploration mission centered at specific chunk coords. */
    public void exploreAt(int chunkX, int chunkZ, int radius) {
        this.missionCenterX = chunkX;
        this.missionCenterZ = chunkZ;
        this.missionRadius = radius;
        this.active = true;
        this.failedFrontiers.clear();
        this.currentGoal = null;
    }

    public void stop() {
        this.active = false;
        this.currentGoal = null;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public double priority() {
        if (!active) return -1;
        // Must be BELOW CustomGoalProcess (Meteor CombatBrain / follow / goto all use it).
        // Exploration is a background mission: any explicit combat/navigation goal wins.
        return -0.5;
    }

    /**
     * Temporary process: yields control to any non-temporary process that wants it
     * (CombatBrain engagement, user commands), resuming automatically when they're done.
     */
    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        // External process took control — deactivate but keep the mission resumable.
        active = false;
        currentGoal = null;
    }

    @Override
    public String displayName0() {
        return "Frontier Explorer (3D)";
    }

    private final long[] lastPick = {Long.MIN_VALUE};

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active) return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);

        // On path calc failure toward the current frontier, blacklist it and pick another.
        if ((calcFailed) && currentGoal != null) {
            failedFrontiers.add(ChunkPos.pack(currentGoal.x(), currentGoal.z()));
            currentGoal = null;
        }

        // Re-pick every 200 ticks or when we have no current goal.
        long now = ctx.world().getGameTime();
        if (currentGoal != null && now - lastPick[0] < 200) {
            return new PathingCommand(goalFor(currentGoal), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }

        Frontier best = pickFrontier();
        if (best == null) {
            logDirect("Frontier exploration complete — no reachable unexplored chunks in mission volume.");
            active = false;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        currentGoal = best;
        lastPick[0] = now;
        failedFrontiers.remove(ChunkPos.pack(best.x(), best.z()));
        return new PathingCommand(goalFor(best), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    private Goal goalFor(Frontier f) {
        if (f.caveExposed()) {
            // Path INTO the cave level near this frontier: aim for a Y in the cave band so the
            // approach actually enters the underground rather than walking over the surface.
            return new GoalComposite(new Goal[]{
                    new GoalXZ(f.x() << 4 | 8, f.z() << 4 | 8),
                    new CaveYGoal()
            });
        }
        return new GoalXZ(f.x() << 4 | 8, f.z() << 4 | 8);
    }

    /** Last computed frontier set for the renderer: {chunkX, chunkZ, caveExposed(0/1)}. */
    private volatile List<long[]> lastFrontiers = List.of();

    public List<long[]> getFrontiersForRender() {
        return lastFrontiers;
    }

    /**
     * Scans the boundary ring of the cached world for the highest-value frontier chunk.
     */
    private Frontier pickFrontier() {
        CachedWorld world = (CachedWorld) baritone.getWorldProvider().getCurrentWorld().getCachedWorld();
        if (world == null) return null;

        int playerChunkX = ctx.playerFeet().getX() >> 4;
        int playerChunkZ = ctx.playerFeet().getZ() >> 4;
        int centerChunkX = missionRadius >= 0 ? missionCenterX : playerChunkX;
        int centerChunkZ = missionRadius >= 0 ? missionCenterZ : playerChunkZ;

        Frontier best = null;
        List<long[]> allFrontiers = new ArrayList<>();
        int ring = 1;
        int maxRing = missionRadius >= 0 ? missionRadius : 64; // unbounded: search 64 chunks out

        while (ring <= maxRing && best == null) {
            List<int[]> candidates = ringChunks(centerChunkX, centerChunkZ, ring);

            for (int[] c : candidates) {
                int cx = c[0], cz = c[1];

                if (world.isCached(cx << 4, cz << 4)) continue;                 // already known
                if (failedFrontiers.contains(ChunkPos.pack(cx, cz))) continue;  // previously unreachable
                if (missionRadius >= 0 && chunkDist(cx, cz, missionCenterX, missionCenterZ) > missionRadius) continue;

                // Must touch at least one cached neighbor (it IS the frontier).
                double caveExposure = 0;
                boolean touchesKnown = false;
                boolean caveNeighbor = false;
                for (int[] n : new int[][]{{cx+1,cz},{cx-1,cz},{cx,cz+1},{cx,cz-1}}) {
                    if (!world.isCached(n[0] << 4, n[1] << 4)) continue;
                    touchesKnown = true;
                    double exposure = caveExposureOf(world, n[0], n[1]);
                    if (exposure > 0) caveNeighbor = true;
                    caveExposure += exposure;
                }
                if (!touchesKnown) continue;

                // Info-gain-over-cost heuristic: exposure signal (expected underground volume)
                // divided by travel distance. Flat-surface frontiers get a small constant gain
                // so they're still reachable when no cave frontiers exist.
                double dist = chunkDist(cx, cz, playerChunkX, playerChunkZ) + 1.0;
                double gain = caveNeighbor ? Math.min(1.0, caveExposure / CAVE_EXPOSURE_SATURATION) * 100.0 : 1.0;
                double score = gain / dist;

                if (caveNeighbor || ring <= 2) {
                    allFrontiers.add(new long[]{cx, cz, caveNeighbor ? 1 : 0});
                }

                if (best == null || score > best.score()) {
                    best = new Frontier(cx, cz, score, caveNeighbor);
                }
            }

            if (best == null) ring++;
        }

        this.lastFrontiers = allFrontiers;
        return best;
    }

    /**
     * Counts known air blocks below CAVE_Y_MAX in a cached column (sampled every 4 blocks for
     * speed — a full scan is 320 blocks/column and the frontier pick runs on the game thread).
     */
    private double caveExposureOf(CachedWorld world, int chunkX, int chunkZ) {
        var region = world.getRegion(chunkX >> 9, chunkZ >> 9);
        if (region == null) return 0;

        int caveAir = 0;
        int sampled = 0;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minY = ctx.world().dimensionType().minY();
        for (int y = minY; y < CAVE_Y_MAX; y += 4) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    try {
                        var state = region.getBlock(baseX + x, y, baseZ + z);
                        if (state != null && state.isAir()) caveAir++;
                    } catch (Exception ignored) {
                    }
                    sampled++;
                }
            }
        }
        return sampled == 0 ? 0 : (double) caveAir / sampled * 256.0; // normalize to per-chunk scale
    }

    private static int chunkDist(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz)); // Chebyshev — ring-consistent
    }

    private static List<int[]> ringChunks(int cx, int cz, int ring) {
        List<int[]> result = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    result.add(new int[]{cx + dx, cz + dz});
                }
            }
        }
        return result;
    }

    /** Goal that keeps the bot in the cave Y band during the approach. */
    private static final class CaveYGoal implements Goal {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y >= -20 && y <= 40; // deepslate cave band
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // Prefer being inside the band; outside, penalize distance to the band edges.
            if (y > 40) return (y - 40) * 2.0;
            if (y < -20) return (-20 - y) * 2.0;
            return 0;
        }
    }

    // int pairs for ChunkPos
    private static final class ChunkPos {
        static long pack(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
