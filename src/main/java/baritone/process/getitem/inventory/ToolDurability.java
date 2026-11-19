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

package baritone.process.getitem.inventory;

/**
 * Tool & durability manager (plan A44): a tool is planned with only while it has {@code minRemaining} uses left;
 * Mending tools are kept for repair once below {@code mendingFraction} instead of being worn out, so the planner
 * crafts a replacement instead.
 */
public final class ToolDurability {
    private ToolDurability() {
    }

    public static boolean usable(int damage, int maxDamage, boolean mending, int minRemaining, double mendingFraction) {
        if (maxDamage <= 0) return true;
        int left = maxDamage - damage;
        if (left <= minRemaining) return false;
        return !mending || (double) left / maxDamage >= mendingFraction;
    }
}
