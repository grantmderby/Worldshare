# WorldShare

A Minecraft 1.20.1 Forge mod that enables session-locked cloud sharing of singleplayer worlds
between friends, without a dedicated server.

## Project Status

**Milestone 0 — Project Scaffold.** The mod loads, registers its config, and logs
initialization messages for each of its four functional modules. No functionality yet.

See `docs/DEVELOPMENT.md` for the milestone roadmap. See `docs/SETUP.md` for detailed
development environment setup.

## Quick Start

From a fresh machine:

1. Install JDK 17 (Temurin recommended). `java -version` should show 17.
2. Clone this repo.
3. From the project root, run `./gradlew build` on Linux/macOS or `gradlew.bat build` on Windows.
4. To run the mod in a dev client: `./gradlew runClient`.

See `docs/SETUP.md` for the full walkthrough including IDE setup.

## Project Structure

```
worldshare/
├── build.gradle              Gradle build script
├── gradle.properties         Version pins (Forge, MC, mod version)
├── settings.gradle           Gradle settings + Forge Maven
├── src/main/java/com/worldshare/mod/
│   ├── WorldShareMod.java    Main @Mod class - entry point
│   ├── cloud/                Google Drive sync (M1-M3)
│   ├── relay/                e4mc live-session relay (M4)
│   ├── ui/                   Contributor Worlds screen (M5)
│   ├── modmanager/           Modpack auto-install (M6)
│   ├── config/               ForgeConfigSpec-based settings
│   └── util/                 Shared utilities (SHA-256, etc)
└── src/main/resources/
    ├── META-INF/mods.toml    Forge mod metadata
    ├── pack.mcmeta           Resource pack format
    └── assets/worldshare/    Localization files
```

## License

MIT
