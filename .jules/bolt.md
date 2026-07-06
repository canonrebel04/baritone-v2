## 2024-05-24 - Priority Queue Half-Exchange Optimization
**Learning:** In highly active A* pathfinders, full swaps in binary heaps (sift-up/sift-down) cause unnecessary memory stores by repeatedly updating the moving element's position and array reference.
**Action:** Use a "half-exchange" approach to defer the array assignment and heap position update of the moving element until its final position is found.

## 2024-05-24 - Priority Queue Half-Exchange Optimization
**Learning:** In highly active A* pathfinders, full swaps in binary heaps (sift-up/sift-down) cause unnecessary memory stores by repeatedly updating the moving element's position and array reference.
**Action:** Use a "half-exchange" approach to defer the array assignment and heap position update of the moving element until its final position is found.
