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

package baritone.api.utils.pacing;

import java.util.Locale;

/**
 * Interaction pacing tier shared by Baritone and Meteor (plan S38-S41, S50). Follows the look profile:
 * LEGIT_SMOOTH (default) -> {@link #LEGIT}, BALANCED -> {@link #BALANCED}, FAST (opt-in) -> {@link #FAST}.
 */
public enum Pace {
    LEGIT, BALANCED, FAST;

    public static Pace fromProfile(String lookProfile) {
        if (lookProfile == null) return LEGIT;
        switch (lookProfile.trim().toUpperCase(Locale.ROOT)) {
            case "FAST":
            case "LEGACY":
                return FAST;
            case "BALANCED":
                return BALANCED;
            default:
                return LEGIT;
        }
    }

    /** The pace of Baritone's current {@code lookProfile} setting. */
    public static Pace current() {
        try {
            return fromProfile(baritone.api.BaritoneAPI.getSettings().lookProfile.value);
        } catch (Throwable t) {
            return LEGIT; // settings not loaded (tests)
        }
    }
}
