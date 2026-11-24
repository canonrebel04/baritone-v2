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

package baritone.process.getitem.advanced;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A25 leftover: vanilla enchanting-table bookshelf power. Offsets and the air-gap rule match
 * {@link EnchantingTableBlock}; the table caps at 15 shelves.
 */
public final class BookshelfPower {
    public static final int MAX = 15;

    private BookshelfPower() {
    }

    /** Relative shelf positions: a 2-block ring at y and y+1 around the table ({@code abs(x)==2 || abs(z)==2}). */
    public static List<BlockPos> offsets() {
        List<BlockPos> out = new ArrayList<>(32);
        for (int y = 0; y <= 1; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) == 2 || Math.abs(z) == 2) out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    /** The block that must transmit power (air in vanilla) between the table and a shelf at {@code offset}. */
    public static BlockPos gap(BlockPos table, BlockPos offset) {
        return table.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2);
    }

    /**
     * Count shelves around {@code table}, capped at {@link #MAX}. {@code provider} is the shelf cell;
     * {@code transmitter} is the gap cell.
     */
    public static int count(Predicate<BlockPos> provider, Predicate<BlockPos> transmitter, BlockPos table) {
        int n = 0;
        for (BlockPos off : offsets()) {
            if (provider.test(table.offset(off)) && transmitter.test(gap(table, off))) n++;
        }
        return Math.min(MAX, n);
    }

    /** Live count in the world, using vanilla tags and {@link EnchantingTableBlock#isValidBookShelf}. */
    public static int count(Level level, BlockPos table) {
        if (level == null || table == null) return 0;
        int n = 0;
        for (BlockPos off : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, table, off)) n++;
        }
        return Math.min(MAX, n);
    }
}
