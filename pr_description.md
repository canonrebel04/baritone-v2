🎯 **What:** Removed the hacky, memory-intensive `DoubleOpenHashSet` logic from `GoalNear.heuristic()` and `GoalRunAway.heuristic()` and replaced it with a much more efficient, allocation-free, two-pass iteration algorithm.

💡 **Why:**
The previous solution instantiated a `DoubleOpenHashSet` whenever `heuristic()` was called, collecting all heuristic values from coordinates inside the goal just to find the max that is less than `minOutside`. This adds unnecessary garbage collection pressure and memory usage, especially since these heuristics are frequently invoked during pathfinding operations.

The new implementation:
1. Performs an initial pass purely to establish `minOutside` for surrounding blocks.
2. Performs a second pass over the goal volume to find `maxInside` strictly utilizing primitive `double`s, eliminating object creation entirely and reducing memory overhead.

✅ **Verification:**
1. Ran full `GoalNearTest` suite locally via `java`. Verified `GoalNear` and `GoalRunAway` produce exactly identical heuristic outputs across 30 different radii.
2. `./gradlew test` confirms no core pathing regression.

✨ **Result:** Identical algorithmic behavior but completely allocation-free. A noticeably cleaner and more performant code base.
