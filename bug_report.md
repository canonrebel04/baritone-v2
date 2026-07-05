- **File & Line:** `src/main/java/baritone/pathing/calc/PathNode.java:93` (and `src/api/java/baritone/api/pathing/goals/Goal*.java`)
- **Severity:** Critical
- **Bug Type:** Logic Errors
- **Description:** Casting a 64-bit packed block coordinate (from `BetterBlockPos.longHash()`) to an `int` for use in `hashCode()` silently truncates the upper 32 bits. Because `BetterBlockPos.serializeToLong` places the X coordinate and upper bits of the Y coordinate in the top 32 bits of the `long`, casting to `int` completely discards the X and part of the Y coordinates. This leads to massive hash collisions where blocks at different X/Y coordinates produce the exact same hash code. While `Long2ObjectOpenHashMap` uses `BetterBlockPos.longHash` directly (which operates perfectly with bijections), anywhere standard Java hash structures or `hashCode()` are used for these objects (like `Set<Goal>` or `Set<PathNode>`) will suffer devastating performance degradation and potentially incorrect logic.
- **Reproduction:** Call `new PathNode(100, 64, 50, goal).hashCode()` and `new PathNode(-200, 64, 50, goal).hashCode()`. They will evaluate to the same value because the X coordinate is located in the truncated top 32 bits.
- **Fix:** Replace `(int) BetterBlockPos.longHash(x, y, z)` with `Long.hashCode(BetterBlockPos.longHash(x, y, z))` in all affected files:
  - `src/main/java/baritone/pathing/calc/PathNode.java:93`
  - `src/api/java/baritone/api/pathing/goals/GoalGetToBlock.java:80`
  - `src/api/java/baritone/api/pathing/goals/GoalTwoBlocks.java:92`
  - `src/api/java/baritone/api/pathing/goals/GoalStrictDirection.java:91`
  - `src/api/java/baritone/api/pathing/goals/GoalNear.java:107`
  - `src/api/java/baritone/api/pathing/goals/GoalBlock.java:86`
  - `src/main/java/baritone/process/BuilderProcess.java:922`

- **File & Line:** `src/main/java/baritone/cache/CachedWorld.java:82` (and multiple other cache initialization methods)
- **Severity:** High
- **Bug Type:** Error Handling Gaps
- **Description:** `Files.createDirectories` is surrounded by an empty catch block (`catch (IOException ignored) {}`). If directory creation fails (e.g. due to permissions or full disk), the application silently continues but any subsequent attempts to write to the cache or file system will throw unhandled exceptions or fail silently.
- **Reproduction:** Run the client with a read-only game directory. The directory creation will fail silently, and later Baritone file write operations will crash or fail without proper error logging.
- **Fix:** Replace empty catch blocks with proper logging (e.g., `BaritoneAPI.getProvider().getPrimaryBaritone().getLogger().error("Failed to create directory", e);` or equivalent logger). Affected files:
  - `src/main/java/baritone/Baritone.java:101`
  - `src/main/java/baritone/cache/CachedWorld.java:85`
  - `src/main/java/baritone/cache/WorldProvider.java:85` and `91`
  - `src/main/java/baritone/cache/WaypointCollection.java:51`

- **File & Line:** `src/main/java/baritone/command/defaults/TunnelCommand.java:43`
- **Severity:** Medium
- **Bug Type:** Error Handling Gaps
- **Description:** Unsafe parsing of integers directly from user input (`Integer.parseInt(args.getArgs().get(0).getValue())`). If the user provides a non-integer value, it will throw an unhandled `NumberFormatException`, likely crashing the command processing and causing an unhandled exception in the chat or console. Baritone commands usually provide utility methods for parsing with proper error handling (e.g., throwing a `CommandException` if invalid).
- **Reproduction:** Run `#tunnel a b c` or `#tunnel 1.5 2 3`.
- **Fix:** Replace `Integer.parseInt(args.getArgs().get(0).getValue())` with `args.getAs(Integer.class)` (or use the equivalent method provided by `IArgConsumer` like `args.getAsOrDefault` or manual try/catch wrapping in `CommandInvalidTypeException`). E.g., `args.getAs(Integer.class)` per standard Baritone command syntax.

- **File & Line:** `src/main/java/baritone/process/elytra/ElytraBehavior.java:670`
- **Severity:** Medium
- **Bug Type:** Logic Errors
- **Description:** Double/float equality check (`interp == 1.0`). `interp` is a `double`, and floating-point math makes strict equality checks unreliable and prone to logical failure due to precision errors. While in this specific context `interps` are hardcoded to `1.0, 0.75, 0.5, 0.25` so it *may* work, it's generally considered a dangerous practice that can break unexpectedly under different JVM conditions or if the array generation logic is ever modified.
- **Reproduction:** Modify `interps` array or change compilation targets; the exact match may fail.
- **Fix:** Replace `interp == 1.0` with a tolerance check or `Math.abs(interp - 1.0) < 1e-5`, or refactor the hardcoded array out of the logic loop.

- **File & Line:** `src/main/java/baritone/pathing/calc/AStarPathFinder.java:93`
- **Severity:** Medium
- **Bug Type:** Error Handling Gaps
- **Description:** Swallowed `InterruptedException` in the `AStarPathFinder` thread loop (`catch (InterruptedException ignored) {}`). If the pathfinding thread is interrupted (e.g. user cancels the path or system shuts down), swallowing this exception prevents the thread from acknowledging the interrupt signal, potentially causing zombie threads or preventing timely cancellation of path calculations.
- **Reproduction:** Set `slowPath` to true, initiate a large path calculation, and attempt to cancel it immediately. The cancellation might be delayed or ignored if it falls exactly in this window and the interrupt status is cleared without being handled or restored.
- **Fix:** Either re-interrupt the thread with `Thread.currentThread().interrupt();` inside the catch block or add an explicit check to terminate the A* loop if interrupted.

- **File & Line:** `src/main/java/baritone/process/elytra/ElytraBehavior.java:179` and `235`
- **Severity:** Medium
- **Bug Type:** Edge Cases
- **Description:** Missing bounds check on `this.path.get(0)` inside the `thenRun` callback of `pathToDestination`. If `path0` resolves but results in an empty path list (e.g. if the player is already at the destination or pathing immediately fails), `this.path.get(0)` or `this.path.get(this.path.size() - 1)` will throw an `IndexOutOfBoundsException`, causing the completable future to complete exceptionally and potentially breaking the elytra process flow.
- **Reproduction:** Command Baritone to elytra fly to the exact block you are currently standing on, or a completely un-pathable origin, yielding an empty path array.
- **Fix:** Add a check `if (this.path == null || this.path.isEmpty()) return;` at the start of the `thenRun` lambda.

- **File & Line:** `src/main/java/baritone/cache/WaypointCollection.java:67` and `95`
- **Severity:** Low
- **Bug Type:** Incorrect API / Library Usage
- **Description:** Waypoints are being saved with the `.mp4` file extension (typically used for video files) instead of a more appropriate extension like `.dat` or `.bin` or `.json`. While the `DataOutputStream`/`DataInputStream` logic will still read/write correctly, this can cause confusion for users and trigger false associations in the OS.
- **Reproduction:** Add a waypoint in Baritone, and observe the file created in the `baritone/waypoints` cache directory.
- **Fix:** Change `.mp4` to `.dat` or `.bin` (or keep it if it's a known historic idiosyncrasy/joke of Baritone, but from an engineering perspective, it's incorrect).

- **File & Line:** `src/main/java/baritone/cache/CachedRegion.java:306` (and `187`)
- **Severity:** Medium
- **Bug Type:** Error Handling Gaps
- **Description:** Swallowing exceptions during region cache loading and saving. Printing the stack trace (`ex.printStackTrace()`) to stdout does not properly integrate with the Baritone/Minecraft logging system, meaning these errors are likely lost in normal gameplay unless the console is being monitored, and the application simply proceeds as if nothing happened despite the region cache potentially being corrupted.
- **Reproduction:** Intentionally corrupt a cached region file, then restart the client and watch it load.
- **Fix:** Replace `ex.printStackTrace()` with `System.err.println(...)` or a proper logger call, and ideally either handle the corrupted state by deleting the corrupted region file or alerting the user. At minimum, use the proper logger instead of stdout stack traces.
