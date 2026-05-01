# Development Environment Setup

This guide walks you from a fresh machine to a running WorldShare dev client.
Target audience: you (the author) and any future collaborators.

## 1. Install Prerequisites

### Java 17 (Required)

Forge 1.20.1 requires **exactly Java 17**. Java 21 will silently break ForgeGradle,
and Java 8/11 won't work at all.

- **Windows / macOS / Linux:** Install [Eclipse Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17)
- Verify with: `java -version` — must print `17.x.x`
- Verify with: `javac -version` — must also print `17.x.x`

If you already have a different Java version installed and your `java -version` shows
something else, you don't need to uninstall it. Just make sure `JAVA_HOME` points to
the Java 17 install:

- **Windows (PowerShell):** `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"`
- **macOS / Linux (bash/zsh):** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` on macOS,
  or `export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64` on Linux.

### IntelliJ IDEA Community (Recommended)

- Download: https://www.jetbrains.com/idea/download/ (scroll to "Community Edition", it's free)
- Minecraft modding works great with IntelliJ. Eclipse and VS Code work too but IntelliJ's
  Gradle integration is the smoothest.

### Git

- If you don't have it: https://git-scm.com/downloads

## 2. Get the Gradle Wrapper

Our project was scaffolded without `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`
because these are binary/executable files that Claude couldn't generate directly.
You need to fetch them from the official Forge MDK:

1. Go to https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html
2. Find the **recommended** build (as of writing, 1.20.1 - 47.2.0 or similar - match
   what's pinned in our `gradle.properties` if possible)
3. Under the "Download Recommended" box click **Mdk** (not Installer, not Universal)
4. Unzip the MDK somewhere temporary
5. Copy exactly these files from the MDK into our project root, preserving paths:

   ```
   gradlew                            → worldshare/gradlew
   gradlew.bat                        → worldshare/gradlew.bat
   gradle/wrapper/gradle-wrapper.jar  → worldshare/gradle/wrapper/gradle-wrapper.jar
   ```

6. **Do not copy anything else.** In particular do NOT copy the MDK's `build.gradle`,
   `gradle.properties`, `settings.gradle`, `src/`, or `.gitignore` — we've
   written our own versions of those and the MDK's will clobber them.

7. On macOS/Linux, make the wrapper executable:
   ```bash
   chmod +x gradlew
   ```

## 3. First Build (Expect It To Be Slow)

From the project root:

**Windows:**
```cmd
gradlew.bat build
```

**macOS / Linux:**
```bash
./gradlew build
```

**What to expect:**
- First run downloads Gradle 8.4 (~100MB)
- Then downloads ForgeGradle and the MDK bootstrap
- Then downloads and decompiles Minecraft 1.20.1 — this step alone can take 10+ minutes
- Then compiles our mod

Total time on the first build: **5-20 minutes** depending on your internet. After that,
incremental builds are a few seconds.

If you see `BUILD SUCCESSFUL`, you're done. The compiled mod jar lives in `build/libs/`.

## 4. Run the Dev Client

```bash
./gradlew runClient
```

This launches a full Minecraft 1.20.1 Forge client with WorldShare already loaded.
No need to copy jars into a `mods/` folder during development — this is faster and lets
you edit code and restart.

**Verification checklist:**
1. Minecraft title screen appears
2. Click **Mods** — you should see "WorldShare" in the list with description
3. Quit Minecraft
4. Open `run/logs/latest.log`
5. Search for these four lines:
   ```
   CloudModule initialized (stub - no Drive calls yet).
   RelayModule initialized (stub - no relay integration yet).
   ModManagerModule initialized (stub - no modpack sync yet).
   UiModule initialized (stub - no screens registered yet).
   ```
6. If all four appear, **Milestone 0 is complete.** 🎉

## 5. Import Into IntelliJ IDEA

1. Launch IntelliJ
2. **File → Open...** → select the `worldshare` project folder (the folder containing
   `build.gradle`, not a parent)
3. IntelliJ will detect it as a Gradle project. Accept any prompts about trusting it and
   loading the Gradle project.
4. Wait for indexing (first time is slow — could be 2-5 minutes as it scans the decompiled MC source)
5. In the top-right run config dropdown you should see **runClient**, **runServer**,
   **runData**, **runGameTestServer** as run configurations. If you don't:
   - Open the Gradle panel (right side)
   - Run the task `ForgeGradle → genIntellijRuns`
   - Reload the Gradle project

Now you can set breakpoints anywhere in our code, hit the green Debug button next to
`runClient`, and step through. This is *significantly* easier than dropping print
statements everywhere.

## 6. Common First-Time Issues

**`Unsupported class file major version 61` or similar:**
You're running Gradle with the wrong Java version. Check `JAVA_HOME` is Java 17.

**`Could not resolve net.minecraftforge:forge`:**
Usually a transient network issue. Try again. If persistent, check your firewall
allows `maven.minecraftforge.net`.

**`FileNotFoundException` for `gradle-wrapper.jar`:**
You skipped Step 2. Go grab the wrapper files from the Forge MDK.

**Dev client crashes on startup with a mixin error:**
We don't use mixins in Milestone 0, so this would be surprising. Check `run/logs/latest.log`
for the full stack trace.

**IntelliJ shows red squiggles on `net.minecraftforge.*` imports:**
Gradle hasn't finished importing. Wait for the status bar indexing to finish, or run
`./gradlew --refresh-dependencies` and re-import the project.

## 7. What to Do Next

Once M0 is verified working, you're ready for **Milestone 1: Google Drive Auth & I/O**.
The plan:
- Set up a Google Cloud project and enable the Drive API
- Create OAuth credentials for a "Desktop" application
- Implement the `DriveClient` class
- Implement the one-time OAuth flow

That's a separate conversation. Come back when you have a green Mods list with
"WorldShare" in it.
