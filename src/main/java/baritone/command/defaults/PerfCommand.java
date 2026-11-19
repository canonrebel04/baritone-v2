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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.perf.TickTelemetry;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** S93: {@code #perf} prints per-process tick times (p50/p95/p99, max, budget, degraded); {@code #perf reset}. */
public class PerfCommand extends Command {

    public PerfCommand(IBaritone baritone) {
        super(baritone, "perf");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny()) {
            String sub = args.getString().toLowerCase();
            if (sub.equals("reset")) {
                TickTelemetry.resetAll();
                logDirect("Tick telemetry cleared.");
                return;
            }
            logDirect("Usage: #perf [reset]");
            return;
        }
        if (!Baritone.settings().perfTelemetry.value) logDirect("perfTelemetry is off; turn it on to collect timings.");
        List<TickTelemetry> all = TickTelemetry.all();
        if (all.isEmpty()) logDirect("No process has run yet.");
        for (TickTelemetry t : all) if (t.count() > 0) logDirect(t.summary());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return args.hasExactlyOne() ? Stream.of("reset") : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Per-process tick timings (p50/p95/p99)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Shows how long each Baritone process takes per tick, and whether it is over perfBudgetMs (then it",
                "lowers its own quality while perfAutoDegrade is on).",
                "",
                "Usage:",
                "> perf - show timings",
                "> perf reset - clear them"
        );
    }
}
