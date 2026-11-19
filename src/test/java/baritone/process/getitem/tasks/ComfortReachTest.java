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

package baritone.process.getitem.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** S43 comfort reach for containers and stations. */
public class ComfortReachTest {
    @Test
    public void comfortReach() {
        assertEquals(3.7, ContainerAccess.comfortReach(4.5), 1e-9);
        assertEquals(3.7, ContainerAccess.comfortReach(5.0), 1e-9);
        assertEquals(2.7, ContainerAccess.comfortReach(3.5), 1e-9);
        assertEquals(2.0, ContainerAccess.comfortReach(2.0), 1e-9);
    }
}
