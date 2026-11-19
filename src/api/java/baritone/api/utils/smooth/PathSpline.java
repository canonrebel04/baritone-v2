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

package baritone.api.utils.smooth;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan S66: centripetal Catmull-Rom smoothing of a path's node chain for rendering (no cusps on sharp corners,
 * endpoints kept). Points are {x, y, z}. Pure.
 */
public final class PathSpline {
    private PathSpline() {
    }

    public static List<double[]> smooth(List<double[]> points, int segmentsPerSpan) {
        List<double[]> out = new ArrayList<>();
        int n = points.size();
        if (n < 3 || segmentsPerSpan <= 1) {
            for (double[] p : points) out.add(p.clone());
            return out;
        }
        for (int i = 0; i < n - 1; i++) {
            double[] p0 = points.get(Math.max(0, i - 1));
            double[] p1 = points.get(i);
            double[] p2 = points.get(i + 1);
            double[] p3 = points.get(Math.min(n - 1, i + 2));
            double t1 = knot(p0, p1);
            double t2 = t1 + knot(p1, p2);
            double t3 = t2 + knot(p2, p3);
            for (int s = 0; s < segmentsPerSpan; s++) {
                double t = t1 + (t2 - t1) * s / segmentsPerSpan;
                out.add(point(p0, p1, p2, p3, 0, t1, t2, t3, t));
            }
        }
        out.add(points.get(n - 1).clone());
        return out;
    }

    private static double knot(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.max(Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)), 1e-4);
    }

    private static double[] point(double[] p0, double[] p1, double[] p2, double[] p3, double t0, double t1, double t2, double t3, double t) {
        double[] r = new double[3];
        for (int k = 0; k < 3; k++) {
            double a1 = (t1 - t) / (t1 - t0) * p0[k] + (t - t0) / (t1 - t0) * p1[k];
            double a2 = (t2 - t) / (t2 - t1) * p1[k] + (t - t1) / (t2 - t1) * p2[k];
            double a3 = (t3 - t) / (t3 - t2) * p2[k] + (t - t2) / (t3 - t2) * p3[k];
            double b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2;
            double b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3;
            r[k] = (t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2;
        }
        return r;
    }
}
