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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.cache.CachedWorld;
import baritone.process.ElytraProcess;
import net.minecraft.ChatFormatting;

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

        if (args.hasAny() && args.peekString().equalsIgnoreCase("survey")) {
            args.requireExactly(1);
            survey();
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

    /**
     * Aerial survey mode (elytra roadmap item 5): flies a high-altitude elytra circuit over the
     * frontier ring's bounding box. During flight, the elytra pathfinder packs visible chunks;
     * on completion, the newly packed region count is logged and posted to the local agent
     * coordination bus.
     */
    private void survey() {
        if (!Baritone.settings().elytraSurveyEnabled.value) {
            logDirect("Aerial survey is disabled (elytraSurveyEnabled = false).");
            return;
        }
        IElytraProcess elytra = baritone.getElytraProcess();
        if (!(elytra instanceof ElytraProcess elytraProcess) || !elytraProcess.isLoaded()) {
            logDirect("Aerial survey unavailable: elytra flight is not supported/loaded in this environment.");
            return;
        }
        if (elytraProcess.isTripActive()) {
            logDirect("An elytra trip is already in progress — survey not started.");
            return;
        }

        var explorer = ((Baritone) baritone).frontierExplorerProcess;
        int[] center = explorer.frontierSurveyCenter();
        if (center == null) {
            // no frontier scan has run yet this session — do one now (commands run on the game thread)
            explorer.scanFrontiers();
            center = explorer.frontierSurveyCenter();
        }
        if (center == null) {
            logDirect("No frontier chunks known — the cached world has no unexplored boundary to survey.");
            return;
        }

        int centroidChunkX = center[0];
        int centroidChunkZ = center[1];
        int radiusChunks = center[2];

        int altitude = Baritone.settings().elytraSurveyAltitude.value;
        // clamp into this dimension's flyable band so elytra bounds checks can never reject a waypoint
        int minY = ctx.world().dimensionType().minY();
        int maxY = minY + ctx.world().dimensionType().height();
        altitude = Math.max(minY + 1, Math.min(altitude, maxY - 1));

        // circuit corners: one chunk past the frontier bounding box edge, block coordinates
        int d = (radiusChunks + 1) << 4;
        int cx = (centroidChunkX << 4) | 8;
        int cz = (centroidChunkZ << 4) | 8;
        List<GoalXZ> circuit = Arrays.asList(
                new GoalXZ(cx - d, cz - d),
                new GoalXZ(cx + d, cz - d),
                new GoalXZ(cx + d, cz + d),
                new GoalXZ(cx - d, cz + d));

        CachedWorld cached = (CachedWorld) ((Baritone) baritone).getWorldProvider().getCurrentWorld().getCachedWorld();
        int regionsBefore = cached.getRegionCount();

        logDirect("Aerial survey: " + circuit.size() + "-waypoint elytra circuit at Y=" + altitude
                + " over the frontier ring (centroid chunk " + centroidChunkX + "," + centroidChunkZ
                + ", radius " + radiusChunks + " chunks).");
        try {
            elytraProcess.startSurveyTrip(circuit, altitude, () -> {
                int newlyPacked = cached.getRegionCount() - regionsBefore;
                String body = "survey complete, " + newlyPacked + " chunks newly packed";
                logDirect("Aerial survey circuit complete — " + body + ".");
                postBusNote(body);
            });
        } catch (IllegalArgumentException e) {
            logDirect("Failed to start survey circuit: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    /**
     * Best-effort fire-and-forget note to the local agent coordination bus (the {@code bus}
     * CLI). Never throws — the survey result is always logged in-game regardless.
     */
    private static void postBusNote(String body) {
        try {
            new ProcessBuilder("bus", "post", "survey", "*", "note", body)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception ignored) {
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        try {
            if (args.hasExactlyOne() && args.peekString().startsWith("s")) {
                return Stream.of("stop", "survey");
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
                "> frontier survey - fly a high-altitude elytra circuit over the frontier ring",
                "> frontier stop - stop exploring"
        );
    }
}
