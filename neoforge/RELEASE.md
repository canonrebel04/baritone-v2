# NeoForge Release Configuration

## Overview
This file documents the NeoForge mod loader configuration for Baritone v2.

## Mod File
- **Location:** `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- **Mod ID:** `baritoe`
- **Version:** `${version}` (inherited from gradle.properties)

## Dependencies
- **NeoForge Version:** `19-beta`
- **Minecraft:** `26.1`
- **Loader Version:** `[1,)` (NeoForge 1.x and above)

## Build Configuration
- **Gradle Subproject:** `neoforge`
- **Build Script:** `neoforge/build.gradle`
- **Mod Type:** FML (Forge Mod Loader - NeoForge variant)

## Release Files
When building, the following JAR files are produced:
- `baritone-api-neoforge-26.1.jar` - API-only release (recommended for distribution)
- `baritone-neoforge-26.1.jar` - Full mod (includes API)

## Build Command
```bash
./gradlew :neoforge:build
```

## Publishing
Releases are published to GitHub Releases with the following naming convention:
- `baritone-api-neoforge-<version>.jar`

## Installation
1. Download the NeoForge JAR from releases
2. Place in the `mods` folder of your NeoForge Minecraft installation
3. Ensure NeoForge 19-beta+ is installed

## Core Mod
- Uses FML core mod system (NeoForge compatible)
- Mixins are configured for runtime modifications

## Compatibility
- Minecraft: 26.1
- Java: 25 (as specified in gradle.properties)
- NeoForge: 19-beta+

## Differences from Forge
- NeoForge is a community-driven fork of Forge
- Maintains compatibility with Forge mods while adding new features
- Uses the same FML loading system
