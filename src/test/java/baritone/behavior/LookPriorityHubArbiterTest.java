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

package baritone.behavior;

import baritone.api.behavior.look.ILookRequest;
import baritone.api.utils.Rotation;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Plan S15: look-hub arbitration (priority, equal-priority hysteresis, camera/owner flags).
 */
public class LookPriorityHubArbiterTest {

    @Test
    public void highestPriorityWins() {
        LookPriorityHub hub = new LookPriorityHub();
        ILookRequest low = hub.requestRotation(1, new Rotation(10, 0), -1);
        ILookRequest high = hub.requestRotation(5, new Rotation(20, 0), -1);
        assertSame(high, hub.getHighestPriorityRequest().orElse(null));
        high.release();
        assertSame(low, hub.getHighestPriorityRequest().orElse(null));
    }

    @Test
    public void equalPriorityKeepsPreviousWinner() {
        LookPriorityHub hub = new LookPriorityHub();
        ILookRequest a = hub.requestRotation(3, new Rotation(10, 0), -1);
        assertSame(a, hub.getHighestPriorityRequest().orElse(null));
        // b is newer (inserted first) but a won last time at the same priority
        ILookRequest b = hub.requestRotation(3, new Rotation(20, 0), -1);
        for (int i = 0; i < 5; i++) {
            assertSame(a, hub.getHighestPriorityRequest().orElse(null));
        }
        a.release();
        assertSame(b, hub.getHighestPriorityRequest().orElse(null));
        // a strictly higher priority still takes over
        ILookRequest c = hub.requestRotation(4, new Rotation(30, 0), -1);
        assertSame(c, hub.getHighestPriorityRequest().orElse(null));
    }

    @Test
    public void cameraAndOwnerFlagsRoundTrip() {
        LookPriorityHub hub = new LookPriorityHub();
        ILookRequest r = hub.requestRotation(2, new Rotation(0, 0), -1);
        assertFalse(r.isCamera());
        assertNull(r.getOwner());
        r.setCamera(true);
        r.setOwner("Meteor/CameraArbiter");
        assertTrue(r.isCamera());
        assertEquals("Meteor/CameraArbiter", r.getOwner());
    }
}
