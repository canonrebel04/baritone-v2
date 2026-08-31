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
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone. If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Fleet takeoff coordination (elytra roadmap item 4).
 * <p>
 * Before taking off, this posts a {@code takeoff} note to the agent message bus and then
 * reads back the recent bus log looking for <b>other</b> bots' takeoff notes whose
 * start&rarr;destination lane intersects ours (2D segment-segment distance below the
 * configured lane separation, same dimension, note younger than
 * {@link #NOTE_FRESHNESS_MS}). If a conflict is found, takeoff is held: the check is
 * retried every {@link #HOLD_RETRY_MS} for at most {@link #MAX_HOLDS} rounds, then the
 * takeoff proceeds anyway (fail-open).
 * <p>
 * Threading contract: <b>nothing here ever blocks the game tick</b>. The game thread only
 * touches volatile state ({@link #shouldHold()} and {@link #consumeLogMessage()}); all
 * HTTP happens on a single daemon executor with 2-second timeouts. Any bus error is
 * fail-open (take off normally).
 * <p>
 * Wire format (agent message bus):
 * <ul>
 *   <li>POST {@code /post} {@code {from, to: "*", type: "note", body}}
 *       with body {@code takeoff|<dim>|<fromX>,<fromZ>|<toX>,<toZ>|<headingDeg>} on takeoff
 *       intent and {@code landed|<dim>} on trip completion</li>
 *   <li>GET {@code /log?limit=20} returns {@code {messages: [{from, to, type, body, ts}, ...]}}
 *       where {@code ts} is epoch seconds</li>
 * </ul>
 */
public class FleetTakeoffCoordination {

    /** A takeoff note older than this is ignored when looking for lane conflicts. */
    public static final long NOTE_FRESHNESS_MS = 60_000L;
    /** How long a takeoff hold lasts before the lane is re-checked. */
    public static final long HOLD_RETRY_MS = 30_000L;
    /** Maximum number of {@value #HOLD_RETRY_MS} hold rounds before we take off anyway. */
    public static final int MAX_HOLDS = 3;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Gson GSON = new Gson();

    private final String busUrl;
    private final double laneSeparation;
    /**
     * Resolves this bot's preferred identity (the player name). May return {@code null},
     * in which case {@code elytra-<dim>} is used for the flight in question.
     */
    private final Supplier<String> preferredName;
    private final ExecutorService executor;
    private final HttpClient http;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    // Current flight segment; written on the game thread, read on the executor thread.
    private volatile String flightDim;
    private volatile double fromX;
    private volatile double fromZ;
    private volatile double toX;
    private volatile double toZ;
    private volatile boolean flightActive;
    private volatile long lastCheckMs;

    // Hold state; written on the executor thread (and read/cleared on the game thread).
    private volatile boolean holding;
    private volatile long holdStartMs;
    private volatile String pendingLog;
    /** Resolved identity used as the bus {@code from} field for the current flight. */
    private volatile String selfName;

    public FleetTakeoffCoordination(String busUrl, double laneSeparation, Supplier<String> preferredName) {
        this.busUrl = (busUrl == null || busUrl.isEmpty()) ? "http://127.0.0.1:3877" : busUrl;
        this.laneSeparation = laneSeparation > 0 ? laneSeparation : 24.0;
        this.preferredName = preferredName;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Baritone-FleetCoordination");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Called on the game thread when an elytra flight destination is set (takeoff intent).
     * Posts a {@code takeoff} note to the bus and kicks off the first lane-conflict check.
     */
    public void onTakeoffIntent(String dim, double fromX, double fromZ, double toX, double toZ, double headingDeg) {
        this.flightDim = dim;
        this.fromX = fromX;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toZ = toZ;
        this.flightActive = true;
        this.holding = false;
        this.holdStartMs = 0L;
        this.pendingLog = null;
        this.selfName = resolveSelfName(dim);
        final String self = this.selfName;
        this.executor.execute(() -> {
            postNote(self, "takeoff|"
                    + dim + "|"
                    + String.format(Locale.ROOT, "%.1f,%.1f", fromX, fromZ) + "|"
                    + String.format(Locale.ROOT, "%.1f,%.1f", toX, toZ) + "|"
                    + String.format(Locale.ROOT, "%.1f", headingDeg));
            runConflictCheck();
        });
    }

    /**
     * Called on the game thread when a trip completes (final landing). Posts a
     * {@code landed} note so other bots' lanes can clear.
     */
    public void onLanded(String dim) {
        String body = "landed|" + dim;
        String self = resolveSelfName(dim);
        this.executor.execute(() -> postNote(self, body));
    }

    /**
     * Called on the game thread when the flight is aborted (control lost, trip cancelled):
     * clears any active hold. The already-posted takeoff note simply ages out of the bus log.
     */
    public void cancelFlight() {
        this.flightActive = false;
        this.holding = false;
        this.pendingLog = null;
    }

    /**
     * Game-thread-safe hold query: never blocks, never throws. While holding, re-checks the
     * bus lane at most once per {@link #HOLD_RETRY_MS}; after {@link #MAX_HOLDS} hold rounds
     * the hold is dropped (fail-open) and the takeoff proceeds.
     *
     * @return {@code true} if takeoff should currently be held
     */
    public boolean shouldHold() {
        if (!flightActive) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (holding && now - lastCheckMs >= HOLD_RETRY_MS && checking.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    runConflictCheck();
                } finally {
                    checking.set(false);
                }
            });
        }
        if (holding && now - holdStartMs >= MAX_HOLDS * HOLD_RETRY_MS) {
            holding = false;
            pendingLog = "max takeoff holds exceeded, taking off anyway";
        }
        return holding;
    }

    /**
     * Game-thread-safe log drain: returns the latest message the coordination wants shown
     * to the user (or {@code null}). The message must be surfaced via {@code logDirect} on
     * the game thread.
     */
    public String consumeLogMessage() {
        String message = this.pendingLog;
        if (message != null) {
            this.pendingLog = null;
        }
        return message;
    }

    private void runConflictCheck() {
        lastCheckMs = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(busUrl + "/log?limit=20"))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String conflict = findConflict(body, selfName, flightDim, fromX, fromZ, toX, toZ, laneSeparation,
                    System.currentTimeMillis());

            if (conflict != null) {
                if (!holding) {
                    holding = true;
                    holdStartMs = System.currentTimeMillis();
                }
                pendingLog = "holding takeoff — fleet lane conflict with " + conflict;
            } else if (holding) {
                holding = false;
                pendingLog = "fleet lane clear, taking off";
            }
        } catch (Exception e) {
            // fail-open: any bus error means take off normally
            holding = false;
        }
    }

    private void postNote(String from, String body) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("from", from);
            payload.addProperty("to", "*");
            payload.addProperty("type", "note");
            payload.addProperty("body", body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(busUrl + "/post"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // best-effort: a failed post must never block a takeoff or landing
        }
    }

    private String resolveSelfName(String dim) {
        try {
            String name = preferredName != null ? preferredName.get() : null;
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return "elytra-" + dim;
    }

    /**
     * Scans a bus log response for a lane conflict with our current flight.
     *
     * @return the conflicting bot's name, or {@code null} if the lane is clear
     */
    public static String findConflict(String logJson, String selfName, String dim,
                                      double fromX, double fromZ, double toX, double toZ,
                                      double laneSeparation, long nowMs) {
        if (logJson == null || logJson.isEmpty()) {
            return null;
        }
        JsonArray messages;
        try {
            JsonObject root = JsonParser.parseString(logJson).getAsJsonObject();
            JsonElement array = root.get("messages");
            if (array == null || !array.isJsonArray()) {
                return null;
            }
            messages = array.getAsJsonArray();
        } catch (Exception e) {
            return null;
        }
            for (JsonElement element : messages) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                String from = optString(message, "from");
                String body = optString(message, "body");
                if (from.isEmpty() || from.equals(selfName) || !body.startsWith("takeoff|")) {
                    continue;
                }
                TakeoffNote note = parseTakeoffNote(body);
                if (note == null || !note.dim.equals(dim)) {
                    continue;
                }
                JsonElement ts = message.get("ts");
                if (ts == null || !ts.isJsonPrimitive()) {
                    continue;
                }
                // bus timestamps are epoch seconds
                long noteTsMs = (long) (ts.getAsDouble() * 1000.0);
                if (nowMs - noteTsMs > NOTE_FRESHNESS_MS) {
                    continue;
                }
                if (segmentDistance(fromX, fromZ, toX, toZ, note.fromX, note.fromZ, note.toX, note.toZ) < laneSeparation) {
                    return from;
                }
            }
            return null;
    }

    /**
     * Parses {@code takeoff|<dim>|<fromX>,<fromZ>|<toX>,<toZ>|<headingDeg>} into a
     * {@link TakeoffNote}, or returns {@code null} if the body is malformed. Note
     * freshness is checked against the bus envelope's {@code ts}, not the body.
     */
    public static TakeoffNote parseTakeoffNote(String body) {
        if (body == null) {
            return null;
        }
        String[] parts = body.split("\\|");
        if (parts.length != 5 || !parts[0].equals("takeoff")) {
            return null;
        }
        try {
            String[] from = parts[2].split(",");
            String[] to = parts[3].split(",");
            if (from.length != 2 || to.length != 2) {
                return null;
            }
            return new TakeoffNote(
                    parts[1],
                    Double.parseDouble(from[0]), Double.parseDouble(from[1]),
                    Double.parseDouble(to[0]), Double.parseDouble(to[1]),
                    Double.parseDouble(parts[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2D segment-segment distance in XZ. Disjoint segments return the closest endpoint-to-segment distance. */
    public static double segmentDistance(double ax0, double az0, double ax1, double az1,
                                         double bx0, double bz0, double bx1, double bz1) {
        if (segmentsIntersect(ax0, az0, ax1, az1, bx0, bz0, bx1, bz1)) {
            return 0.0;
        }
        return Math.min(
                Math.min(
                        pointSegmentDistance(ax0, az0, bx0, bz0, bx1, bz1),
                        pointSegmentDistance(ax1, az1, bx0, bz0, bx1, bz1)),
                Math.min(
                        pointSegmentDistance(bx0, bz0, ax0, az0, ax1, az1),
                        pointSegmentDistance(bx1, bz1, ax0, az0, ax1, az1)));
    }

    /** 2D point-to-segment distance in XZ. */
    public static double pointSegmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0) {
            double ex = px - ax;
            double ez = pz - az;
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double closestX = ax + t * dx;
        double closestZ = az + t * dz;
        double ex = px - closestX;
        double ez = pz - closestZ;
        return Math.sqrt(ex * ex + ez * ez);
    }

    private static boolean segmentsIntersect(double ax0, double az0, double ax1, double az1,
                                             double bx0, double bz0, double bx1, double bz1) {
        double d1 = cross(bx0, bz0, bx1, bz1, ax0, az0);
        double d2 = cross(bx0, bz0, bx1, bz1, ax1, az1);
        double d3 = cross(ax0, az0, ax1, az1, bx0, bz0);
        double d4 = cross(ax0, az0, ax1, az1, bx1, bz1);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        // collinear-overlap cases
        if (d1 == 0 && onSegment(bx0, bz0, bx1, bz1, ax0, az0)) return true;
        if (d2 == 0 && onSegment(bx0, bz0, bx1, bz1, ax1, az1)) return true;
        if (d3 == 0 && onSegment(ax0, az0, ax1, az1, bx0, bz0)) return true;
        return d4 == 0 && onSegment(ax0, az0, ax1, az1, bx1, bz1);
    }

    private static double cross(double ox, double oz, double ax, double az, double bx, double bz) {
        return (ax - ox) * (bz - oz) - (az - oz) * (bx - ox);
    }

    private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }

    private static String optString(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    /** Parsed takeoff note (freshness is evaluated against the bus envelope's {@code ts}). */
    public static final class TakeoffNote {
        public final String dim;
        public final double fromX;
        public final double fromZ;
        public final double toX;
        public final double toZ;
        public final double headingDeg;

        public TakeoffNote(String dim, double fromX, double fromZ, double toX, double toZ, double headingDeg) {
            this.dim = dim;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toZ = toZ;
            this.headingDeg = headingDeg;
        }
    }
}
