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

package baritone.utils.pathing;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.Collections;
import java.util.List;

@baritone.KeepName
public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;

    private List<Avoidance> avoidances;

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context);
        // avoidance spheres are evaluated lazily per-node during pathing instead of
        // being rasterized into the map here, which used to cost ~5k-36k map writes
        // per sphere synchronously on the render thread
        this.avoidances = Avoidance.create(ctx);
        Helper.HELPER.logDebug("Favoring size: " + favorings.size() + ", avoidances: " + avoidances.size());
    }

    public Favoring(IPath previous, CalculationContext context) { // create one just from previous path, no mob avoidances
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        this.avoidances = Collections.emptyList();
        double coeff = context.backtrackCostFavoringCoefficient;
        if (coeff != 1D && previous != null) {
            previous.positions().forEach(pos -> favorings.put(BetterBlockPos.longHash(pos), coeff));
        }
    }

    @baritone.KeepName
    public static java.util.function.LongToDoubleFunction combatFavoringSupplier = null;

    public boolean isEmpty() {
        return favorings.isEmpty() && avoidances.isEmpty() && combatFavoringSupplier == null;
    }

    public double calculate(int x, int y, int z, long hash) {
        double val = favorings.get(hash);
        if (combatFavoringSupplier != null) {
            val *= combatFavoringSupplier.applyAsDouble(hash);
        }
        if (!avoidances.isEmpty()) {
            for (Avoidance avoidance : avoidances) {
                val *= avoidance.coefficient(x, y, z);
            }
        }
        return val;
    }
}
