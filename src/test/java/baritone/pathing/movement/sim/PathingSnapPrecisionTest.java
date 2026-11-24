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
package baritone.pathing.movement.sim;

import baritone.behavior.look.LookProfile;
import baritone.behavior.look.SaccadeAimController;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Headless regression for pathing yaw snap: movements request a look at dest
 * and hold MOVE_FORWARD along the (possibly smoothed) yaw, matching
 * {@code LookBehavior.onPlayerRotationMove}. With snapped yaw, a 90° off
 * 2-block-gap parkour, a corner diagonal ascend, and a diagonal cross jump
 * must land. With Legit-Smooth lag, the same 90° parkour walks off the
 * takeoff block without jumping.
 *
 * <p>Minecraft yaw: 0 looks +Z (south), 90 looks -X (west); forward is
 * {@code (-sin(yaw), cos(yaw))}.
 */
public class PathingSnapPrecisionTest {

    private static final double MAX_TURN = 95.0;
    private static final double HALF = PlayerPhysics.PLAYER_HALF_WIDTH;
    private static final LookProfile LEGIT = LookProfile.LEGIT_SMOOTH;

    enum LookMode { SNAP, SMOOTH }

    static final class WorldBox {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        WorldBox(int x, int y, int z, int dx, int dy, int dz) {
            this.minX = x; this.minY = y; this.minZ = z;
            this.maxX = x + dx; this.maxY = y + dy; this.maxZ = z + dz;
        }
        boolean overlaps(double px, double py, double pz, double half, double height) {
            return px + half > minX && px - half < maxX
                    && py + height > minY && py < maxY
                    && pz + half > minZ && pz - half < maxZ;
        }
        boolean supports(double px, double pz, double half) {
            return px + half > minX && px - half < maxX
                    && pz + half > minZ && pz - half < maxZ;
        }
    }

    enum Kind { PARKOUR, DIAG_ASCEND, DIAG_CROSS }

    static final class Scenario {
        final String name;
        final Kind kind;
        final double startX, startY, startZ;
        final float startYaw;
        final double destX, destY, destZ;
        final int srcX, srcZ, destBX, destBZ, dirX, dirZ;
        final List<WorldBox> solids, floors;
        Scenario(String name, Kind kind, double sx, double sy, double sz, float yaw,
                 double dx, double dy, double dz,
                 int srcX, int srcZ, int dbx, int dbz, int dirX, int dirZ,
                 List<WorldBox> solids, List<WorldBox> floors) {
            this.name = name; this.kind = kind;
            this.startX = sx; this.startY = sy; this.startZ = sz; this.startYaw = yaw;
            this.destX = dx; this.destY = dy; this.destZ = dz;
            this.srcX = srcX; this.srcZ = srcZ; this.destBX = dbx; this.destBZ = dbz;
            this.dirX = dirX; this.dirZ = dirZ;
            this.solids = solids; this.floors = floors;
        }
    }

    static final class Result {
        final boolean reached, jumped, walkedOff;
        final int ticks;
        final double landErr;
        Result(boolean reached, boolean jumped, boolean walkedOff, int ticks, double landErr) {
            this.reached = reached; this.jumped = jumped; this.walkedOff = walkedOff;
            this.ticks = ticks; this.landErr = landErr;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "reached=%s jumped=%s walkOff=%s ticks=%d landErr=%.3f",
                    reached, jumped, walkedOff, ticks, landErr);
        }
    }

    static float yawTo(double px, double pz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - px), (tz - pz)));
    }

    static Result run(Scenario sc, LookMode look) {
        MovementSimulator.SimConfig cfg = MovementSimulator.SimConfig.vanillaDefault();
        MovementSimulator.SimState s = new MovementSimulator.SimState(
                sc.startX, sc.startY, sc.startZ, 0, 0, 0, true, false);
        SaccadeAimController aim = new SaccadeAimController(0xC0FFEE);
        aim.reset(sc.startYaw, 0.0);
        float yaw = sc.startYaw;
        boolean horizColNext = false, jumped = false, hasJumped = false, reached = false;
        double landErr = Double.NaN;
        int ticks;
        for (ticks = 1; ticks <= 80; ticks++) {
            float targetYaw = yawTo(s.x, s.z, sc.destX, sc.destZ);
            if (look == LookMode.SNAP) {
                yaw = targetYaw;
            } else {
                aim.step(targetYaw, 0.0, LEGIT, MAX_TURN, false);
                yaw = (float) aim.yaw();
            }
            boolean wantJump = false;
            boolean onSrc = Math.floor(s.x) == sc.srcX && Math.floor(s.z) == sc.srcZ
                    && Math.abs(s.y - sc.startY) < 0.8;
            if (sc.kind == Kind.DIAG_ASCEND) {
                if (s.onGround && s.y < sc.startY + 0.1 && horizColNext) {
                    wantJump = true;
                }
            } else {
                boolean feetToward = !onSrc && (
                        (sc.dirX != 0 || sc.dirZ != 0)
                                && Math.floor(s.x) == sc.srcX + sc.dirX
                                && Math.floor(s.z) == sc.srcZ + sc.dirZ
                        || s.y - sc.startY > 0.0001);
                if (s.onGround && !hasJumped && feetToward) {
                    wantJump = true;
                }
            }
            MovementSimulator.step(s, MovementSimulator.SimInput.forward(yaw, true, wantJump), cfg);
            boolean hit = overlapsAny(sc.solids, s.x, s.y, s.z);
            resolveCollision(s, sc.solids);
            applySupport(s, sc.floors);
            horizColNext = hit;
            if (wantJump && s.vy > 0) {
                jumped = true;
                hasJumped = true;
            }
            if (Math.floor(s.x) == sc.destBX && Math.floor(s.z) == sc.destBZ
                    && s.onGround && s.y >= sc.destY - 0.2) {
                reached = true;
                landErr = Math.hypot(s.x - sc.destX, s.z - sc.destZ);
                break;
            }
            boolean supported = isSupported(s, sc.floors);
            if (s.y <= sc.destY - 0.6 && !supported && !hasJumped) {
                break;
            }
        }
        boolean onSrc = Math.floor(s.x) == sc.srcX && Math.floor(s.z) == sc.srcZ
                && Math.abs(s.y - sc.startY) < 0.8;
        boolean supported = isSupported(s, sc.floors);
        boolean walkedOff = !reached && !onSrc && !supported && s.y < sc.startY - 0.15;
        if (reached && Double.isNaN(landErr)) {
            landErr = Math.hypot(s.x - sc.destX, s.z - sc.destZ);
        }
        return new Result(reached, jumped, walkedOff, ticks, landErr);
    }

    static boolean overlapsAny(List<WorldBox> boxes, double x, double y, double z) {
        for (WorldBox b : boxes) {
            if (b.overlaps(x, y, z, HALF, 1.8)) return true;
        }
        return false;
    }

    static void resolveCollision(MovementSimulator.SimState s, List<WorldBox> solids) {
        for (int i = 0; i < 3; i++) {
            for (WorldBox b : solids) {
                if (!b.overlaps(s.x, s.y, s.z, HALF, 1.8)) continue;
                double overlapX = Math.min((s.x + HALF) - b.minX, b.maxX - (s.x - HALF));
                double overlapZ = Math.min((s.z + HALF) - b.minZ, b.maxZ - (s.z - HALF));
                if (overlapX < overlapZ) {
                    if ((s.x + HALF) - b.minX < b.maxX - (s.x - HALF)) s.x = b.minX - HALF - 1e-4;
                    else s.x = b.maxX + HALF + 1e-4;
                    s.vx = 0;
                } else {
                    if ((s.z + HALF) - b.minZ < b.maxZ - (s.z - HALF)) s.z = b.minZ - HALF - 1e-4;
                    else s.z = b.maxZ + HALF + 1e-4;
                    s.vz = 0;
                }
            }
        }
    }

    static void applySupport(MovementSimulator.SimState s, List<WorldBox> floors) {
        WorldBox on = null;
        double bestTop = -1e9;
        for (WorldBox f : floors) {
            if (f.supports(s.x, s.z, HALF) && s.y <= f.maxY + 0.55 && s.y >= f.maxY - 0.7) {
                if (f.maxY > bestTop) {
                    bestTop = f.maxY;
                    on = f;
                }
            }
        }
        if (on != null && s.vy <= 0 && s.y <= on.maxY + 0.55) {
            s.y = on.maxY;
            s.vy = 0;
            s.onGround = true;
        } else if (on == null) {
            s.onGround = false;
        }
    }

    static boolean isSupported(MovementSimulator.SimState s, List<WorldBox> floors) {
        for (WorldBox f : floors) {
            if (f.supports(s.x, s.z, HALF) && Math.abs(s.y - f.maxY) < 0.05) return true;
        }
        return false;
    }

    static Scenario parkourEast(float yaw) {
        List<WorldBox> floors = List.of(new WorldBox(0, 63, 0, 1, 1, 1), new WorldBox(3, 63, 0, 1, 1, 1));
        return new Scenario("parkour2gap yaw=" + yaw, Kind.PARKOUR, 0.5, 64, 0.5, yaw,
                3.5, 64, 0.5, 0, 0, 3, 0, 1, 0, List.of(), floors);
    }

    static Scenario diagAscend(float yaw) {
        List<WorldBox> solids = List.of(new WorldBox(1, 64, 1, 1, 1, 1), new WorldBox(0, 64, 1, 1, 2, 1));
        List<WorldBox> floors = List.of(new WorldBox(0, 63, 0, 1, 1, 1), new WorldBox(1, 64, 1, 1, 1, 1));
        return new Scenario("diagAscendCorner yaw=" + yaw, Kind.DIAG_ASCEND, 0.5, 64, 0.5, yaw,
                1.5, 65, 1.5, 0, 0, 1, 1, 0, 0, solids, floors);
    }

    static Scenario diagCross(float yaw) {
        List<WorldBox> floors = List.of(new WorldBox(0, 63, 0, 1, 1, 1), new WorldBox(2, 63, 2, 1, 1, 1));
        return new Scenario("diagCross2 yaw=" + yaw, Kind.DIAG_CROSS, 0.5, 64, 0.5, yaw,
                2.5, 64, 2.5, 0, 0, 2, 2, 1, 1, List.of(), floors);
    }

    @Test
    public void snapLandsParkourDiagonalAscendAndCrossJump() {
        Result p90snap = run(parkourEast(0f), LookMode.SNAP); // facing south, dest is east: 90° off
        Result p90smooth = run(parkourEast(0f), LookMode.SMOOTH);
        Result a180 = run(diagAscend(180f), LookMode.SNAP);
        Result a45 = run(diagAscend(45f), LookMode.SNAP);
        Result cross = run(diagCross(0f), LookMode.SNAP);
        System.out.printf(Locale.ROOT,
                "parkour 90-off SNAP %s%nparkour 90-off SMOOTH %s%nascend 180-off SNAP %s%nascend 45-off SNAP %s%ncross SNAP %s%n",
                p90snap, p90smooth, a180, a45, cross);
        assertTrue("Snap must land 2-gap parkour starting 90° off, got " + p90snap, p90snap.reached && p90snap.jumped && !p90snap.walkedOff);
        assertTrue("Smooth look still walks off 2-gap parkour starting 90° off, got " + p90smooth, p90smooth.walkedOff && !p90smooth.jumped);
        assertTrue("Snap must land corner diagonal ascend starting 180° off, got " + a180, a180.reached && a180.jumped && !a180.walkedOff);
        assertTrue("Snap must land corner diagonal ascend starting 45° off, got " + a45, a45.reached && a45.jumped && !a45.walkedOff);
        assertTrue("Snap must land diagonal cross jump, got " + cross, cross.reached && cross.jumped && !cross.walkedOff);
        assertFalse("Landing error should be on the dest block", Double.isNaN(p90snap.landErr) || p90snap.landErr > 0.8);
    }
}
