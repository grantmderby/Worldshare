# Development Environment Setup

From a fresh machine to a running WorldShare dev client.

> This guide was rewritten after the project moved from Forge 1.20.1 to
> **NeoForge 1.21.1**. If you find instructions elsewhere mentioning Java 17,
> ForgeGradle, or fetching the Gradle wrapper from a Forge MDK, they predate that
> move and will not work.

## 1. Install prerequisites

### Java 21 (required)

NeoForge 1.21.1 requires **Java 21** — Minecraft moved up from 17 in 1.20.5, and
`build.gradle` pins the toolchain to 21. Java 17 will not build this project.

- Install [Eclipse Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21)
- Verify: `java -version` and `javac -version` should both print `21.x.x`

If you keep several JDKs around, point `JAVA_HOME` at 21 rather than uninstalling
the others:

- **Windows (PowerShell):** `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot"`
- **macOS:** `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- **Linux:** `export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64`

Gradle's toolchain support means it may find a suitable JDK on its own, but
setting `JAVA_HOME` explicitly avoids a class of confusing failures.

### IntelliJ IDEA Community (recommended)

[Download here](https://www.jetbrains.com/idea/download/) — scroll to Community
Edition, which is free. Eclipse and VS Code work, but IntelliJ's Gradle
integration is the smoothest for Minecraft modding.

### Git

[git-scm.com/downloads](https://git-scm.com/downloads) if you don't have it.

## 2. Build

The Gradle wrapper is committed to the repository, so there is nothing to fetch
first. From the project root:

**Windows:**
```
gradlew.bat build
```

**macOS / Linux:**
```
./gradlew build
```

**What to expect on a first build:** Gradle 8.8 downloads (~100 MB), then
NeoGradle pulls and processes Minecraft 1.21.1. Budget **5–20 minutes**.
Incremental builds afterwards take seconds.

Two jars land in `build/libs/`:

- `worldshare-<version>.jar` — the real one, with the Google API libraries shaded in
- `worldshare-<version>-slim.jar` — intermediate, no dependencies bundled, not distributable

NeoForge uses official mappings end to end, so there is no reobfuscation step —
what Shadow produces is what ships.

## 3. Google credentials

The mod can build without these, but it can't talk to Drive. See
[GOOGLE_CLOUD_SETUP.md](GOOGLE_CLOUD_SETUP.md) for how to create them, then place
`client_secret.json` at either:

- `src/main/resources/worldshare/oauth/client_secret.json` — bundled at build time
- `<gamedir>/config/worldshare/client_secret.json` — loaded at runtime, takes
  precedence, so you can swap credentials without rebuilding

Both are gitignored. Don't commit either.

## 4. Run the dev client

```
./gradlew runClient
```

This launches Minecraft 1.21.1 with WorldShare loaded from source — no copying
jars into a `mods/` folder. Its game directory is `run/`, so worlds live in
`run/saves/` and logs in `run/logs/latest.log`.

**Quick verification:**

1. Title screen appears, with a **Contributor Worlds** button below Multiplayer
2. **Mods** lists WorldShare
3. In a singleplayer world, `/worldshare` tab-completes its subcommands

For anything involving Drive, work through
[E2E_TEST_PLAN.md](E2E_TEST_PLAN.md) rather than poking at it ad hoc — several
failure modes in this design look like success from the outside.

## 5. Import into IntelliJ

1. **File → Open…** → select the folder containing `build.gradle`
2. Accept the prompts to trust and load the Gradle project
3. Wait for indexing — first time can be several minutes
4. `runClient`, `runServer`, `runData` and `runGameTestServer` appear in the run
   configuration dropdown

Breakpoints and the Debug button beside `runClient` are considerably more
pleasant than scattering log statements, particularly for the OAuth flow, which
is hard to reason about from logs alone.

## 6. Testing Drive behaviour without launching the game

`tools/oauth-picker-prototype/` holds standalone Python scripts that exercise the
Drive API paths the mod depends on — the consent-plus-picker flow, per-file grant
persistence, and what a folder grant does and doesn't reach. Its README explains
each one.

Every claim in [CLOUD_BACKEND_DECISION.md](CLOUD_BACKEND_DECISION.md) was
established with these, and a round trip through them takes seconds against
minutes for a game restart. Reach for them first whenever you're about to assume
something about Drive.

## 7. Common first-time issues

**`Unsupported class file major version 65` (or similar):**
Gradle is running on the wrong JDK. Check `JAVA_HOME` points at Java 21.

**`Could not resolve net.neoforged:neoforge`:**
Usually transient. Retry; if it persists, check your firewall allows
`maven.neoforged.net`.

**`OAuth client_secret.json not found`:**
Expected until you complete step 3. The mod builds and launches fine without it —
only Drive operations fail, and the exception says where to put the file.

**IntelliJ shows red squiggles on `net.minecraft.*` imports:**
Gradle hasn't finished importing. Wait for indexing, or run
`./gradlew --refresh-dependencies` and reload the Gradle project.

**Dev client launches but Contributor Worlds is missing:**
Check `run/logs/latest.log` for a mod loading error. WorldShare registers its UI
during client setup, so a failure there is usually visible as a stack trace early
in the log.

## 8. Where to look next

- [CLOUD_BACKEND_DECISION.md](CLOUD_BACKEND_DECISION.md) — why the storage design
  is the shape it is, and what was tested to rule out the alternatives. Read this
  before changing anything in `sync/` or `cloud/`.
- [E2E_TEST_PLAN.md](E2E_TEST_PLAN.md) — how to verify a change end to end.
- [DEVELOPMENT.md](DEVELOPMENT.md) — module layout and milestone history.
