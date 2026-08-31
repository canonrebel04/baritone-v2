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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;
import baritone.api.process.IBaritoneProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * ExynAI-style depth-aware frontier exploration command.
 */
public class FrontierCommand extends Command {

    public FrontierCommand(IBaritone baritone) {
        super(baritone, "frontier");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        var process = ((baritone.Baritone) baritone).frontierExplorerProcess;

        if (args.hasAny() && args.peekString().equalsIgnoreCase("stop")) {
            args.requireExactly(1);
            process.stop();
            logDirect("Frontier exploration stopped.");
            return;
        }

        int radius = -1;
        if (args.hasAny()) {
            args.requireExactly(1);
            try {
                radius = Math.max(1, args.getAsOrDefault(Integer.class, -1));
            } catch (CommandNotEnoughArgumentsException ignored) {
            }
        }
        process.explore(radius);
        logDirect(radius > 0
                ? "Frontier exploration started (radius " + radius + " chunks, cave-aware)."
                : "Frontier exploration started (unbounded, cave-aware).");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        try {
            if (args.hasExactlyOne() && args.peekString().startsWith("s")) {
                return Stream.of("stop");
            }
        } catch (CommandNotEnoughArgumentsException ignored) {
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "ExynAI-style 3D-aware exploration";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Depth-aware frontier exploration: discovers unexplored chunks weighted by",
                "cave exposure — frontiers adjacent to known cave systems are prioritized,",
                "and the approach paths enter the cave band instead of walking the surface.",
                "",
                "Usage:",
                "> frontier - explore outward from your position, unbounded",
                "> frontier <radius> - explore within <radius> chunks of your position",
                "> frontier stop - stop exploring"
        );
    }
}
