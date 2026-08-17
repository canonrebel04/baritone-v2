## 2025-05-21 - Test Suite Needs Java 25
**Learning:** The project's gradle build strictly depends on Java 25 and Unimined/Minecraft transformers which requires a properly configured environment. The project might not be buildable/testable with standard Java 21 tools in the sandbox environment.
**Action:** Proceed with extreme caution and focus on safe, local, self-contained optimizations that do not break standard compilation and use read_file/grep heavily.
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:**  currently uses a custom prime multiplier approach, but the comment says . We already have  directly in  (which is likely the 1.12+  equivalent since it does custom bit shifts). If  uses , we guarantee 0 collisions (it's a bijection) which makes the HashMap  perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the  body with a call to .
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:** `BetterBlockPos.longHash` currently uses a custom prime multiplier approach, but the comment says `// TODO use the same thing as BlockPos.fromLong(); invertibility would be incredibly useful`. We already have `serializeToLong` directly in `BetterBlockPos`. If `longHash` uses `serializeToLong`, we guarantee 0 collisions (it's a bijection) which makes the HashMap `O(1)` perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the `longHash` body with a call to `serializeToLong(x, y, z)`.
## 2026-06-05 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:** `BetterBlockPos.longHash` currently uses a custom prime multiplier approach, but the comment says `// TODO use the same thing as BlockPos.fromLong(); invertibility would be incredibly useful`. We already have `serializeToLong` directly in `BetterBlockPos`. If `longHash` uses `serializeToLong`, we guarantee 0 collisions (it's a bijection) which makes the HashMap `O(1)` perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the `longHash` body with a call to `BlockPos.asLong(x, y, z)`.

## 2026-06-05 - BetterBlockPos.hashCode needs Long.hashCode()
**Learning:** `BetterBlockPos.longHash` returns a long encoding coordinates. Hashing this to int using a simple `(int)` cast drops the 32 highest bits. Since Minecraft stores the X coordinate entirely inside these 32 highest bits, casting drops the X coordinate, causing severe hash collisions when generating identical Z/Y pos blocks. Using `Long.hashCode()` properly mixes the lower and upper 32 bits avoiding this catastrophic regression.
**Action:** Replaced `(int)` casts to `Long.hashCode()`.
## 2026-06-05 - AStar Inner Loop Floating Point Division
**Learning:** In `AStarPathFinder`, heuristics fallback `bestSoFar` nodes are tracked by scaling `cost` down using an array of `COEFFICIENTS` to determine "distance traveled versus estimated remaining distance". However, floating point division is notoriously slow compared to multiplication. Because this block sits inside the absolute innermost node checking loop (running millions of times per long path execution segment) saving CPU cycles on division is extremely valuable.
**Action:** Replaced `cost / COEFFICIENTS[i]` with multiplication of pre-calculated inverses `cost * COEFFICIENTS_INV[i]` in the base search class.

## 2026-08-08 - Priority Queue Half-Exchange
**Learning:** In AStarPathFinder, the BinaryHeapOpenSet priority queue sift-up and sift-down operations (update, removeLowest) can be optimized using a half-exchange pattern. By deferring the array assignment and heap position update until the end of the loop, memory stores are minimized, providing a performance boost during the millions of map lookups.
**Action:** Replaced full swaps in update and removeLowest with half-exchanges.

## 2025-05-21 - BetterBlockPos.longHash needs serializeToLong over BlockPos.asLong
**Learning:** We replaced `BlockPos.asLong(x, y, z)` with `BetterBlockPos.serializeToLong(x, y, z)` in `BetterBlockPos.longHash` because `BlockPos.asLong` has a bit layout (Z in the middle) that combined with Fastutil's mixing algorithm causes catastrophic Z-axis collisions in the `Long2ObjectOpenHashMap`. Using `serializeToLong` preserves the invertibility but guarantees zero hash collisions for adjacent nodes, making map lookups during A* extremely fast.
**Action:** When implementing spatial hashes for fastutil maps, ensure the serialization format avoids structural collisions with the fastutil mixing algorithm; prefer custom serializers when vanilla bit layouts cause collisions.

## 2026-06-05 - PathNode.hashCode needs Long.hashCode() and BetterBlockPos.longHash()
**Learning:** Naive polynomial hashes (`31 * result + coord`) for 3D block coordinates cause severe hash collisions for spatially local coordinates. This causes a massive performance degradation in algorithms like A* that process millions of spatially contiguous blocks and store them in hash maps/sets (e.g. `PathNode`).
**Action:** Replace `PathNode.hashCode()`'s naive polynomial math with `Long.hashCode(BetterBlockPos.longHash(x, y, z))`. This combines zero-collision perfect packed 64-bit coordinate hashing (from `BetterBlockPos`) with `Long.hashCode` mixing to prevent truncation.
## 2025-05-21 - Hoist Inner Loop Invariants in AStar Pathfinding
**Learning:** In the innermost loop of AStarPathFinder (evaluating up to 18 `Moves` for each of millions of nodes), calculating `currentNode.x >> 4` and `currentNode.z >> 4` dynamically inside the loop causes redundant computation. Since the `currentNode` is fixed for the duration of the loop, its chunk coordinates are invariant.
**Action:** Hoisted the chunk X/Z calculations (`currentNode.x >> 4` and `currentNode.z >> 4`) out of the innermost `for (Moves moves : allMoves)` loop.
