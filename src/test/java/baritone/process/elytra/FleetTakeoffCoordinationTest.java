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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for fleet takeoff coordination (elytra roadmap item 4).
 * <p>
 * Pure Java: exercises takeoff-note parsing, 2D segment-segment lane distance, and the
 * bus-log conflict scan. The HTTP layer is exercised against the live bus in integration.
 */
public class FleetTakeoffCoordinationTest {

    private static final double EPS = 1e-6;

    // ---------------------------------------------------------------- parsing

    @Test
    public void testParseTakeoffNote() {
        FleetTakeoffCoordination.TakeoffNote note = FleetTakeoffCoordination.parseTakeoffNote(
                "takeoff|the_nether|100.0,-200.5|500,600|270.0");
        assertNotNull(note);
        assertEquals("the_nether", note.dim);
        assertEquals(100.0, note.fromX, EPS);
        assertEquals(-200.5, note.fromZ, EPS);
        assertEquals(500.0, note.toX, EPS);
        assertEquals(600.0, note.toZ, EPS);
        assertEquals(270.0, note.headingDeg, EPS);
    }

    @Test
    public void testParseTakeoffNoteMalformed() {
        assertNull(FleetTakeoffCoordination.parseTakeoffNote(null));
        assertNull(FleetTakeoffCoordination.parseTakeoffNote(""));
        assertNull(FleetTakeoffCoordination.parseTakeoffNote("landed|the_nether"));
        assertNull(FleetTakeoffCoordination.parseTakeoffNote("takeoff|the_nether|a,b|500,600|90"));
        assertNull(FleetTakeoffCoordination.parseTakeoffNote("takeoff|the_nether|100,200"));
        assertNull(FleetTakeoffCoordination.parseTakeoffNote("takeoff|the_nether|100,200|500,600"));
    }

    // ------------------------------------------------------- segment distance

    @Test
    public void testSegmentDistanceCrossingIsZero() {
        assertEquals(0.0, FleetTakeoffCoordination.segmentDistance(
                0, 0, 100, 100,
                0, 100, 100, 0), EPS);
    }

    @Test
    public void testSegmentDistanceParallelLanes() {
        // two north-south lanes 50 blocks apart
        double d = FleetTakeoffCoordination.segmentDistance(
                0, 0, 0, 1000,
                50, 0, 50, 1000);
        assertEquals(50.0, d, EPS);
    }

    @Test
    public void testSegmentDistanceCollinearOverlapIsZero() {
        assertEquals(0.0, FleetTakeoffCoordination.segmentDistance(
                0, 0, 100, 0,
                50, 0, 150, 0), EPS);
    }

    @Test
    public void testSegmentDistanceEndpointToSegment() {
        // their segment's endpoint (100,50) sits 50 blocks above our segment's endpoint (100,0)
        double d = FleetTakeoffCoordination.segmentDistance(
                0, 0, 100, 0,
                100, 50, 200, 50);
        assertEquals(50.0, d, EPS);
    }

    // ------------------------------------------------------------ log parsing

    private static String log(String... entries) {
        StringBuilder sb = new StringBuilder("{\"messages\": [");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entries[i]);
        }
        return sb.append("]}").toString();
    }

    private static final double SEPARATION = 24.0;
    private static final long NOW_MS = 1_000_000_000L;

    private static String takeoffMessage(String from, String dim, String fromXZ, String toXZ, double ageSeconds) {
        double ts = (NOW_MS - ageSeconds * 1000.0) / 1000.0;
        return String.format(java.util.Locale.ROOT,
                "{\"from\": \"%s\", \"to\": \"*\", \"type\": \"note\", \"body\": \"takeoff|%s|%s|%s|90\", \"ts\": %f}",
                from, dim, fromXZ, toXZ, ts);
    }

    @Test
    public void testConflictWithFreshOverlappingLane() {
        // our lane: (0,0) -> (1000,0); theirs: (500,-10) -> (600,10) — crosses ours
        String busLog = log(takeoffMessage("bt-other", "the_nether", "500,-10", "600,10", 5));
        String conflict = FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS);
        assertEquals("bt-other", conflict);
    }

    @Test
    public void testNoConflictWithDistantParallelLane() {
        // 50 blocks north of our east-west lane: > 24 separation
        String busLog = log(takeoffMessage("bt-other", "the_nether", "0,50", "1000,50", 5));
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testSelfNotesAreIgnored() {
        String busLog = log(takeoffMessage("bt-self", "the_nether", "500,-10", "600,10", 5));
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testStaleNotesAreIgnored() {
        String busLog = log(takeoffMessage("bt-other", "the_nether", "500,-10", "600,10", 61));
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testOtherDimensionNotesAreIgnored() {
        String busLog = log(takeoffMessage("bt-other", "overworld", "500,-10", "600,10", 5));
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testNonTakeoffNotesAreIgnored() {
        String busLog = log(
                "{\"from\": \"bt-other\", \"to\": \"*\", \"type\": \"note\", \"body\": \"landed|the_nether\", \"ts\": "
                        + NOW_MS / 1000.0 + "}",
                "{\"from\": \"bt-other\", \"to\": \"*\", \"type\": \"steer\", \"body\": \"go left\", \"ts\": "
                        + NOW_MS / 1000.0 + "}");
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testMalformedBodiesAreSkipped() {
        String busLog = log(
                "{\"from\": \"bt-other\", \"to\": \"*\", \"type\": \"note\", \"body\": \"takeoff|the_nether|garbage\", \"ts\": "
                        + NOW_MS / 1000.0 + "}");
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testInvalidJsonFailsOpen() {
        assertNull(FleetTakeoffCoordination.findConflict(
                "not json at all", "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
        assertNull(FleetTakeoffCoordination.findConflict(
                "{\"messages\": \"nope\"}", "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testBoundarySeparationIsNotAConflict() {
        // exactly 24 blocks apart: not strictly below the separation, so no conflict
        String busLog = log(takeoffMessage("bt-other", "the_nether", "0,24", "1000,24", 5));
        assertNull(FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }

    @Test
    public void testFirstConflictingBotReported() {
        String busLog = log(
                takeoffMessage("bt-far", "the_nether", "0,50", "1000,50", 5),
                takeoffMessage("bt-near", "the_nether", "500,-10", "600,10", 5));
        assertEquals("bt-near", FleetTakeoffCoordination.findConflict(
                busLog, "bt-self", "the_nether", 0, 0, 1000, 0, SEPARATION, NOW_MS));
    }
}
