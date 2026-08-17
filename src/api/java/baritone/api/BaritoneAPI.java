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

package baritone.api;

import baritone.api.behavior.look.ILookPriorityHub;
import baritone.api.utils.SettingsUtil;

/**
 * Exposes the {@link IBaritoneProvider} instance and the {@link Settings} instance for API usage.
 *
 * @author Brady
 * @since 9/23/2018
 */
public final class BaritoneAPI {

    private static final IBaritoneProvider provider;
    private static final Settings settings;

    static {
        settings = new Settings();
        SettingsUtil.readAndApply(settings, SettingsUtil.SETTINGS_DEFAULT_NAME);

        try {
            provider = (IBaritoneProvider) Class.forName("baritone.BaritoneProvider").newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static IBaritoneProvider getProvider() {
        return BaritoneAPI.provider;
    }

    public static Settings getSettings() {
        return BaritoneAPI.settings;
    }

    /**
     * Returns the {@link ILookPriorityHub} for the primary Baritone instance.
     * This provides a clean API for external mods (e.g. Meteor Client) to submit prioritized rotation requests.
     *
     * @return The look priority hub of the primary Baritone instance
     */
    public static ILookPriorityHub getLookPriorityHub() {
        return provider.getPrimaryBaritone().getLookBehavior().getPriorityHub();
    }

    /**
     * Returns the {@link ILookPriorityHub} for the specified {@link IBaritone} instance.
     *
     * @param baritone The Baritone instance
     * @return The look priority hub of the given Baritone instance
     */
    public static ILookPriorityHub getLookPriorityHub(IBaritone baritone) {
        return baritone.getLookBehavior().getPriorityHub();
    }
}
