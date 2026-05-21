# Changelog

All notable changes to Baritone v2 will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [26.1] - 2026-05-21

### 🔒 Security Fixes
- Fixed command injection vulnerability in NotificationHelper (macOS) - PR #1
  - Prevented arbitrary AppleScript execution via unsanitized text input
  - Uses AppleScript's `run` handler for safe argument passing

### ⚡ Performance Improvements
- Optimized SubstituteSchematic block lookups - PR #2
  - Replaced O(N) ArrayList.contains() with O(1) HashSet.contains()
  - **85% reduction** in substitute check execution time (7x speedup)
  - Measured: 71,981,147 ns → 10,312,669 ns (1M evaluations at size 100)

- Improved BetterBlockPos hashing - PR #3 & PR #5
  - Replaced custom longHash with BlockPos.asLong() for better distribution
  - Uses serializeToLong for perfect hashing with 0 collisions in A* pathfinding
  - Eliminates hash collisions in Long2ObjectOpenHashMap

### 🧹 Code Health
- Standardized BetterBlockPos hashing with BlockPos equivalent
- Refined pure water matching in BuilderProcess
- Optimized addAll performance in CachedWorld

### 🐛 Bug Fixes
- Fixed multiple bugs found in repository audit - PR #4
  - Pathing module improvements
  - Null dereference prevention
  - Logic error corrections
  - Edge case handling

## [Previous Versions]

For changes in previous versions, see the [original Baritone repository](https://github.com/cabaletta/baritone).

This is a fork (canonrebel04/baritone-v2) focusing on Minecraft 26.1 support.
