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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;

/** Executes one plan step (plan 4.2 executor). The process re-plans from the real inventory after each step. */
public abstract class StepTask {
    protected final Baritone baritone;
    protected final IPlayerContext ctx;
    protected final GameData data;
    public final PlanStep step;
    protected int ticks;
    private boolean done;
    private String failure;

    protected StepTask(Baritone baritone, GameData data, PlanStep step) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.data = data;
        this.step = step;
    }

    public final PathingCommand tick() {
        ticks++;
        if (done || failure != null) return pause();
        return onTick();
    }

    protected abstract PathingCommand onTick();

    /** Release anything held (screens, mining). Called once when the task ends or is abandoned. */
    public void stop() {
    }

    public abstract String status();

    protected final PathingCommand finish() {
        done = true;
        return pause();
    }

    protected final PathingCommand fail(String why) {
        failure = why;
        return pause();
    }

    public boolean isDone() {
        return done;
    }

    public String failure() {
        return failure;
    }

    protected static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    protected static PathingCommand defer() {
        return new PathingCommand(null, PathingCommandType.DEFER);
    }
}
