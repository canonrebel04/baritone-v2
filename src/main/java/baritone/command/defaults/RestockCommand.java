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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RestockCommand extends Command {

    public RestockCommand(IBaritone baritone) {
        super(baritone, "restock");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Item item = args.getDatatypeFor(ItemById.INSTANCE);
        args.requireMax(0);
        if (!baritone.getRestockProcess().restock(item)) {
            throw new CommandInvalidStateException("Could not restock " + item.getDefaultInstance().getHoverName().getString());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
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
        return "Restocks an item from a shulker box in your inventory";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Places a shulker box from your inventory that contains the requested item, pulls stacks out, and picks the shulker back up.",
                "",
                "Usage:",
                "> restock <item> - Restocks the given item"
        );
    }
}
