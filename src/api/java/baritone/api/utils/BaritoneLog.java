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

package baritone.api.utils;

import baritone.api.BaritoneAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Tagged logger for Baritone. Every line is written to the game log (latest.log) prefixed with its
 * subsystem tag, e.g. {@code [Baritone/Elytra] ...}, independent of what is shown in chat.
 * <p>
 * Debug output is off by default. Enable it with the JVM argument {@code -Dbaritone.debug=true} or the
 * {@code debugLogging} setting ({@code #debugLogging true}). When enabled, debug lines are written at INFO
 * level with a {@code [debug]} marker so they reach latest.log under the stock Minecraft log4j config.
 */
public final class BaritoneLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");
    private static final boolean DEBUG_PROPERTY = Boolean.getBoolean("baritone.debug");

    public static final BaritoneLog GENERAL = new BaritoneLog(null);
    public static final BaritoneLog PATH = new BaritoneLog("Path");
    public static final BaritoneLog ELYTRA = new BaritoneLog("Elytra");
    public static final BaritoneLog MINE = new BaritoneLog("Mine");
    public static final BaritoneLog BUILD = new BaritoneLog("Build");
    public static final BaritoneLog EXPLORE = new BaritoneLog("Explore");
    public static final BaritoneLog FARM = new BaritoneLog("Farm");
    public static final BaritoneLog FOLLOW = new BaritoneLog("Follow");
    public static final BaritoneLog CACHE = new BaritoneLog("Cache");
    public static final BaritoneLog INVENTORY = new BaritoneLog("Inventory");
    public static final BaritoneLog COMMAND = new BaritoneLog("Command");
    public static final BaritoneLog SETTINGS = new BaritoneLog("Settings");
    public static final BaritoneLog EVENT = new BaritoneLog("Event");

    private final String prefix;

    private BaritoneLog(String tag) {
        this.prefix = tag == null ? "[Baritone] " : "[Baritone/" + tag + "] ";
    }

    /**
     * @return Whether debug logging was requested via {@code -Dbaritone.debug=true} or the {@code debugLogging} setting
     */
    public static boolean isDebugEnabled() {
        if (DEBUG_PROPERTY) {
            return true;
        }
        try {
            return BaritoneAPI.getSettings().debugLogging.value;
        } catch (Throwable t) {
            // Settings not initialised yet (e.g. logging from inside Settings' own constructor)
            return false;
        }
    }

    public void info(Object message) {
        LOGGER.info(prefix + message);
    }

    public void warn(Object message) {
        LOGGER.warn(prefix + message);
    }

    public void warn(String message, Throwable t) {
        LOGGER.warn(prefix + message, t);
    }

    public void error(String message, Throwable t) {
        LOGGER.error(prefix + message, t);
    }

    public void debug(Object message) {
        if (isDebugEnabled()) {
            LOGGER.info(prefix + "[debug] " + message);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(prefix + message);
        }
    }

    public void debug(Supplier<String> message) {
        if (isDebugEnabled() || LOGGER.isDebugEnabled()) {
            debug((Object) message.get());
        }
    }
}
