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

package baritone.process.elytra;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for nether portal discovery + shortcut matching (elytra roadmap item 2b).
 * <p>
 * Pure Java: exercises the registry dedupe, the /8 overworld projection, the
 * {@link PortalKnowledge#findShortcut} trip-leg query and the
 * {@code <dimension>-portals.json} persistence round trip.
 */
public class PortalKnowledgeTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        PortalKnowledge.resetForTest();
        tempDir = Files.createTempDirectory("baritone-portals-test");
    }

    @After
    public void tearDown() throws Exception {
        PortalKnowledge.resetForTest();
    }

    @Test
    public void testNearbyPortalBlocksDedupeToOnePortal() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 100, 64, 200);
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 102, 65, 202); // same frame
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 101, 63, 201); // same frame
        List<PortalKnowledge.Portal> portals = PortalKnowledge.known(PortalKnowledge.NETHER);
        assertEquals(1, portals.size());
        assertEquals(100, portals.get(0).x);
        assertEquals(64, portals.get(0).y);
        assertEquals(200, portals.get(0).z);
    }

    @Test
    public void testDistantPortalsAreDistinct() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 0, 64, 0);
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 64, 70, 0);
        assertEquals(2, PortalKnowledge.known(PortalKnowledge.NETHER).size());
    }

    @Test
    public void testNetherRecordProjectsToOverworldBy8() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 500, 64, -600);
        PortalKnowledge.Portal portal = PortalKnowledge.known(PortalKnowledge.NETHER).get(0);
        assertEquals(4000, portal.overworldX);
        assertEquals(-4800, portal.overworldZ);
        assertEquals(500, portal.netherX());
        assertEquals(-600, portal.netherZ());
        assertEquals(PortalKnowledge.NETHER, portal.side);
    }

    @Test
    public void testOverworldRecordKeepsCoordinates() {
        PortalKnowledge.recordPortal(PortalKnowledge.OVERWORLD, 4000, 70, -4800);
        PortalKnowledge.Portal portal = PortalKnowledge.known(PortalKnowledge.OVERWORLD).get(0);
        assertEquals(4000, portal.overworldX);
        assertEquals(-4800, portal.overworldZ);
        assertEquals(500, portal.netherX());
        assertEquals(-600, portal.netherZ());
        assertEquals(PortalKnowledge.OVERWORLD, portal.side);
    }

    @Test
    public void testShortcutFromNetherLeg() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 500, 64, -600);
        // start near the portal; the nether target's overworld projection (4040, -4760) is
        // near the portal's overworld side (4000, -4800)
        PortalKnowledge.Portal portal = PortalKnowledge.findShortcut(PortalKnowledge.NETHER, 520, -610, 505, -595);
        assertNotNull(portal);
        assertEquals(500, portal.x);
        assertEquals(-600, portal.z);
    }

    @Test
    public void testNoShortcutWhenPortalFarFromStart() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 500, 64, -600);
        assertNull(PortalKnowledge.findShortcut(
                PortalKnowledge.NETHER,
                520 + PortalKnowledge.PORTAL_NEAR_START, -610,
                505, -595));
    }

    @Test
    public void testNoShortcutWhenTargetFarFromPortalOtherSide() {
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 500, 64, -600);
        // the target's overworld projection is 2000 overworld blocks away from the portal side
        assertNull(PortalKnowledge.findShortcut(PortalKnowledge.NETHER, 520, -610, 505 + 250, -595));
    }

    @Test
    public void testShortcutFromOverworldLeg() {
        PortalKnowledge.recordPortal(PortalKnowledge.OVERWORLD, 4000, 70, -4800);
        // overworld start near the portal; the target's /8 nether projection (505, -595) is
        // near the portal's nether side (500, -600)
        PortalKnowledge.Portal portal = PortalKnowledge.findShortcut(PortalKnowledge.OVERWORLD, 4022, -4822, 4040, -4760);
        assertNotNull(portal);
    }

    @Test
    public void testNoShortcutInUnknownDimension() {
        PortalKnowledge.recordPortal("the_end", 0, 64, 0);
        assertNull(PortalKnowledge.findShortcut("the_end", 0, 0, 8, 8));
    }

    @Test
    public void testSaveLoadRoundTrip() throws Exception {
        PortalKnowledge.syncWorldDirectory(tempDir, PortalKnowledge.NETHER);
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, 500, 64, -600);
        PortalKnowledge.recordPortal(PortalKnowledge.NETHER, -120, 40, 300);
        PortalKnowledge.saveAll();
        assertTrue(Files.exists(tempDir.resolve("the_nether-portals.json")));
        PortalKnowledge.resetForTest();
        PortalKnowledge.syncWorldDirectory(tempDir, PortalKnowledge.NETHER);
        List<PortalKnowledge.Portal> portals = PortalKnowledge.known(PortalKnowledge.NETHER);
        assertEquals(2, portals.size());
        // a shortcut must still be findable from the reloaded registry
        PortalKnowledge.Portal found = PortalKnowledge.findShortcut(PortalKnowledge.NETHER, 500, -600, 505, -595);
        assertNotNull(found);
        assertEquals(-600, found.z);
    }

    @Test
    public void testRegistryCapIsRespected() {
        for (int i = 0; i < 1100; i++) {
            PortalKnowledge.recordPortal(PortalKnowledge.OVERWORLD, i * 16, 70, 0);
        }
        assertTrue(PortalKnowledge.known(PortalKnowledge.OVERWORLD).size() <= 1024);
    }
}
