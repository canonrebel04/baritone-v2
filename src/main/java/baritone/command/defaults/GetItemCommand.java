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
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.process.IGetItemProcess;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** {@code #get} (plan A9): obtain items by mining, crafting and smelting, or explain the plan without running it. */
public class GetItemCommand extends Command {

    public GetItemCommand(IBaritone baritone) {
        super(baritone, "get", "getitem");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        IGetItemProcess proc = baritone.getGetItemProcess();
        String first = args.hasAny() ? args.getString() : "status";
        switch (first.toLowerCase()) {
            case "status":
                args.requireMax(0);
                logDirect(proc.status());
                return;
            case "cancel":
            case "stop":
                args.requireMax(0);
                proc.cancel();
                logDirect("Get Item cancelled");
                return;
            case "why":
                args.requireMax(0);
                proc.why().forEach(this::logDirect);
                return;
            case "pause":
                args.requireMax(0);
                proc.pause();
                logDirect("Get Item paused (#get resume to continue)");
                return;
            case "resume":
                args.requireMax(0);
                proc.resume();
                logDirect("Get Item resumed");
                return;
            case "where":
            case "trades": {
                String item = args.getString();
                args.requireMax(0);
                List<String> lines = proc.where(item);
                if (first.equalsIgnoreCase("trades")) lines = lines.stream().filter(l -> l.startsWith("Trade: ")).toList();
                if (lines.isEmpty()) logDirect("No known trades for " + item);
                lines.forEach(this::logDirect);
                return;
            }
            case "knowledge":
                args.requireMax(0);
                proc.knowledge().forEach(this::logDirect);
                return;
            case "plan": {
                String item = args.getString();
                int amount = args.hasAny() ? args.getAs(Integer.class) : 1;
                args.requireMax(0);
                proc.plan(item, amount).forEach(this::logDirect);
                return;
            }
            default: {
                int amount = args.hasAny() ? args.getAs(Integer.class) : 1;
                args.requireMax(0);
                String err = proc.get(first, amount);
                if (err != null) {
                    throw new CommandInvalidStateException("Can't get " + first + ": " + err);
                }
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString();
            return new TabCompleteHelper()
                    .append("plan", "status", "cancel", "why", "pause", "resume", "where", "trades", "knowledge")
                    .append(BuiltInRegistries.ITEM.keySet().stream().map(Object::toString))
                    .append(BuiltInRegistries.ITEM.getTags().map(t -> "#" + t.key().location()))
                    .filterPrefixNamespaced(prefix)
                    .sortAlphabetically()
                    .stream();
        }
        if (args.hasExactly(2) && (args.peekString().equalsIgnoreCase("plan") || args.peekString().equalsIgnoreCase("where") || args.peekString().equalsIgnoreCase("trades"))) {
            args.get();
            return new TabCompleteHelper()
                    .append(BuiltInRegistries.ITEM.keySet().stream().map(Object::toString))
                    .filterPrefixNamespaced(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Get items by mining, crafting and smelting";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Plans the cheapest way to have the requested amount of an item (or any item of a tag) and does it:",
                "mines with the right tool tier, places/uses crafting tables and furnaces, crafts and smelts with paced clicks,",
                "re-planning from your inventory after every step. Takes items from your own chests when that's quicker,",
                "travels to the Nether/End through known portals, eats, flees mobs and keeps inventory space.",
                "",
                "Usage:",
                "> get <item|#tag> [amount] - Get items (total amount to have, default 1)",
                "> get plan <item|#tag> [amount] - Show the plan, ETA and risks without doing anything",
                "> get status - Current goal and step",
                "> get why - Last plan / failure explained",
                "> get pause / resume - Pause and continue the current goal",
                "> get where <item> - Where the item is known to be (your chests, villagers, ore depth)",
                "> get trades <item> - Remembered villager trades selling it",
                "> get knowledge - What Get Item remembers about this world",
                "> get cancel - Stop",
                "",
                "Your chests are used automatically (getItemUseContainers). Containers named with getItemProtectChestNames",
                "(\"protect\", \"private\", \"keep\", \"nobot\", ...) or listed in getItemProtectChestPositions are never",
                "touched; getItemProtectItems are never taken, used or stashed. Get Item waits while you run #goto/#mine/..."
        );
    }
}
