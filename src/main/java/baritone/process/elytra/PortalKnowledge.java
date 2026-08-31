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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nether portal knowledge (elytra roadmap item 2b). Tracks the positions of nether portals
 * observed while baritone is active — chunk packing emits portal blocks into this static
 * registry (the same side-product pattern as the highway ice index in
 * {@link NetherPathfinderContext}; see also {@code ChunkPacker#pack}) — and persists them
 * per dimension to {@code <dimension>-portals.json} in the baritone world directory.
 * <p>
 * Every recorded portal stores its in-dimension position plus its overworld projection
 * ({@code overworldX}/{@code overworldZ} via the /8 coordinate mapping), so a portal pair's
 * two sides can be matched against trip legs in either dimension without having visited both
 * sides. {@link #findShortcut} implements the trip-leg shortcut query used by
 * {@code ElytraProcess} when it starts or advances a multi-leg trip.
 * <p>
 * This class is deliberately free of Minecraft types (plain ints and strings) so the
 * discovery/shortcut logic is unit-testable without a game instance.
 */
public final class PortalKnowledge {

    /** Dimension identifier path ({@code ResourceLocation#getPath}) of the nether. */
    public static final String NETHER = "the_nether";
    /** Dimension identifier path of the overworld. */
    public static final String OVERWORLD = "overworld";

    /**
     * Maximum number of portals tracked per dimension. When the cap is hit, new portals are
     * ignored (the registry is a cache of observed portals, not a guarantee).
     */
    private static final int MAX_PORTALS_PER_DIMENSION = 1024;
    /**
     * Two portal blocks closer than this (horizontal, blocks) are considered the same portal
     * frame — a portal's nether portal blocks span at most 21 blocks but practically 2-4.
     */
    private static final int DEDUP_RADIUS = 8;
    /** A shortcut's portal must be within this distance (blocks) of the leg's start. */
    public static final int PORTAL_NEAR_START = 64;
    /**
     * A shortcut's portal overworld projection must be within this distance (overworld
     * blocks) of the leg target's overworld projection.
     */
    public static final int TARGET_NEAR_PORTAL = 128;
    /** Portals are persisted at most this often (ms) while being discovered. */
    private static final long SAVE_INTERVAL_MS = 5_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One known nether portal. Coordinates are world coordinates in {@link #side}. */
    public static final class Portal {
        public int x;
        public int y;
        public int z;
        /** Overworld X/Z projection of this portal (x*8 for nether-side records, x otherwise). */
        public int overworldX;
        public int overworldZ;
        /** Dimension identifier path this record was made in ({@link #NETHER} or {@link #OVERWORLD}). */
        public String side;

        public Portal() {}

        Portal(int x, int y, int z, int overworldX, int overworldZ, String side) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.overworldX = overworldX;
            this.overworldZ = overworldZ;
            this.side = side;
        }

        /** The nether-side X of this portal (its overworld projection divided by 8). */
        public int netherX() {
            return Math.floorDiv(this.overworldX, 8);
        }

        /** The nether-side Z of this portal (its overworld projection divided by 8). */
        public int netherZ() {
            return Math.floorDiv(this.overworldZ, 8);
        }
    }

    private static final class SavedPortals {
        List<Portal> portals = new ArrayList<>();
    }

    private static final class DimData {
        final List<Portal> portals = new ArrayList<>();
        boolean loaded;
        boolean dirty;
    }

    /** Per-dimension registry, keyed by dimension identifier path. */
    private static final Map<String, DimData> REGISTRY = new HashMap<>();
    /** Baritone world directory, synced by ElytraProcess while a world is joined. */
    private static volatile Path worldDir;
    private static volatile long lastSaveMs;

    private PortalKnowledge() {}

    /**
     * Records a nether portal block observed during chunk packing. Deduplicates nearby blocks
     * of the same portal frame, applies the /8 overworld projection and schedules a
     * (throttled) save. Safe to call from chunk packing worker threads.
     *
     * @param dimensionId dimension identifier path of the observed position
     * @param x           world X of the nether portal block
     * @param y           world Y of the nether portal block
     * @param z           world Z of the nether portal block
     */
    public static void recordPortal(String dimensionId, int x, int y, int z) {
        final DimData data;
        synchronized (REGISTRY) {
            data = data(dimensionId);
            lazyLoad(dimensionId, data);
            for (Portal known : data.portals) {
                int dx = known.x - x;
                int dz = known.z - z;
                if (dx * dx + dz * dz <= DEDUP_RADIUS * DEDUP_RADIUS) {
                    return; // same portal frame, already known
                }
            }
            if (data.portals.size() >= MAX_PORTALS_PER_DIMENSION) {
                return; // registry full
            }
            final boolean nether = NETHER.equals(dimensionId);
            data.portals.add(new Portal(
                    x, y, z,
                    nether ? x * 8 : x,
                    nether ? z * 8 : z,
                    dimensionId));
            data.dirty = true;
        }
        scheduleSave();
    }

    /**
     * @return a copy of the portals known in the given dimension (empty if none)
     */
    public static List<Portal> known(String dimensionId) {
        synchronized (REGISTRY) {
            return new ArrayList<>(data(dimensionId).portals);
        }
    }

    /**
     * Shortcut query for a trip leg (elytra roadmap item 2b). Finds a known portal that is
     * near the leg's start AND whose other side is near the leg's target — via the /8
     * coordinate mapping: in the nether, the portal's overworld projection is compared to the
     * target's overworld projection (target*8); in the overworld, the portal's nether side is
     * compared to the target's nether projection (target/8). Unknown dimensions never match.
     *
     * @param dimensionId dimension the leg starts in
     * @param fromX       leg start X
     * @param fromZ       leg start Z
     * @param targetX     leg target X (in the same dimension as the start)
     * @param targetZ     leg target Z
     * @return the best (nearest to the start) matching portal, or {@code null}
     */
    public static Portal findShortcut(String dimensionId, int fromX, int fromZ, int targetX, int targetZ) {
        final boolean nether = NETHER.equals(dimensionId);
        if (!nether && !OVERWORLD.equals(dimensionId)) {
            return null;
        }
        synchronized (REGISTRY) {
            Portal best = null;
            int bestDistSq = Integer.MAX_VALUE;
            for (Portal portal : data(dimensionId).portals) {
                int dx = portal.x - fromX;
                int dz = portal.z - fromZ;
                int distSq = dx * dx + dz * dz;
                if (distSq > PORTAL_NEAR_START * PORTAL_NEAR_START) {
                    continue; // portal not near the leg's start
                }
                int otherX;
                int otherZ;
                if (nether) {
                    otherX = portal.overworldX - targetX * 8;
                    otherZ = portal.overworldZ - targetZ * 8;
                } else {
                    otherX = portal.netherX() - Math.floorDiv(targetX, 8);
                    otherZ = portal.netherZ() - Math.floorDiv(targetZ, 8);
                }
                if (Math.abs(otherX) > TARGET_NEAR_PORTAL || Math.abs(otherZ) > TARGET_NEAR_PORTAL) {
                    continue; // portal's other side is not near the leg's target
                }
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = portal;
                }
            }
            return best;
        }
    }

    /**
     * Syncs the baritone world directory (called while a world is joined, e.g. from
     * ElytraProcess' tick hook) and lazily loads the given dimension's portal file.
     */
    public static void syncWorldDirectory(Path directory, String dimensionId) {
        synchronized (REGISTRY) {
            worldDir = directory;
            lazyLoad(dimensionId, data(dimensionId));
        }
    }

    /** Saves every loaded, dirty dimension's portal file. Called on world unload. */
    public static void saveAll() {
        synchronized (REGISTRY) {
            for (Map.Entry<String, DimData> entry : REGISTRY.entrySet()) {
                save(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Test hook: clears the registry and world directory state. */
    static void resetForTest() {
        synchronized (REGISTRY) {
            REGISTRY.clear();
            worldDir = null;
            lastSaveMs = 0;
        }
    }

    private static DimData data(String dimensionId) {
        return REGISTRY.computeIfAbsent(dimensionId, k -> new DimData());
    }

    private static void lazyLoad(String dimensionId, DimData data) {
        if (data.loaded) {
            return;
        }
        data.loaded = true;
        final Path dir = worldDir;
        if (dir == null) {
            return; // no world directory known yet; discovery still works in-memory
        }
        try {
            final Path file = dir.resolve(dimensionId + "-portals.json");
            if (!Files.exists(file)) {
                return;
            }
            final SavedPortals saved = GSON.fromJson(Files.readString(file), SavedPortals.class);
            if (saved != null && saved.portals != null) {
                data.portals.addAll(saved.portals);
            }
        } catch (Exception e) {
            // malformed portal file: start fresh rather than refusing to discover
        }
    }

    private static void scheduleSave() {
        final long now = System.currentTimeMillis();
        synchronized (REGISTRY) {
            if (worldDir == null || now - lastSaveMs < SAVE_INTERVAL_MS) {
                return; // no persistence dir known (or throttled); a later record or world unload will persist
            }
            lastSaveMs = now;
        }
        try {
            Baritone.getExecutor().execute(PortalKnowledge::saveAll);
        } catch (Throwable t) {
            // no executor (e.g. tests): persistence happens on the next syncWorldDirectory/saveAll
        }
    }

    private static void save(String dimensionId, DimData data) {
        if (!data.dirty) {
            return;
        }
        final Path dir = worldDir;
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            final SavedPortals saved = new SavedPortals();
            saved.portals.addAll(data.portals);
            Files.writeString(dir.resolve(dimensionId + "-portals.json"), GSON.toJson(saved));
            data.dirty = false;
        } catch (Exception e) {
            // keep dirty so a later save retries
        }
    }
}
