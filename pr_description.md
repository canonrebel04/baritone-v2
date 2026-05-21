Title: 🧹 Ensure water matching only applies to pure water in BuilderProcess

Description:
🎯 **What:** Modified the liquid check in `BuilderProcess.java` to explicitly check for `Fluids.WATER` in the fluid state.
💡 **Why:** This ensures that the water matching logic strictly applies to pure water blocks and not to arbitrary waterlogged blocks or other fluids, fulfilling a long-standing TODO note. This improves readability and precisely bounds the matching behavior.
✅ **Verification:** Verified the code compiles mentally, checked `BlockState` API methods, and ran `./gradlew check`.
✨ **Result:** The condition `if (state.getBlock() instanceof LiquidBlock && state.getFluidState().getType() == Fluids.WATER)` handles exactly what is needed for pure water, improving code health without changing intended functionality.
