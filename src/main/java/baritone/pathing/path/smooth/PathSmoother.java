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

package baritone.pathing.path.smooth;

/**
 * Pure 2D path smoothing (plan S16/S17/S20). Pure-pursuit with string pulling over the upcoming flat node
 * centers: the steering "carrot" sits a speed-scaled distance ahead along the path polyline, then is pulled back
 * until the straight line player -> carrot passes within {@link #TOLERANCE} of every node it skips past, so a
 * node (and the block beside an inside corner) is never cut.
 */
public final class PathSmoother {
    /** Max distance (blocks) the steering line may pass from a node center: 0.2 + 0.3 hitbox stays inside 0.5. */
    public static final double TOLERANCE = 0.2;

    private PathSmoother() {
    }

    /** S20: horizontal lookahead distance for a horizontal speed in blocks/tick. */
    public static double lookahead(double speed) {
        return Math.max(1.2, Math.min(3.5, 0.9 + speed * 6.0));
    }

    /**
     * @param px    player x
     * @param pz    player z
     * @param nodes upcoming node centers {x0, z0, x1, z1, ...}; node 0 is the current movement's destination
     * @param L     lookahead distance
     * @return the steering target {x, z}
     */
    public static double[] carrot(double px, double pz, double[] nodes, double L) {
        int n = nodes.length / 2;
        if (n == 0) return new double[]{px, pz};
        double[] best = {nodes[0], nodes[1]};
        double lo = 0, hi = L;
        // the carrot can't be closer than node 0 (we must still head for it)
        double d0 = Math.hypot(nodes[0] - px, nodes[1] - pz);
        if (L <= d0) return pointAt(px, pz, nodes, L);
        lo = d0;
        if (valid(px, pz, nodes, L)) return pointAt(px, pz, nodes, L);
        for (int i = 0; i < 14; i++) {
            double mid = (lo + hi) / 2;
            if (valid(px, pz, nodes, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double[] p = pointAt(px, pz, nodes, lo);
        return p == null ? best : p;
    }

    /** Point at arc length {@code s} along player -> node0 -> node1 ... (clamped to the last node). */
    static double[] pointAt(double px, double pz, double[] nodes, double s) {
        double ax = px, az = pz;
        for (int i = 0; i < nodes.length / 2; i++) {
            double bx = nodes[2 * i], bz = nodes[2 * i + 1];
            double len = Math.hypot(bx - ax, bz - az);
            if (s <= len && len > 1e-9) {
                double t = s / len;
                return new double[]{ax + (bx - ax) * t, az + (bz - az) * t};
            }
            s -= len;
            ax = bx;
            az = bz;
        }
        return new double[]{ax, az};
    }

    /** Every node passed before arc length {@code s} lies within tolerance of the segment player -> carrot. */
    static boolean valid(double px, double pz, double[] nodes, double s) {
        double[] c = pointAt(px, pz, nodes, s);
        double acc = 0, ax = px, az = pz;
        for (int i = 0; i < nodes.length / 2; i++) {
            double bx = nodes[2 * i], bz = nodes[2 * i + 1];
            acc += Math.hypot(bx - ax, bz - az);
            if (acc >= s - 1e-9) break;
            if (segmentDistance(px, pz, c[0], c[1], bx, bz) > TOLERANCE) return false;
            ax = bx;
            az = bz;
        }
        return true;
    }

    public static double segmentDistance(double ax, double az, double bx, double bz, double qx, double qz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((qx - ax) * dx + (qz - az) * dz) / len2));
        return Math.hypot(ax + dx * t - qx, az + dz * t - qz);
    }

    /**
     * S18 speed profile: arc distance from the player to the first node where the path turns by more than
     * {@code minTurnDeg}, or +infinity if none in view.
     */
    public static double distanceToTurn(double px, double pz, double[] nodes, double minTurnDeg) {
        int n = nodes.length / 2;
        double acc = Math.hypot(nodes.length > 0 ? nodes[0] - px : 0, nodes.length > 0 ? nodes[1] - pz : 0);
        double prevX = px, prevZ = pz;
        for (int i = 0; i + 1 < n; i++) {
            double cx = nodes[2 * i], cz = nodes[2 * i + 1];
            double nx = nodes[2 * i + 2], nz = nodes[2 * i + 3];
            double in = Math.atan2(cz - prevZ, cx - prevX);
            double out = Math.atan2(nz - cz, nx - cx);
            double turn = Math.abs(Math.toDegrees(Math.atan2(Math.sin(out - in), Math.cos(out - in))));
            if (turn > minTurnDeg && Math.hypot(cx - prevX, cz - prevZ) > 1e-6) return acc;
            acc += Math.hypot(nx - cx, nz - cz);
            prevX = cx;
            prevZ = cz;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** S18: let go of sprint when a sharp turn is within roughly braking distance at the current speed. */
    public static boolean releaseSprintForTurn(double distToTurn, double speed) {
        return distToTurn < 0.4 + speed * 7.0;
    }

    /** S19: sprint hysteresis. Returns the new latch: off above 28 degrees of heading error, on below 18. */
    public static boolean sprintLatch(boolean latched, float yawErrorDeg) {
        if (yawErrorDeg > 28f) return false;
        if (yawErrorDeg < 18f) return true;
        return latched;
    }

    /**
     * S34 crowd steering: which way to drift around entities standing ahead. Returns +1 to strafe to the
     * right of the heading, -1 left, 0 none.
     *
     * @param hx      unit heading x
     * @param hz      unit heading z
     * @param others  entity positions relative to the player {dx0, dz0, dx1, dz1, ...}
     */
    public static int crowdSide(double hx, double hz, double[] others) {
        double rx = -hz, rz = hx; // right-hand perpendicular (Minecraft: +x east, +z south, yaw-based)
        double bestAhead = Double.POSITIVE_INFINITY;
        int side = 0;
        for (int i = 0; i + 1 < others.length; i += 2) {
            double ahead = others[i] * hx + others[i + 1] * hz;
            double lateral = others[i] * rx + others[i + 1] * rz;
            if (ahead > 0.3 && ahead < 2.5 && Math.abs(lateral) < 0.8 && ahead < bestAhead) {
                bestAhead = ahead;
                side = lateral >= 0 ? -1 : 1; // entity on our right -> go left
            }
        }
        return side;
    }
}
