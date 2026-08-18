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

package baritone.process.elytra;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;

/**
 * @author Brady
 */
public final class BlockStateOctreeInterface {

    private final NetherPathfinderContext context;
    private final long contextPtr;
    transient long chunkPtr;

    // Guarantee that the first lookup will fetch the context by setting MAX_VALUE
    private int prevChunkX = Integer.MAX_VALUE;
    private int prevChunkZ = Integer.MAX_VALUE;

    public BlockStateOctreeInterface(final NetherPathfinderContext context) {
        this.context = context;
        this.contextPtr = context.context;
    }

    public boolean get0(final int x, final int y, final int z) {
        if ((y | (127 - y)) < 0) {
            return false;
        }
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        if (this.chunkPtr == 0 | ((chunkX ^ this.prevChunkX) | (chunkZ ^ this.prevChunkZ)) != 0) {
            this.prevChunkX = chunkX;
            this.prevChunkZ = chunkZ;
            // Read-only lookup: returns the existing chunk, or a default chunk with the given
            // solidity if missing (solid=false = air, matching the old getOrCreateChunk empty
            // chunk). Unlike allocateAndInsertChunk this does NOT mutate the native cache, so
            // it is safe to call under the context read lock while culling (write lock) runs.
            this.chunkPtr = NetherPathfinder.getChunkOrDefault(this.contextPtr, chunkX, chunkZ, false);
        }
        return Octree.getBlock(this.chunkPtr, x & 0xF, y & 0x7F, z & 0xF);
    }
}
