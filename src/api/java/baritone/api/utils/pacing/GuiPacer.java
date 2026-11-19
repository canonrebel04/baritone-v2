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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Stateful click pacer over {@link GuiPacing} (plan S40/S41). Call {@link #ready()} (or {@link #ready(int, int)}
 * with the slot about to be clicked) once per tick; it returns true when a click may be sent now. All pacers share
 * one budget so two automations (e.g. Get Item and regear) can never click in the same tick.
 */
public final class GuiPacer {
    private static final AtomicLong GLOBAL_TICK = new AtomicLong();
    private static volatile long lastGlobalClickTick = Long.MIN_VALUE;

    private final Supplier<Pace> pace;
    private final Supplier<Double> lag;
    private int wait;
    private long clicks;
    private int lastSlot = -1;
    /** Optional fixed interval override (ticks, >= 0), e.g. Baritone's getItemClickDelayTicks. */
    private Supplier<Integer> override = () -> -1;

    public GuiPacer() {
        this(Pace::current, () -> LagModel.global().factor());
    }

    public GuiPacer(Supplier<Pace> pace, Supplier<Double> lag) {
        this.pace = pace;
        this.lag = lag;
    }

    public GuiPacer withOverride(Supplier<Integer> ticks) {
        this.override = ticks;
        return this;
    }

    /** Advance the shared tick counter (Baritone calls this once per client tick). */
    public static void globalTick() {
        GLOBAL_TICK.incrementAndGet();
    }

    public boolean ready() {
        return ready(-1, 9);
    }

    /** @param slot the slot about to be clicked (for cursor travel), -1 if unknown; @param columns grid width */
    public boolean ready(int slot, int columns) {
        if (wait > 0) {
            wait--;
            return false;
        }
        long now = GLOBAL_TICK.get();
        if (now == lastGlobalClickTick) return false; // another pacer clicked this tick
        double dist = slot >= 0 && lastSlot >= 0 ? GuiPacing.slotDistance(lastSlot, slot, columns) : 0;
        Integer fixed = override.get();
        int interval = fixed != null && fixed >= 0 ? Math.max(1, fixed) : GuiPacing.clickInterval(pace.get(), dist, clicks, lag.get());
        clicks++;
        wait = interval - 1;
        if (slot >= 0) lastSlot = slot;
        lastGlobalClickTick = now;
        return true;
    }

    /** Delay the next click (e.g. after opening a screen). */
    public void delay(int ticks) {
        wait = Math.max(wait, ticks);
    }

    /** S41: wait the "read" time for a freshly opened container with {@code stacks} stacks. */
    public void opened(int stacks) {
        lastSlot = -1;
        delay(GuiPacing.openReadDelay(pace.get(), stacks, lag.get()));
    }

    public int closeDelay() {
        return GuiPacing.closeDelay(pace.get(), lag.get());
    }

    public Pace pace() {
        return pace.get();
    }
}
