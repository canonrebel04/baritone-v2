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

package baritone.behavior.look;

/**
 * Silent -> camera hand-off (PS1 known limitation). When a camera rotation request arrives while the server-side
 * rotation is still away from the camera (e.g. walking with silent look, then mining with camera look), the
 * server rotation would otherwise jump to the camera in a single packet. Instead both legs are stepped toward the
 * new target with their own aim controllers until they meet, then plain camera mode takes over.
 */
public final class SilentCameraBlend {
    private SilentCameraBlend() {
    }

    /** Server and camera are far enough apart that a direct switch would be a visible snap. */
    public static boolean needed(double serverYaw, double serverPitch, double cameraYaw, double cameraPitch, double gcdStep) {
        return distance(serverYaw, serverPitch, cameraYaw, cameraPitch) > Math.max(2.0, 2.0 * gcdStep);
    }

    /** Both legs are close enough that switching to the camera alone is invisible. */
    public static boolean converged(double serverYaw, double serverPitch, double cameraYaw, double cameraPitch, double gcdStep) {
        return distance(serverYaw, serverPitch, cameraYaw, cameraPitch) <= Math.max(0.5, 2.0 * gcdStep);
    }

    public static double distance(double yawA, double pitchA, double yawB, double pitchB) {
        double dy = ((yawB - yawA) % 360 + 540) % 360 - 180;
        return Math.hypot(dy, pitchB - pitchA);
    }
}
