# Baritone v2 - Release Information

## Current Version
- **Baritone Version:** `26.1-SNAPSHOT`
- **Minecraft Version:** `26.1`
- **Java Version:** `25`

## Supported Mod Loaders
Baritone v2 supports the following Minecraft mod loaders:

| Loader | Version | Status |
|--------|---------|--------|
| **Fabric** | 0.18.6+ | ✅ Supported |
| **Forge** | 62.0.9+ | ✅ Supported |
| **NeoForge** | 19-beta+ | ✅ Supported |

## Release Files

Each mod loader has its own release JAR file:

### Fabric
- **File:** `baritone-api-fabric-26.1.jar`
- **Configuration:** [fabric/RELEASE.md](fabric/RELEASE.md)
- **Mod File:** `fabric/src/main/resources/fabric.mod.json`

### Forge
- **File:** `baritone-api-forge-26.1.jar`
- **Configuration:** [forge/RELEASE.md](forge/RELEASE.md)
- **Mod File:** `forge/src/main/resources/META-INF/mods.toml`

### NeoForge
- **File:** `baritone-api-neoforge-26.1.jar`
- **Configuration:** [neoforge/RELEASE.md](neoforge/RELEASE.md)
- **Mod File:** `neoforge/src/main/resources/META-INF/neoforge.mods.toml`

## Quick Download Links

| Loader | Download Link |
|--------|---------------|
| **Fabric** | [baritone-api-fabric-26.1.jar](https://github.com/canonrebel04/baritone-v2/releases/download/v26.1/baritone-api-fabric-26.1.jar) |
| **Forge** | [baritone-api-forge-26.1.jar](https://github.com/canonrebel04/baritone-v2/releases/download/v26.1/baritone-api-forge-26.1.jar) |
| **NeoForge** | [baritone-api-neoforge-26.1.jar](https://github.com/canonrebel04/baritone-v2/releases/download/v26.1/baritone-api-neoforge-26.1.jar) |

## Building from Source

### Prerequisites
- Java 25 JDK
- Git
- Gradle (included in wrapper)

### Build All Versions
```bash
./gradlew build
```

### Build Specific Loader
```bash
# Fabric only
./gradlew :fabric:build

# Forge only
./gradlew :forge:build

# NeoForge only
./gradlew :neoforge:build
```

### Output Location
Build artifacts are located in:
- `fabric/build/libs/`
- `forge/build/libs/`
- `neoforge/build/libs/`

## Version Information

### Version Scheme
- **Format:** `<minecraft-version>-SNAPSHOT` or `<minecraft-version>`
- **Example:** `26.1-SNAPSHOT` (development), `26.1` (release)

### Version Configuration
Version information is centralized in `gradle.properties`:
```properties
mod_version=26.1-SNAPSHOT
minecraft_version=26.1
java_version=25
```

## Release Process

1. Update version in `gradle.properties` (remove `-SNAPSHOT`)
2. Update changelog (if applicable)
3. Build all versions: `./gradlew build`
4. Create GitHub Release with version tag
5. Upload JAR files to release
6. Update version back to `-SNAPSHOT` for development

## Changelog

### v26.1 (Current)
- Initial version for Minecraft 26.1
- Support for Fabric, Forge, and NeoForge
- All features from previous versions adapted for 26.1

## Installation Instructions

### Fabric
1. Install [Fabric Loader](https://fabricmc.net/use/) 0.18.6+
2. Download `baritone-api-fabric-26.1.jar`
3. Place JAR in `mods` folder
4. Launch Minecraft

### Forge
1. Install [Forge](https://files.minecraftforge.net/) 62.0.9+
2. Download `baritone-api-forge-26.1.jar`
3. Place JAR in `mods` folder
4. Launch Minecraft

### NeoForge
1. Install [NeoForge](https://neoforged.net/) 19-beta+
2. Download `baritone-api-neoforge-26.1.jar`
3. Place JAR in `mods` folder
4. Launch Minecraft

## Support

For support, join the [Baritone Discord Server](http://discord.gg/s6fRBAUpmr).

## License

Baritone is licensed under LGPL-3.0. See [LICENSE](LICENSE) for details.
