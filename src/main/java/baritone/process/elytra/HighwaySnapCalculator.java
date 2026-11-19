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
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure 2b2t 8-axis &amp; ring-road highway snap planner.
 * <p>
 * Given a start {@code (sx, sz)} and goal {@code (gx, gz)} in Nether coordinates, projects
 * onto the 8 major highways ({@code X=0}, {@code Z=0}, {@code |X|=|Z|}) and optional ring roads
 * ({@code max(|X|, |Z|) = R}) to produce a multi-leg {@code (x, z)} waypoint chain that
 * minimizes off-highway travel.
 */
public final class HighwaySnapCalculator {

    public static final int[] DEFAULT_RING_ROADS = {500, 1000, 1500, 2500, 5000, 25000};

    private HighwaySnapCalculator() {}

    public record Waypoint(int x, int z) {}

    /**
     * Plans a multi-leg highway route from {@code (sx, sz)} to {@code (gx, gz)}.
     * Returns at least {@code [Waypoint(gx, gz)]}; when snapping saves off-highway
     * distance, returns {@code [entry, (optional transfer), exit, goal]}.
     */
    public static List<Waypoint> planRoute(int sx, int sz, int gx, int gz, int[] ringRoads) {
        double directDist = Math.hypot(gx - (double) sx, gz - (double) sz);
        if (directDist < 128.0) {
            return List.of(new Waypoint(gx, gz));
        }

        List<Waypoint> bestRoute = List.of(new Waypoint(gx, gz));
        double bestOffHighway = directDist;

        // Candidate 1: snap onto the nearest of the 8 axes from start, fly along axis/origin, exit to goal
        for (int axis = 0; axis < 8; axis++) {
            Waypoint entry = projectToAxis(sx, sz, axis);
            Waypoint exit = projectToAxis(gx, gz, axis);
            double offDist = Math.hypot(entry.x - sx, entry.z - sz) + Math.hypot(gx - exit.x, gz - exit.z);
            if (offDist + 64.0 < bestOffHighway) {
                bestOffHighway = offDist;
                bestRoute = compact(List.of(entry, exit, new Waypoint(gx, gz)), sx, sz);
            }
        }

        // Candidate 2: enter best axis for start, transfer via origin or ring road to best axis for goal
        Waypoint bestStartEntry = nearestAxisPoint(sx, sz);
        Waypoint bestGoalExit = nearestAxisPoint(gx, gz);
        double twoAxisOff = Math.hypot(bestStartEntry.x - sx, bestStartEntry.z - sz)
                + Math.hypot(gx - bestGoalExit.x, gz - bestGoalExit.z);
        if (twoAxisOff + 128.0 < directDist) {
            int entryAxis = axisOf(bestStartEntry);
            int exitAxis = axisOf(bestGoalExit);
            if (entryAxis != exitAxis) {
                // Transfer via nearest ring road or origin (0, 0)
                Waypoint bestTransferStart = new Waypoint(0, 0);
                Waypoint bestTransferEnd = new Waypoint(0, 0);
                double bestTotal = twoAxisOff
                        + Math.hypot(bestStartEntry.x, bestStartEntry.z)
                        + Math.hypot(bestGoalExit.x, bestGoalExit.z);

                if (ringRoads != null) {
                    for (int r : ringRoads) {
                        Waypoint rStart = pointOnAxisAtRing(entryAxis, r);
                        Waypoint rEnd = pointOnAxisAtRing(exitAxis, r);
                        double total = twoAxisOff
                                + Math.hypot(rStart.x - bestStartEntry.x, rStart.z - bestStartEntry.z)
                                + ringDistance(rStart, rEnd, r)
                                + Math.hypot(bestGoalExit.x - rEnd.x, bestGoalExit.z - rEnd.z);
                        if (total < bestTotal) {
                            bestTotal = total;
                            bestTransferStart = rStart;
                            bestTransferEnd = rEnd;
                        }
                    }
                }
                if (twoAxisOff + 64.0 < bestOffHighway) {
                    List<Waypoint> raw = new ArrayList<>();
                    raw.add(bestStartEntry);
                    raw.add(bestTransferStart);
                    raw.add(bestTransferEnd);
                    raw.add(bestGoalExit);
                    raw.add(new Waypoint(gx, gz));
                    bestRoute = compact(raw, sx, sz);
                }
            }
        }

        return bestRoute;
    }

    static Waypoint projectToAxis(int x, int z, int axis) {
        return switch (axis) {
            case 0 -> new Waypoint(Math.max(0, x), 0); // +X
            case 1 -> new Waypoint(Math.min(0, x), 0); // -X
            case 2 -> new Waypoint(0, Math.max(0, z)); // +Z
            case 3 -> new Waypoint(0, Math.min(0, z)); // -Z
            case 4 -> { int d = Math.max(0, (x + z) / 2); yield new Waypoint(d, d); } // +X/+Z
            case 5 -> { int d = Math.max(0, (x - z) / 2); yield new Waypoint(d, -d); } // +X/-Z
            case 6 -> { int d = Math.max(0, (-x + z) / 2); yield new Waypoint(-d, d); } // -X/+Z
            default -> { int d = Math.max(0, (-x - z) / 2); yield new Waypoint(-d, -d); } // -X/-Z
        };
    }

    private static Waypoint nearestAxisPoint(int x, int z) {
        Waypoint best = projectToAxis(x, z, 0);
        double bestDist = Math.hypot(best.x - x, best.z - z);
        for (int a = 1; a < 8; a++) {
            Waypoint candidate = projectToAxis(x, z, a);
            double d = Math.hypot(candidate.x - x, candidate.z - z);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }

    private static int axisOf(Waypoint p) {
        if (p.z == 0 && p.x >= 0) return 0;
        if (p.z == 0) return 1;
        if (p.x == 0 && p.z >= 0) return 2;
        if (p.x == 0) return 3;
        if (p.x > 0 && p.z > 0) return 4;
        if (p.x > 0) return 5;
        if (p.z > 0) return 6;
        return 7;
    }

    private static Waypoint pointOnAxisAtRing(int axis, int ring) {
        return switch (axis) {
            case 0 -> new Waypoint(ring, 0);
            case 1 -> new Waypoint(-ring, 0);
            case 2 -> new Waypoint(0, ring);
            case 3 -> new Waypoint(0, -ring);
            case 4 -> new Waypoint(ring, ring);
            case 5 -> new Waypoint(ring, -ring);
            case 6 -> new Waypoint(-ring, ring);
            default -> new Waypoint(-ring, -ring);
        };
    }

    private static double ringDistance(Waypoint a, Waypoint b, int ring) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        // Approximate perimeter traversal along the square ring
        return Math.min(8.0 * ring - (Math.abs(dx) + Math.abs(dz)), Math.abs(dx) + Math.abs(dz));
    }

    private static List<Waypoint> compact(List<Waypoint> input, int sx, int sz) {
        List<Waypoint> out = new ArrayList<>();
        int prevX = sx;
        int prevZ = sz;
        for (Waypoint wp : input) {
            if (Math.hypot(wp.x - prevX, wp.z - prevZ) >= 16.0) {
                out.add(wp);
                prevX = wp.x;
                prevZ = wp.z;
            }
        }
        if (out.isEmpty()) {
            out.add(input.get(input.size() - 1));
        }
        return out;
    }
}
