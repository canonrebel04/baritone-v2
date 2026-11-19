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

package baritone.process.getitem.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.Reader;
import java.io.Writer;
import java.util.*;

/**
 * Persisted Get Item world knowledge (plan A19): containers, stations, villagers, farms, hazards and a pending
 * goal (A50 resume), one file per world/server, versioned and bounded by each part's limit.
 */
public final class KnowledgeBase {
    public static final int VERSION = 1;

    public final ContainerIndex containers = new ContainerIndex();
    public final StationRegistry stations = new StationRegistry();
    public final VillagerMemory villagers = new VillagerMemory();
    public final FarmRegistry farms = new FarmRegistry();
    public final HazardMap hazards = new HazardMap();
    /** Goal to resume after a disconnect: item/tag and amount, or null. */
    public String pendingGoal;
    public int pendingAmount;
    private boolean dirty;

    /** JSON shape. */
    static final class Snapshot {
        int version = VERSION;
        List<ContainerIndex.Container> containers = new ArrayList<>();
        List<StationRegistry.Station> stations = new ArrayList<>();
        List<VillagerMemory.Villager> villagers = new ArrayList<>();
        List<FarmRegistry.Farm> farms = new ArrayList<>();
        List<HazardMap.Hazard> hazards = new ArrayList<>();
        String pendingGoal;
        int pendingAmount;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public void markDirty() {
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void write(Writer w) {
        Snapshot s = new Snapshot();
        s.containers = containers.all();
        s.stations = stations.all();
        s.villagers = villagers.all();
        s.farms = farms.all();
        List<HazardMap.Hazard> hz = new ArrayList<>();
        for (HazardMap.Hazard h : hazards.all()) if (h.type != HazardMap.Type.MOBS) hz.add(h); // mob spots are transient
        s.hazards = hz;
        s.pendingGoal = pendingGoal;
        s.pendingAmount = pendingAmount;
        GSON.toJson(s, w);
        dirty = false;
    }

    /** Loads a snapshot; unknown future versions and malformed files are ignored (returns false). */
    public boolean read(Reader r) {
        Snapshot s;
        try {
            s = GSON.fromJson(r, Snapshot.class);
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
        if (s == null || s.version > VERSION) return false;
        if (s.containers != null) for (ContainerIndex.Container c : s.containers) if (c != null && c.items != null) containers.record(c);
        if (s.stations != null) for (StationRegistry.Station st : s.stations) if (st != null && st.block != null) {
            if (st.placedByUs) stations.markPlaced(st.block, st.x, st.y, st.z, st.dim, st.seen);
            else stations.record(st.block, st.x, st.y, st.z, st.dim, st.seen);
        }
        if (s.villagers != null) for (VillagerMemory.Villager v : s.villagers) if (v != null && v.uuid != null) {
            if (v.offers == null) v.offers = new ArrayList<>();
            villagers.record(v);
        }
        if (s.farms != null) farms.replaceAll(filterNull(s.farms));
        if (s.hazards != null) hazards.replaceAll(filterNull(s.hazards));
        pendingGoal = s.pendingGoal;
        pendingAmount = s.pendingAmount;
        dirty = false;
        return true;
    }

    private static <T> List<T> filterNull(List<T> in) {
        List<T> out = new ArrayList<>();
        for (T t : in) if (t != null) out.add(t);
        return out;
    }

    /** File name for a world/server key ("mc.example.org:25565", "My World"). */
    public static String fileName(String key) {
        String k = key == null || key.isBlank() ? "unknown" : key.toLowerCase(Locale.ROOT);
        return k.replaceAll("[^a-z0-9._-]+", "_") + ".json";
    }
}
