#!/bin/bash
cat << 'EOP'
1. *Fix `Optional.get()` bug in `PathingBehavior.java`*
    - The code calls `ticksRemainingInSegment(false).get()` without checking if it is present. I will fix it by wrapping it in `Optional<Double> ticksRemaining = ticksRemainingInSegment(false);` and then checking `ticksRemaining.isPresent() && ticksRemaining.get() < ...`.
2. *Run the test suite*
    - I will run `./gradlew check` to verify the codebase compiles and passes all tests.
3. *Complete pre-commit steps*
    - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
EOP
