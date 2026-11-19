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

package baritone.process.getitem.tasks;

import baritone.Baritone;
import baritone.api.utils.pacing.GuiPacing;
import baritone.api.utils.pacing.Jitter;
import baritone.api.utils.pacing.LagModel;
import baritone.api.utils.pacing.Pace;
import baritone.behavior.look.LookProfile;

/**
 * Get Item's click pacer: the shared {@link baritone.api.utils.pacing.GuiPacer} layer (plan S40/S41: Fitts
 * cursor travel, jitter, open-read and close delays, server-lag stretch, one click per tick across all
 * automations) with the {@code getItemClickDelayTicks} override.
 */
public final class GuiPacer {
    private final baritone.api.utils.pacing.GuiPacer shared = new baritone.api.utils.pacing.GuiPacer(
            () -> Pace.fromProfile(Baritone.settings().lookProfile.value), () -> LagModel.global().factor())
            .withOverride(() -> Baritone.settings().getItemClickDelayTicks.value);

    /** Base/jitter for a profile (pure, for tests). */
    static int[] profileDelay(LookProfile p) {
        return GuiPacing.base(Pace.fromProfile(p.name()));
    }

    /** Interval before click number {@code n}, given base and jitter (pure, deterministic). */
    static int interval(int base, int jitter, long n) {
        return Math.max(1, base + Jitter.of(n, 17, jitter));
    }

    /** Call once per tick; true when a click may be sent now (and starts the next interval). */
    public boolean ready() {
        return shared.ready();
    }

    /** As {@link #ready()}, with the slot about to be clicked so cursor travel time is included. */
    public boolean ready(int slot, int columns) {
        return shared.ready(slot, columns);
    }

    /** Delay the next click (e.g. after opening a screen, a "reaction" pause). */
    public void delay(int ticks) {
        shared.delay(ticks);
    }

    /** S41: a container just opened with this many stacks in view. */
    public void opened(int stacks) {
        shared.opened(stacks);
    }

    public int closeDelay() {
        return shared.closeDelay();
    }
}
