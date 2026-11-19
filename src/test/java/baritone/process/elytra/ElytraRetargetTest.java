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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone. If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElytraRetargetTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class StubPlayerContext implements IPlayerContext {
        BetterBlockPos feet = new BetterBlockPos(0, 100, 0);

        @Override
        public Minecraft minecraft() {
            return null;
        }

        @Override
        public LocalPlayer player() {
            return null;
        }

        @Override
        public IPlayerController playerController() {
            return null;
        }

        @Override
        public Level world() {
            return null;
        }

        @Override
        public IWorldData worldData() {
            return null;
        }

        @Override
        public HitResult objectMouseOver() {
            return null;
        }

        @Override
        public BetterBlockPos playerFeet() {
            return feet;
        }

        @Override
        public BetterBlockPos viewerPos() {
            return feet;
        }
    }

    private static final class QueuedMainExecutor implements Executor {
        private final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void drain() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    private static final class RecordingPathFinder implements IElytraPathFinder {
        record Request(BlockPos src, BlockPos dst, CompletableFuture<UnpackedSegment> future) {}

        final List<Request> requests = new ArrayList<>();

        @Override
        public CompletableFuture<UnpackedSegment> pathFindAsync(BlockPos src, BlockPos dst) {
            CompletableFuture<UnpackedSegment> future = new CompletableFuture<>();
            requests.add(new Request(src, dst, future));
            return future;
        }
    }

    private static UnpackedSegment segment(boolean finished, BetterBlockPos... nodes) {
        return new UnpackedSegment(Stream.of(nodes), finished);
    }

    @Test
    public void retargetKeepsExistingPathActiveUntilNewPathCompletes() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        RecordingPathFinder pathFinder = new RecordingPathFinder();

        BetterBlockPos initialDest = new BetterBlockPos(100, 100, 0);
        ElytraBehavior behavior = new ElytraBehavior(ctx, pathFinder, mainExec, initialDest, false);
        try {
            behavior.pathManager.pathToDestination();
            assertEquals(1, pathFinder.requests.size());
            pathFinder.requests.get(0).future().complete(segment(
                    false,
                    new BetterBlockPos(0, 100, 0),
                    new BetterBlockPos(50, 100, 0),
                    new BetterBlockPos(100, 100, 0)
            ));
            mainExec.drain();

            assertEquals(3, behavior.pathManager.getPath().size());
            assertFalse(behavior.pathManager.isRecalculating());

            ctx.feet = new BetterBlockPos(48, 100, 0);
            behavior.pathManager.updatePlayerNear();
            assertEquals(1, behavior.pathManager.getNear());

            BetterBlockPos movedTarget = new BetterBlockPos(200, 120, 80);
            CompletableFuture<Void> retargetFuture = behavior.retarget(movedTarget, false);

            assertEquals(movedTarget, behavior.destination);
            assertTrue(behavior.pathManager.isRecalculating());
            assertFalse(retargetFuture.isDone());
            assertEquals(2, pathFinder.requests.size());
            assertEquals(new BetterBlockPos(48, 100, 0), pathFinder.requests.get(1).src());
            assertEquals(movedTarget, pathFinder.requests.get(1).dst());

            // Existing path remains active and undisturbed while async calc is in flight.
            assertEquals(3, behavior.pathManager.getPath().size());
            assertEquals(new BetterBlockPos(50, 100, 0), behavior.pathManager.getPath().get(1));

            pathFinder.requests.get(1).future().complete(segment(
                    true,
                    new BetterBlockPos(40, 100, 0),
                    new BetterBlockPos(48, 100, 0),
                    new BetterBlockPos(120, 110, 40),
                    movedTarget
            ));
            mainExec.drain();

            assertTrue(retargetFuture.isDone());
            assertFalse(behavior.pathManager.isRecalculating());
            assertTrue(behavior.pathManager.isComplete());
            assertEquals(4, behavior.pathManager.getPath().size());
            assertEquals(movedTarget, behavior.pathManager.getPath().getLast());
            assertEquals(1, behavior.pathManager.getNear());
        } finally {
            behavior.destroy();
        }
    }

    @Test
    public void retargetWhileRecalculatingDiscardsStaleSegmentAndDispatchesLatest() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        RecordingPathFinder pathFinder = new RecordingPathFinder();

        BetterBlockPos dest1 = new BetterBlockPos(100, 100, 0);
        ElytraBehavior behavior = new ElytraBehavior(ctx, pathFinder, mainExec, dest1, false);
        try {
            behavior.pathManager.pathToDestination();
            pathFinder.requests.get(0).future().complete(segment(
                    false,
                    new BetterBlockPos(0, 100, 0),
                    new BetterBlockPos(50, 100, 0),
                    dest1
            ));
            mainExec.drain();

            // Kick off an obstacle segment recalc for the old destination.
            behavior.pathManager.pathRecalcSegment(OptionalInt.of(2));
            assertTrue(behavior.pathManager.isRecalculating());
            assertEquals(2, pathFinder.requests.size());

            // Target moves twice before the in-flight segment recalc finishes.
            BetterBlockPos destMid = new BetterBlockPos(150, 100, 30);
            BetterBlockPos destLatest = new BetterBlockPos(220, 110, 60);
            CompletableFuture<Void> fMid = behavior.retarget(destMid, false);
            CompletableFuture<Void> fLatest = behavior.retarget(destLatest, false);

            // Should coalesce instead of queuing redundant back-to-back native pathfinds.
            assertEquals(2, pathFinder.requests.size());
            assertEquals(destLatest, behavior.destination);

            // Complete the stale segment calculation.
            pathFinder.requests.get(1).future().complete(segment(
                    false,
                    new BetterBlockPos(10, 100, 10),
                    new BetterBlockPos(20, 100, 20)
            ));
            mainExec.drain();

            // Stale segment must NOT overwrite the active path, and pending retarget dispatches destLatest.
            assertEquals(dest1, behavior.pathManager.getPath().getLast());
            assertEquals(3, pathFinder.requests.size());
            assertEquals(destLatest, pathFinder.requests.get(2).dst());
            assertTrue(behavior.pathManager.isRecalculating());

            pathFinder.requests.get(2).future().complete(segment(
                    true,
                    new BetterBlockPos(0, 100, 0),
                    destLatest
            ));
            mainExec.drain();

            assertTrue(fMid.isDone());
            assertTrue(fLatest.isDone());
            assertFalse(behavior.pathManager.isRecalculating());
            assertEquals(destLatest, behavior.pathManager.getPath().getLast());
        } finally {
            behavior.destroy();
        }
    }

    @Test
    public void retargetFailurePreservesExistingPathAndAllowsRetry() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        RecordingPathFinder pathFinder = new RecordingPathFinder();

        BetterBlockPos dest1 = new BetterBlockPos(100, 100, 0);
        ElytraBehavior behavior = new ElytraBehavior(ctx, pathFinder, mainExec, dest1, false);
        try {
            behavior.pathManager.pathToDestination();
            pathFinder.requests.get(0).future().complete(segment(
                    true,
                    new BetterBlockPos(0, 100, 0),
                    dest1
            ));
            mainExec.drain();

            BetterBlockPos unreachable = new BetterBlockPos(300, 100, 300);
            CompletableFuture<Void> failedFuture = behavior.retarget(unreachable, false);
            pathFinder.requests.get(1).future().completeExceptionally(new PathCalculationException("blocked"));
            mainExec.drain();

            assertTrue(failedFuture.isCompletedExceptionally());
            assertFalse(behavior.pathManager.isRecalculating());
            assertEquals(2, behavior.pathManager.getPath().size());
            assertEquals(dest1, behavior.pathManager.getPath().getLast());

            BetterBlockPos dest3 = new BetterBlockPos(180, 105, 40);
            CompletableFuture<Void> retryFuture = behavior.retarget(dest3, false);
            pathFinder.requests.get(2).future().complete(segment(
                    true,
                    new BetterBlockPos(0, 100, 0),
                    dest3
            ));
            mainExec.drain();

            assertTrue(retryFuture.isDone());
            assertFalse(retryFuture.isCompletedExceptionally());
            assertEquals(dest3, behavior.pathManager.getPath().getLast());
        } finally {
            behavior.destroy();
        }
    }

    @Test
    public void destroyWhileRetargetInFlightDoesNotApplyStalePath() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        RecordingPathFinder pathFinder = new RecordingPathFinder();

        ElytraBehavior behavior = new ElytraBehavior(ctx, pathFinder, mainExec, new BetterBlockPos(100, 100, 0), false);
        behavior.pathManager.pathToDestination();
        pathFinder.requests.get(0).future().complete(segment(true, new BetterBlockPos(0, 100, 0), new BetterBlockPos(100, 100, 0)));
        mainExec.drain();

        BetterBlockPos dest2 = new BetterBlockPos(200, 100, 0);
        behavior.retarget(dest2, false);
        behavior.destroy();

        pathFinder.requests.get(1).future().complete(segment(true, new BetterBlockPos(0, 100, 0), dest2));
        mainExec.drain();

        assertTrue(behavior.pathManager.getPath().isEmpty());
        assertFalse(behavior.pathManager.isRecalculating());
    }

    @Test
    public void retargetRecoversWhenPathFindThrowsSynchronouslyAndHandlesCacheHits() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        boolean[] throwSync = {true};
        BetterBlockPos dest1 = new BetterBlockPos(100, 100, 0);
        BetterBlockPos dest2 = new BetterBlockPos(160, 100, 20);
        BetterBlockPos dest3 = new BetterBlockPos(220, 110, 40);

        IElytraPathFinder mixedPathFinder = (src, dst) -> {
            if (throwSync[0]) {
                throw new IllegalStateException("sync failure");
            }
            if (dest2.equals(dst)) {
                return CompletableFuture.completedFuture(segment(true, new BetterBlockPos(src), dest2));
            }
            return CompletableFuture.supplyAsync(
                    () -> segment(true, new BetterBlockPos(src), new BetterBlockPos(dst)),
                    Runnable::run
            );
        };

        ElytraBehavior behavior = new ElytraBehavior(ctx, mixedPathFinder, mainExec, dest1, false);
        try {
            CompletableFuture<Void> failed = behavior.retarget(dest1, false);
            assertTrue(failed.isCompletedExceptionally());
            assertFalse(behavior.pathManager.isRecalculating());

            throwSync[0] = false;
            CompletableFuture<Void> cachedHit = behavior.retarget(dest2, false);
            assertTrue(behavior.pathManager.isRecalculating());
            mainExec.drain();

            assertTrue(cachedHit.isDone());
            assertFalse(cachedHit.isCompletedExceptionally());
            assertFalse(behavior.pathManager.isRecalculating());
            assertEquals(dest2, behavior.pathManager.getPath().getLast());

            // Switching from appendDestination=true (landing) back to false clears landingMode cleanly.
            behavior.landingMode = true;
            CompletableFuture<Void> chaseFuture = behavior.retarget(dest3, false);
            assertFalse(behavior.landingMode);
            mainExec.drain();
            assertTrue(chaseFuture.isDone());
            assertEquals(dest3, behavior.pathManager.getPath().getLast());
        } finally {
            behavior.destroy();
        }
    }

    @Test
    public void chainedRetargetWhileDispatchedPendingRetargetIsInFlightResolvesAllFutures() {
        StubPlayerContext ctx = new StubPlayerContext();
        QueuedMainExecutor mainExec = new QueuedMainExecutor();
        RecordingPathFinder pathFinder = new RecordingPathFinder();

        BetterBlockPos dest1 = new BetterBlockPos(100, 100, 0);
        BetterBlockPos dest2 = new BetterBlockPos(150, 100, 0);
        BetterBlockPos dest3 = new BetterBlockPos(250, 105, 50);

        ElytraBehavior behavior = new ElytraBehavior(ctx, pathFinder, mainExec, dest1, false);
        try {
            behavior.pathManager.pathToDestination();
            CompletableFuture<Void> f2 = behavior.retarget(dest2, false);

            // Complete dest1; this dispatches dest2 on mainExec.
            pathFinder.requests.get(0).future().complete(segment(true, new BetterBlockPos(0, 100, 0), dest1));
            mainExec.drain();
            assertEquals(2, pathFinder.requests.size());
            assertEquals(dest2, pathFinder.requests.get(1).dst());

            // While dest2 is still in flight, retarget to dest3.
            CompletableFuture<Void> f3 = behavior.retarget(dest3, false);
            pathFinder.requests.get(1).future().complete(segment(true, new BetterBlockPos(0, 100, 0), dest2));
            mainExec.drain();

            assertTrue(f2.isDone());
            assertEquals(3, pathFinder.requests.size());
            assertEquals(dest3, pathFinder.requests.get(2).dst());

            pathFinder.requests.get(2).future().complete(segment(true, new BetterBlockPos(0, 100, 0), dest3));
            mainExec.drain();

            assertTrue(f3.isDone());
            assertFalse(f3.isCompletedExceptionally());
            assertFalse(behavior.pathManager.isRecalculating());
            assertEquals(dest3, behavior.pathManager.getPath().getLast());
        } finally {
            behavior.destroy();
        }
    }
}
