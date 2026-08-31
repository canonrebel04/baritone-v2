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

package baritone.utils;

/**
 * Detects whether an Iris shader pipeline is actively rendering.
 *
 * <p>When a shader pack is active, Iris redirects world rendering through its own frame
 * graph and does not track draw calls issued outside it (e.g. Baritone's
 * {@code RenderType.draw} calls from the {@code renderLevel RETURN} hook). Custom
 * RenderPipelines with non-vanilla blend/depth state then fight the shader program state,
 * producing flicker and depth artifacts. In that situation Baritone's renderers fall back
 * to vanilla pipelines that Iris fully understands.
 *
 * <p>Resolution is fully reflective so the main source set keeps its loader-agnostic
 * API surface: Iris (and even Fabric Loader) may be absent at runtime.
 */
public final class ShaderCompat {
    private static final boolean IRIS_PRESENT = detectIris();

    private static boolean cachedShaderPackInUse;
    private static long cachedAtNanos;

    private ShaderCompat() {}

    private static boolean detectIris() {
        try {
            Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when Iris is installed AND a shader pack is currently in use (not just installed).
     * Result is cached for one second; the reflective lookup is cheap but called per frame.
     */
    public static boolean shaderPackRenderingActive() {
        if (!IRIS_PRESENT) {
            return false;
        }
        final long now = System.nanoTime();
        if (now - cachedAtNanos > 1_000_000_000L) {
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                cachedShaderPackInUse = apiClass
                        .getMethod("isShaderPackInUse")
                        .invoke(api) == Boolean.TRUE;
            } catch (Throwable t) {
                // Iris API surface changed or initialization race — treat as not rendering.
                cachedShaderPackInUse = false;
            }
            cachedAtNanos = now;
        }
        return cachedShaderPackInUse;
    }
}
