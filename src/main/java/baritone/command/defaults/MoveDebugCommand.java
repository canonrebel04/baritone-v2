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
import baritone.pathing.movement.sim.MovementTelemetry;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Enhancement #49: Debug telemetry & adaptive movement tuning command ({@code #movedebug}).
 */
public class MoveDebugCommand extends Command {

    public MoveDebugCommand(IBaritone baritone) {
        super(baritone, "movedebug");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            boolean next = !Baritone.settings().movementDebugTelemetry.value;
            Baritone.settings().movementDebugTelemetry.value = next;
            logDirect("Movement debug telemetry " + (next ? "enabled" : "disabled") + ".");
            logDirect(MovementTelemetry.formatSummary());
            return;
        }
        String sub = args.getString().toLowerCase();
        switch (sub) {
            case "on", "true" -> {
                Baritone.settings().movementDebugTelemetry.value = true;
                logDirect("Movement debug telemetry enabled.");
            }
            case "off", "false" -> {
                Baritone.settings().movementDebugTelemetry.value = false;
                logDirect("Movement debug telemetry disabled.");
            }
            case "stats", "status" -> logDirect(MovementTelemetry.formatSummary());
            case "reset" -> {
                MovementTelemetry.reset();
                logDirect("Cleared movement failure memory and adaptive tuning stats.");
            }
            default -> logDirect("Usage: #movedebug [on|off|stats|reset]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("on", "off", "stats", "reset");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Toggle movement telemetry or inspect/reset adaptive movement stats";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Toggles movement trajectory deviation telemetry and inspects or resets",
                "session failure memory and adaptive per-movement cost multipliers.",
                "",
                "Usage:",
                "> movedebug - Toggle telemetry and print current stats",
                "> movedebug stats - Print adaptive tuning & failure stats",
                "> movedebug reset - Clear failure memory and adaptive stats"
        );
    }
}
