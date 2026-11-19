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

package baritone;

import baritone.api.utils.smooth.PathSpline;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** S66: path spline keeps endpoints, passes through nodes, stays near the chain and rounds corners. */
public class PathSplineTest {
    @Test
    public void smoothsACorner() {
        List<double[]> nodes = Arrays.asList(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{2, 0, 0}, new double[]{2, 0, 1}, new double[]{2, 0, 2});
        List<double[]> pts = PathSpline.smooth(nodes, 4);
        assertEquals(4 * 4 + 1, pts.size());
        assertArrayEquals(nodes.get(0), pts.get(0), 1e-9);
        assertArrayEquals(nodes.get(4), pts.get(pts.size() - 1), 1e-9);
        // passes through every node
        for (int i = 0; i < nodes.size() - 1; i++) assertArrayEquals(nodes.get(i), pts.get(i * 4), 1e-9);
        // stays near the chain (small bulge allowed)
        for (double[] p : pts) assertTrue(Arrays.toString(p), p[0] <= 2.2 && p[2] >= -0.2 && p[0] >= -1e-9);
        // the corner (node 2 = index 8) is rounded: the curve leaves it diagonally, not along a single axis
        double[] after = pts.get(9);
        assertTrue(Math.abs(after[0] - 2) > 1e-3 && after[2] > 1e-3);
    }

    @Test
    public void shortChainsUnchanged() {
        List<double[]> nodes = Arrays.asList(new double[]{0, 0, 0}, new double[]{1, 1, 0});
        assertEquals(2, PathSpline.smooth(nodes, 4).size());
        // duplicate nodes don't blow up
        List<double[]> dup = Arrays.asList(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{1, 0, 0});
        for (double[] p : PathSpline.smooth(dup, 4)) for (double v : p) assertFalse(Double.isNaN(v));
    }
}
