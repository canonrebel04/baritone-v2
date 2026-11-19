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

package baritone.behavior.look;

/**
 * Seeded 1D gradient (Perlin) noise for bounded, temporally correlated micro-corrections (plan S5).
 *
 * <p>Unlike white jitter (a new random offset every tick), consecutive samples are smoothly related, so the
 * camera drifts like a resting hand instead of vibrating. Output is in {@code [-1, 1]} and C1 continuous.
 * Allocation free after construction.
 */
public final class CorrelatedNoise {

    private static final int SIZE = 256;
    private static final int MASK = SIZE - 1;

    private final double[] gradients = new double[SIZE];
    private final int[] perm = new int[SIZE * 2];

    public CorrelatedNoise(long seed) {
        long s = seed ^ 0x9E3779B97F4A7C15L;
        int[] p = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            s = mix(s);
            gradients[i] = ((s >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
            p[i] = i;
        }
        for (int i = SIZE - 1; i > 0; i--) {
            s = mix(s);
            int j = (int) ((s >>> 33) % (i + 1));
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < SIZE * 2; i++) {
            perm[i] = p[i & MASK];
        }
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Samples the noise at {@code x} (units: lattice cells; one cell is roughly one "wobble").
     *
     * @return a value in {@code [-1, 1]}
     */
    public double sample(double x) {
        int xi = (int) Math.floor(x);
        double xf = x - xi;
        double g0 = gradients[perm[xi & MASK]];
        double g1 = gradients[perm[(xi + 1) & MASK]];
        double d0 = g0 * xf;
        double d1 = g1 * (xf - 1.0);
        double fade = xf * xf * xf * (xf * (xf * 6.0 - 15.0) + 10.0);
        // 1D gradient noise peaks at |0.5|; scale to [-1, 1] and clamp for safety
        double v = (d0 + fade * (d1 - d0)) * 2.0;
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
