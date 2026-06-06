1. **Bug Type: Logic Errors (Hash Collisions)**
   - **Locations:**
     - `src/api/java/baritone/api/pathing/goals/GoalGetToBlock.java:81`
     - `src/api/java/baritone/api/pathing/goals/GoalTwoBlocks.java:93`
     - `src/api/java/baritone/api/pathing/goals/GoalStrictDirection.java:92`
     - `src/api/java/baritone/api/pathing/goals/GoalNear.java:108`
     - `src/api/java/baritone/api/pathing/goals/GoalBlock.java:87`
     - `src/main/java/baritone/process/BuilderProcess.java:928`
     - `src/main/java/baritone/pathing/calc/PathNode.java:96`
   - **Severity:** High
   - **Description:** When overriding `hashCode()`, the code calls `BetterBlockPos.longHash(x, y, z)` and directly casts the 64-bit result to an `(int)`. However, `BetterBlockPos.longHash` serializes the X, Y, and Z coordinates into a long. The layout (from `BetterBlockPos.java`) places the 26 X bits in the uppermost part of the 64 bits. By casting to `(int)`, the upper 32 bits (which include the entire X coordinate) are completely truncated. This means any two coordinates that only differ in their X coordinate will produce the exact same initial integer value, causing widespread hash collisions. Because A* uses thousands or millions of path nodes stored in hash sets/maps, this creates massive performance degradations and potentially unpredictable lookup behavior due to extreme collision rates.
   - **Reproduction:** Call `new PathNode(100, 50, 50, goal).hashCode()` and `new PathNode(200, 50, 50, goal).hashCode()`. They will return the same value.
   - **Fix:** Replace `(int) BetterBlockPos.longHash(x, y, z)` with `Long.hashCode(BetterBlockPos.longHash(x, y, z))` in all affected areas. This uses the standard XOR fold `(int)(value ^ (value >>> 32))` to preserve information from all 64 bits.

2. Complete pre commit steps
   - Complete pre commit steps to make sure proper testing, verifications, reviews and reflections are done.

3. Submit the change.
   - Once all tests pass, I will submit the change with a descriptive commit message.
