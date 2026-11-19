/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class PortalCommand extends Command {

    public PortalCommand(IBaritone baritone) {
        super(baritone, "portal");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int x = args.getAs(Integer.class);
        int z = args.getAs(Integer.class);
        args.requireMax(0);
        ((Baritone) baritone).portalRouterProcess.routeToOverworld(x, z);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Route to Overworld coordinates via Nether portals";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Uses PortalKnowledge to route into the Nether, travel at 8x compression, and exit to the target Overworld coordinates.",
                "",
                "Usage:",
                "> portal <x> <z> - Route to Overworld (x, z)"
        );
    }
}
