## 2025-05-21 - Test Suite Needs Java 25
**Learning:** The project's gradle build strictly depends on Java 25 and Unimined/Minecraft transformers which requires a properly configured environment. The project might not be buildable/testable with standard Java 21 tools in the sandbox environment.
**Action:** Proceed with extreme caution and focus on safe, local, self-contained optimizations that do not break standard compilation and use read_file/grep heavily.
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:**  currently uses a custom prime multiplier approach, but the comment says . We already have  directly in  (which is likely the 1.12+  equivalent since it does custom bit shifts). If  uses , we guarantee 0 collisions (it's a bijection) which makes the HashMap  perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the  body with a call to .
## 2025-05-21 - BetterBlockPos.longHash could be BlockPos.asLong()
**Learning:** `BetterBlockPos.longHash` currently uses a custom prime multiplier approach, but the comment says `// TODO use the same thing as BlockPos.fromLong(); invertibility would be incredibly useful`. We already have `serializeToLong` directly in `BetterBlockPos`. If `longHash` uses `serializeToLong`, we guarantee 0 collisions (it's a bijection) which makes the HashMap `O(1)` perfect hashing. This is exactly the kind of optimization that matters for A* search algorithms which perform millions of map lookups.
**Action:** Replace the `longHash` body with a call to `serializeToLong(x, y, z)`.
## 2025-05-21 - Hash collisions with 64-bit coordinate hashes cast to int
**Learning:** In Java, casting a 64-bit `long` to a 32-bit `int` (e.g., `(int) longHash`) truncates the upper 32 bits. For `BetterBlockPos.longHash`, which uses `serializeToLong`, the X coordinate is stored in the upper bits (bits 38 to 63). Truncating it means changes in the X coordinate do not affect the resulting 32-bit hash code, leading to severe hash collisions in `HashMap`/`HashSet` operations during A* pathfinding.
**Action:** Always use `Long.hashCode(long)` (which XORs the upper and lower 32 bits) instead of casting `(int)` when reducing a 64-bit long to a 32-bit int for a hash code, especially when the upper bits contain significant entropy.
