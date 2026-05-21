# Forge Release Configuration

## Overview
This file documents the Forge mod loader configuration for Baritone v2.

## Mod File
- **Location:** `forge/src/main/resources/META-INF/mods.toml`
- **Mod ID:** `baritoe`
- **Version:** `${version}` (inherited from gradle.properties)

## Dependencies
- **Forge Version:** `62.0.9`
- **Minecraft:** `26.1`
- **Loader Version:** `[62,)` (Forge 62.x and above)

## Build Configuration
- **Gradle Subproject:** `forge`
- **Build Script:** `forge/build.gradle`
- **Mod Type:** FML (Forge Mod Loader)

## Release Files
When building, the following JAR files are produced:
- `baritone-api-forge-26.1.jar` - API-only release (recommended for distribution)
- `baritone-forge-26.1.jar` - Full mod (includes API)

## Build Command
```bash
./gradlew :forge:build
```

## Publishing
Releases are published to GitHub Releases with the following naming convention:
- `baritone-api-forge-<version>.jar`

## Installation
1. Download the Forge JAR from releases
2. Place in the `mods` folder of your Forge Minecraft installation
3. Ensure Forge 62.0.9+ is installed

## Core Mod
- Uses FML core mod system
- Mixins are configured for runtime modifications

## Compatibility
- Minecraft: 26.1
- Java: 25 (as specified in gradle.properties)
- Forge: 62.0.9+
