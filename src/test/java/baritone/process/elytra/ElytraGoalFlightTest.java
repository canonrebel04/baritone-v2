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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Headless coverage for the two pure consumers of a stuck elytra goal:
 * a path segment the cache and the splice both revisit, and a highway
 * departure the glider can actually climb.
 */
public class ElytraGoalFlightTest {

    @Test
    public void segmentSurvivesCollectThenSplice() {
        final BetterBlockPos a = new BetterBlockPos(4, 184, 131);
        final BetterBlockPos b = new BetterBlockPos(4, 190, 200);
        final BetterBlockPos c = new BetterBlockPos(4, 200, 400);
        final UnpackedSegment segment = new UnpackedSegment(Stream.of(a, b), false);

        // The path cache returns this same instance after setPath has already collected it.
        assertEquals(List.of(a, b), segment.collect());
        assertEquals(List.of(a, b), segment.collect());

        final UnpackedSegment extended = segment.append(Stream.of(c), true);
        assertEquals(List.of(a, b, c), extended.collect());
        assertTrue(extended.isFinished());
        // The cached original is still reusable for the next recalc.
        assertEquals(List.of(a, b), segment.collect());
        assertFalse(segment.isFinished());

        final UnpackedSegment prefixed = extended.prepend(Stream.of(new BetterBlockPos(4, 184, 100)));
        assertEquals(List.of(new BetterBlockPos(4, 184, 100), a, b, c), prefixed.collect());
        assertTrue(prefixed.isFinished());
        assertEquals(List.of(a, b, c), extended.collect());
    }

    @Test
    public void climbTowardGoalStaysFlyable() {
        // #elytra 4 900 160 is x=4, z=900, y=160. A glider at y=184 must not be
        // handed a node ~150 blocks straight up.
        final BetterBlockPos start = new BetterBlockPos(4, 184, 131);
        final BetterBlockPos destination = new BetterBlockPos(4, 160, 900);
        final int flightLevel = 336;
        final List<BetterBlockPos> climb = BuildLimitPathFinder.flyableClimb(
                start, destination, flightLevel, BuildLimitPathFinder.MAX_CLIMB_RISE_PER_RUN, BuildLimitPathFinder.CLIMB_STEP);

        assertFalse(climb.isEmpty());
        BetterBlockPos prev = start;
        for (BetterBlockPos node : climb) {
            final int run = Math.abs(node.x - prev.x) + Math.abs(node.z - prev.z);
            final int rise = node.y - prev.y;
            assertTrue("climb z " + prev.z + " -> " + node.z + " does not approach " + destination.z, node.z > prev.z);
            assertTrue("climb rises " + rise + " over horizontal run " + run, rise <= run * BuildLimitPathFinder.MAX_CLIMB_RISE_PER_RUN + 1.0);
            assertTrue(rise > 0);
            prev = node;
        }
        assertEquals(flightLevel, climb.get(climb.size() - 1).y);
        assertTrue(climb.get(0).y < start.y + 20);
    }

    @Test
    public void climbThatCannotFinishBeforeTheGoalIsRefused() {
        final BetterBlockPos start = new BetterBlockPos(4, 184, 131);
        final BetterBlockPos tooClose = new BetterBlockPos(4, 160, 200);
        final List<BetterBlockPos> climb = BuildLimitPathFinder.flyableClimb(
                start, tooClose, 336, BuildLimitPathFinder.MAX_CLIMB_RISE_PER_RUN, BuildLimitPathFinder.CLIMB_STEP);
        assertTrue(climb.isEmpty());
    }
}
