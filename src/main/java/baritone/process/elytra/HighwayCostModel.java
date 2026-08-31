/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone. If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

/**
 * Highway (ice road) cost model for elytra path-finding, roadmap item 2 part 1.
 * <p>
 * Nether ice highways (player-built blue ice roads, packed ice patches) are dramatically
 * faster to fly along than open terrain because they sit in wide, flat, obstacle-free
 * corridors. The pathfinder therefore discounts nodes whose floor is ice:
 * <ul>
 *   <li>packed ice floor &rarr; node cost &times; {@code packedMultiplier} (default 0.5)</li>
 *   <li>blue ice floor &rarr; node cost &times; {@code blueMultiplier} (default 0.35)</li>
 * </ul>
 * A discount requires <b>continuity</b>: at least 2 of the last 4 nodes must also have had an
 * ice floor. A single isolated ice patch (a lone frozen pool, glacier overhang) is not a
 * highway and must not bias the path. This class keeps the rolling per-search state.
 * <p>
 * The model is intentionally free of Minecraft imports so it can be unit-tested in isolation;
 * {@link NetherPathfinderContext} supplies the per-node floor kind by reading the ice index
 * built from already-packed chunk data (no extra chunk loads in the A* hot path).
 *
 * @author Brady
 */
public final class HighwayCostModel {

    /** No ice floor below the node. */
    public static final int FLOOR_NONE = 0;
    /** Packed ice floor below the node. */
    public static final int FLOOR_PACKED_ICE = 1;
    /** Blue ice floor below the node. */
    public static final int FLOOR_BLUE_ICE = 2;

    /** Floor probes reach this many blocks below a node; nearest block wins. */
    public static final int FLOOR_PROBE_DEPTH = 4;

    /** History window for the continuity gate ("2 of the last 4 nodes"). */
    private static final int CONTINUITY_WINDOW = 4;
    /** Ice-floor nodes required within the window before the multiplier applies. */
    private static final int CONTINUITY_THRESHOLD = 2;

    private final double packedMultiplier;
    private final double blueMultiplier;

    /**
     * Rolling ice-floor history of the most recently evaluated nodes,
     * {@code history[0]} = most recent. Not yet filled slots are {@code false}.
     */
    private final boolean[] history = new boolean[CONTINUITY_WINDOW];
    private int filled;

    public HighwayCostModel(double packedMultiplier, double blueMultiplier) {
        this.packedMultiplier = packedMultiplier;
        this.blueMultiplier = blueMultiplier;
    }

    /**
     * Resets the rolling continuity state. Call at the start of each path search
     * (and whenever evaluation jumps to an unrelated part of the graph).
     */
    public void reset() {
        for (int i = 0; i < this.history.length; i++) {
            this.history[i] = false;
        }
        this.filled = 0;
    }

    /**
     * Cost multiplier for a node whose floor kind is {@code floorKind}, advancing the rolling
     * continuity state. The continuity gate is evaluated over the <b>previous</b> nodes only,
     * so the first (at most one) ice node of a stretch is always charged full price.
     *
     * @param floorKind one of {@link #FLOOR_NONE}, {@link #FLOOR_PACKED_ICE}, {@link #FLOOR_BLUE_ICE}
     * @return the multiplier to apply to the node's base cost (&ge; 0; 1.0 when no discount)
     */
    public double nodeCostMultiplier(int floorKind) {
        final boolean ice = floorKind != FLOOR_NONE;
        double multiplier = 1.0;
        if (ice && this.recentIceCount() >= CONTINUITY_THRESHOLD) {
            multiplier = floorKind == FLOOR_BLUE_ICE ? this.blueMultiplier : this.packedMultiplier;
        }
        this.record(ice);
        return multiplier;
    }

    /**
     * Number of ice-floor nodes among the most recent (up to) {@value #CONTINUITY_WINDOW}
     * evaluated nodes, <b>not including</b> the node currently being evaluated.
     */
    public int recentIceCount() {
        int count = 0;
        final int window = Math.min(this.filled, CONTINUITY_WINDOW);
        for (int i = 0; i < window; i++) {
            if (this.history[i]) {
                count++;
            }
        }
        return count;
    }

    private void record(boolean ice) {
        // shift right: drop the oldest entry once the window is full
        for (int i = Math.min(this.filled, CONTINUITY_WINDOW - 1); i > 0; i--) {
            this.history[i] = this.history[i - 1];
        }
        this.history[0] = ice;
        if (this.filled < CONTINUITY_WINDOW) {
            this.filled++;
        }
    }

    /**
     * Combines floor probes taken at increasing depth below a node (y-1 up to y-4) into a
     * single floor kind: the nearest ice block wins (it is the surface the corridor sits on).
     *
     * @param probes floor kinds probed at y-1, y-2, y-3, y-4 (in that order)
     * @return the effective floor kind, {@link #FLOOR_NONE} if no probe found ice
     */
    public static int floorKindFromProbes(int... probes) {
        for (int kind : probes) {
            if (kind != FLOOR_NONE) {
                return kind;
            }
        }
        return FLOOR_NONE;
    }
}
