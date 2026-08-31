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

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Brady
 */
public final class NetherPathfinderContext implements IElytraPathFinder {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    private static final int SECTION_SIZE = 16;
    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    public final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    public final ReentrantReadWriteLock.ReadLock readLock = rwl.readLock();
    public final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();
    private final int maxHeight;

    // Visible for access in BlockStateOctreeInterface
    final long context;
    private final long seed;
    // Native dimension constant (DIMENSION_NETHER / _END / _OVERWORLD)
    private final int dimension;
    final int minY;
    private final BlockStateOctreeInterface boi;

    // --- Phase 2 threading (plan item 28) ---
    // The native lib does NO internal synchronization: reads (raytrace, passable, octree
    // lookups, non-generating pathfinds) must hold the read lock; mutations (chunk packing,
    // block updates, culling, seed-generation pathfinds) must hold the write lock.

    // writeExecutor: mutations only (packing, block updates, culling, generating pathfinds).
    // packExecutor: chunk packing with nearest-first priority + bounded queue (plan item 9).
    // readExecutor: non-generating pathfinds (read-only, never blocks on a pack flood).
    private final ExecutorService writeExecutor;
    private final ExecutorService packExecutor;
    private final ExecutorService readExecutor;

    // --- Bounded, prioritized pack queue (plan item 9) ---
    // repackChunks floods 81x81 chunks; a FIFO would pack far-away chunks before the flight
    // corridor. Sort by distance to the player (updated each tick), drop the farthest when
    // over the cap so memory stays flat on long flights.
    private static final int MAX_PENDING_PACKS = 4096;
    private final TreeSet<PackTask> pendingPacks = new TreeSet<>(Comparator.comparingInt(PackTask::distance));
    private final Map<Long, PackTask> pendingByKey = new HashMap<>();
    private volatile BlockPos playerPosForPacking = new BlockPos(0, 0, 0);

    // --- Deterministic segment cache (plan item 19) ---
    // Bounded LRU keyed by (src, dst, generate, atLeastX4, refine). Re-plans after a
    // setback/recalc return the SAME path (stable flight) and re-#elytra is instant.
    private static final int PATH_CACHE_MAX = 64;
    private final LinkedHashMap<PathKey, UnpackedSegment> pathCache = new LinkedHashMap<>(PATH_CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PathKey, UnpackedSegment> eldest) {
            return size() > PATH_CACHE_MAX;
        }
    };

    public NetherPathfinderContext(long seed, Path cache, Level world) {
        final ResourceKey<Level> dimensionKey = world.dimension();
        this.dimension = dimensionKey == Level.NETHER ? NetherPathfinder.DIMENSION_NETHER
                : dimensionKey == Level.END ? NetherPathfinder.DIMENSION_END
                : NetherPathfinder.DIMENSION_OVERWORLD;
        this.minY = world.dimensionType().minY();
        int height = Math.min(world.dimensionType().height(), 384);
        if (!Baritone.settings().elytraAllowAboveRoof.value && this.dimension == NetherPathfinder.DIMENSION_NETHER) {
            height = Math.min(height, 128);
        }
        this.maxHeight = height;
        this.context = NetherPathfinder.newContext(seed, cache != null ? cache.toString() : null, this.dimension, height, Baritone.settings().elytraCustomAllocator.value);
        this.seed = seed;
        this.boi = new BlockStateOctreeInterface(this);
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.packExecutor = Executors.newSingleThreadExecutor();
        this.readExecutor = Executors.newSingleThreadExecutor();
        // Pack thread: drain the priority queue continuously, packing nearest-first.
        this.packExecutor.execute(this::drainPackQueue);
    }

    /** Called by ElytraBehavior each tick so pack priority tracks the player. */
    public void updatePlayerPosForPacking(BlockPos feet) {
        this.playerPosForPacking = feet;
    }

    private void drainPackQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            final PackTask task;
            synchronized (pendingPacks) {
                task = pendingPacks.pollFirst();
                if (task != null) pendingByKey.remove(task.key);
            }
            if (task == null) {
                try {
                    Thread.sleep(1); // idle; wait for more packs
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            this.writeLock.lock();
            try {
                final LevelChunk chunk = task.ref.get();
                if (chunk != null) {
                    // we might free this chunk
                    this.boi.chunkPtr = 0L;
                    long ptr = NetherPathfinder.allocateAndInsertChunk(this.context, task.chunkX, task.chunkZ);
                    writeChunkData(chunk, ptr);
                    NetherPathfinder.setChunkState(this.context, task.chunkX, task.chunkZ, true);
                }
            } finally {
                this.writeLock.unlock();
            }
        }
    }

    public boolean hasChunk(ChunkPos pos) {
        return NetherPathfinder.hasChunkFromJava(this.context, pos.x(), pos.z());
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks, BlockStateOctreeInterface boi) {
        this.writeExecutor.execute(() -> {
            this.writeLock.lock();
            try {
                boi.chunkPtr = 0L;
                this.boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            } finally {
                this.writeLock.unlock();
            }
        });
    }

    /**
     * Queues a chunk for packing with nearest-first priority. Bounded: when the pending
     * queue exceeds {@link #MAX_PENDING_PACKS}, the farthest pending chunk is dropped —
     * it will be re-queued by chunk events when the player approaches it.
     */
    public void queueForPacking(final LevelChunk chunkIn) {
        final int chunkX = chunkIn.getPos().x();
        final int chunkZ = chunkIn.getPos().z();
        final long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

        synchronized (pendingPacks) {
            if (pendingByKey.containsKey(key)) return; // already queued
            final BlockPos feet = this.playerPosForPacking;
            final int dist = (chunkX * 16 - feet.getX()) * (chunkX * 16 - feet.getX())
                    + (chunkZ * 16 - feet.getZ()) * (chunkZ * 16 - feet.getZ());
            final PackTask task = new PackTask(key, chunkX, chunkZ, dist, new SoftReference<>(chunkIn));
            pendingPacks.add(task);
            pendingByKey.put(key, task);

            while (pendingPacks.size() > MAX_PENDING_PACKS) {
                final PackTask farthest = pendingPacks.pollLast();
                if (farthest == null) break;
                pendingByKey.remove(farthest.key);
            }
        }
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.writeExecutor.execute(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            // not inserting or deleting from the cache hashmap but it would still be bad for this function to race with itself
            this.writeLock.lock();
            try {
                long ptr = NetherPathfinder.getChunk(this.context, chunkPos.x(), chunkPos.z());
                if (ptr == 0) return; // this shouldn't ever happen
                event.getBlocks().forEach(pair -> {
                    BlockPos pos = pair.first().below(minY);
                    if (pos.getY() < 0 || pos.getY() >= this.maxHeight) return;
                    boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                    Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
                });
            } finally {
                this.writeLock.unlock();
            }
        });
    }

    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        final BlockPos adjustedSrc = src.below(minY);
        final BlockPos adjustedDst = dst.below(minY);
        // Only generate terrain from the seed in the nether — the native generator is a
        // nether world-gen port; other dimensions must treat unloaded chunks as air.
        final boolean generate = Baritone.settings().elytraPredictTerrain.value && this.dimension == NetherPathfinder.DIMENSION_NETHER;
        final boolean atLeastX4 = !Baritone.settings().elytraAllowTightSpaces.value;
        final boolean refine = Baritone.settings().elytraRefinePath.value;

        // Deterministic segment cache: identical (src, dst, settings) returns the cached path.
        final PathKey key = new PathKey(src, dst, generate, atLeastX4, refine);
        synchronized (this.pathCache) {
            final UnpackedSegment cached = this.pathCache.get(key);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }

        // Generating pathfinds mutate the chunk cache (seed terrain) -> write lock on the
        // write executor; non-generating pathfinds only read -> read lock on the read executor.
        final Lock lock = generate ? this.writeLock : this.readLock;
        final ExecutorService exec = generate ? this.writeExecutor : this.readExecutor;
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                final PathSegment segment = NetherPathfinder.pathFind(
                        this.context,
                        adjustedSrc.getX(), adjustedSrc.getY(), adjustedSrc.getZ(),
                        adjustedDst.getX(), adjustedDst.getY(), adjustedDst.getZ(),
                        atLeastX4, // require >=4 block clearance unless tight spaces allowed
                        refine, // refine pass smooths the node string
                        10000, // timeoutMs
                        !generate, // useAirIfChunkNotLoaded
                        // Cost per node traversed through a chunk the native lib hasn't observed —
                        // makes A* prefer known/loaded routes over blind leaps into unloaded terrain.
                        8.0 // fakeChunkCost
                );
                if (segment == null) {
                    throw new PathCalculationException("Path calculation failed");
                }

                final UnpackedSegment unpacked = new UnpackedSegment(
                        UnpackedSegment.from(segment).collect().stream().map(pos -> pos.above(minY)),
                        segment.finished);
                synchronized (this.pathCache) {
                    this.pathCache.put(key, unpacked);
                }
                return unpacked;
            } finally {
                lock.unlock();
            }
        }, exec);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        final double adjustedStartY = startY - this.minY;
        final double adjustedEndY = endY - this.minY;
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, startX, adjustedStartY, startZ, endX, adjustedEndY, endZ);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        final Vec3 adjustedStart = start.subtract(0, this.minY, 0);
        final Vec3 adjustedEnd = end.subtract(0, this.minY, 0);
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, adjustedStart.x, adjustedStart.y, adjustedStart.z, adjustedEnd.x, adjustedEnd.y, adjustedEnd.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        switch (visibility) {
            case Visibility.ALL:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
            case Visibility.NONE:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) == -1;
            case Visibility.ANY:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) != -1;
            default:
                throw new IllegalArgumentException("lol");
        }
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
    }

    public boolean passable(int x, int y, int z) {
        return !this.boi.get0(x, y, z);
    }

    public void cancel() {
        NetherPathfinder.cancel(this.context);
    }

    public void destroy() {
        this.cancel();
        // Ignore anything that was queued up, just shutdown the executors
        this.writeExecutor.shutdownNow();
        this.readExecutor.shutdownNow();
        this.packExecutor.shutdownNow();

        try {
            while (!this.writeExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
            while (!this.readExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
            while (!this.packExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        NetherPathfinder.freeContext(this.context);
    }

    /** Chunk-pack queue entry, ordered by distance from the player (nearest first). */
    private static final class PackTask {
        final long key;      // ChunkPos.asLong
        final int chunkX, chunkZ;
        final int distance;  // squared distance from player block pos
        final SoftReference<LevelChunk> ref;

        PackTask(long key, int chunkX, int chunkZ, int distance, SoftReference<LevelChunk> ref) {
            this.key = key;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.distance = distance;
            this.ref = ref;
        }

        int distance() {
            return this.distance;
        }
    }

    /** Path-cache key: source/dest block positions + the pathing settings that affect the result. */
    private static final class PathKey {
        final int sx, sy, sz, dx, dy, dz;
        final boolean generate, atLeastX4, refine;

        PathKey(BlockPos src, BlockPos dst, boolean generate, boolean atLeastX4, boolean refine) {
            this.sx = src.getX(); this.sy = src.getY(); this.sz = src.getZ();
            this.dx = dst.getX(); this.dy = dst.getY(); this.dz = dst.getZ();
            this.generate = generate; this.atLeastX4 = atLeastX4; this.refine = refine;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PathKey)) return false;
            PathKey k = (PathKey) o;
            return sx == k.sx && sy == k.sy && sz == k.sz
                    && dx == k.dx && dy == k.dy && dz == k.dz
                    && generate == k.generate && atLeastX4 == k.atLeastX4 && refine == k.refine;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sx, sy, sz, dx, dy, dz, generate, atLeastX4, refine);
        }
    }

    public long getSeed() {
        return this.seed;
    }

    public void acquireReadLock() {
        this.readLock.lock();
    }

    public boolean tryAcquireReadLock() {
        return this.readLock.tryLock();
    }

    public void releaseReadLock() {
        this.readLock.unlock();
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    private static void writeChunkData(LevelChunk chunk, long chunkPtr) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            final int maxSections = Math.min(chunkInternalStorageArray.length, 24); // pathfinder support stops at 384/16 sections
            for (int y0 = 0; y0 < maxSections; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null || extendedblockstorage.hasOnlyAir()) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                var palette = iPalettedContainer.getPalette();
                // Mushrooms spawn on the roof and writing them as solid will cause pages to be unnecessarily allocated.
                // idFor can't be used because it may update the palette
                int airId = -1;
                int caveAirId = -1;
                int redMushroomId = -1;
                int brownMushroomId = -1;
                for (int i = 0; i < palette.getSize(); i++) {
                    BlockState bs = palette.valueFor(i);
                    if (bs == Blocks.AIR.defaultBlockState()) airId = i;
                    else if (bs == Blocks.CAVE_AIR.defaultBlockState()) caveAirId = i;
                    else if (bs == Blocks.RED_MUSHROOM.defaultBlockState()) redMushroomId = i;
                    else if (bs == Blocks.BROWN_MUSHROOM.defaultBlockState()) brownMushroomId = i;
                }
                if (airId == -1 & caveAirId == -1) {
                    final long bytesInSection = SECTION_SIZE / 8;
                    UNSAFE.setMemory(chunkPtr + (y0 * bytesInSection), bytesInSection, (byte) 0xFF);
                    continue;
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);

                        // Avoid unnecessary writes that may trigger a page allocation
                        if (!(value == airId | value == caveAirId) & value != redMushroomId & value != brownMushroomId) {
                            Octree.setBlock(chunkPtr, x, y, z, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public static final class Visibility {
        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;
        private Visibility() {}
    }
}
