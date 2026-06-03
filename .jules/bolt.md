## 2025-05-21 - Test Suite Needs Java 25
**Learning:** The project's gradle build strictly depends on Java 25 and Unimined/Minecraft transformers which requires a properly configured environment. The project might not be buildable/testable with standard Java 21 tools in the sandbox environment.
**Action:** Proceed with extreme caution and focus on safe, local, self-contained optimizations that do not break standard compilation and use read_file/grep heavily.
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:**  currently uses a custom prime multiplier approach, but the comment says . We already have  directly in  (which is likely the 1.12+  equivalent since it does custom bit shifts). If  uses , we guarantee 0 collisions (it's a bijection) which makes the HashMap  perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the  body with a call to .
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:** `BetterBlockPos.longHash` currently uses a custom prime multiplier approach, but the comment says `// TODO use the same thing as BlockPos.fromLong(); invertibility would be incredibly useful`. We already have `serializeToLong` directly in `BetterBlockPos`. If `longHash` uses `serializeToLong`, we guarantee 0 collisions (it's a bijection) which makes the HashMap `O(1)` perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the `longHash` body with a call to `serializeToLong(x, y, z)`.
## 2025-05-21 - Hash Collision Optimization
**Learning:** Directly casting `BetterBlockPos.longHash(x, y, z)` to `(int)` truncated the top 32 bits. Because the hash layout packed the 26-bit X coordinate and the top 6 bits of the Y coordinate into those upper bits, casting to `int` completely removed them, leading to massive hash collisions (all blocks with same Z and lower Y bits collided). Using `Long.hashCode(longHash)` properly mixes all bits using XOR `(int)(value ^ (value >>> 32))`.
**Action:** Always use `Long.hashCode()` when converting 64-bit coordinate hashes to 32-bit integers, to avoid disastrous collisions in A* `HashSet`/`HashMap` lookups.
