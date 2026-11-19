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

package baritone.api.utils;

import java.util.function.LongToDoubleFunction;

/**
 * Lock-free holder for the live combat favoring cost-multiplier supplier shared between
 * {@link baritone.api.BaritoneAPI} and path calculation without forcing eager provider initialization.
 */
public final class CombatFavoringStorage {

    private static volatile LongToDoubleFunction supplier;

    private CombatFavoringStorage() {}

    public static LongToDoubleFunction get() {
        return supplier;
    }

    public static void set(LongToDoubleFunction next) {
        supplier = next;
    }
}
