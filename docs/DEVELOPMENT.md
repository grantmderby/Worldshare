# Development Roadmap

WorldShare is built in vertical slices. Each milestone produces something testable on
its own, rather than building all the plumbing before any of it works.

## Milestones

### ✅ M0 — Project Scaffold (current)

Forge mod loads, registers config, logs init from each module.

**Done when:** `./gradlew runClient` opens MC, Mods list shows WorldShare, log shows
all four module init messages.

### 🔜 M1 — Google Drive Auth & Basic File I/O

The mod can OAuth to Drive, upload a file, download it, and hash it correctly.

**Components:**
- `DriveClient` — wraps Drive v3 API (upload, download, readJson, writeJson)
- `OAuthFlow` — one-time browser consent + silent refresh token reuse
- Token persistence — encrypted at rest in the game dir's `config/worldshare/`
- Dev smoke test — a hidden debug keybind that uploads a test file and verifies the hash

**Done when:** You can run the client, trigger the debug keybind, see a file appear in
your Drive folder, delete it locally, re-trigger, see it come back identical.

### 🔜 M2 — Session Lock System

The lock file works in all scenarios (acquire, release, heartbeat, stale detection).

**Components:**
- `LockManager` — acquire/release/read/heartbeat
- `session.lock` JSON schema (holder, timestamps, status, players_online, relay_address)
- Stale lock popup UI
- Heartbeat background thread (refreshes `expires_at` every 30 minutes)

**Done when:** Two dev clients on the same machine (via `runClient` + a second launcher
install) can take turns holding the lock, and the stale-lock popup appears correctly
when a lock expires without release.

### 🔜 M3 — Cloud Sync Engine (Offline Mode)

Solo play correctly syncs world files in and out.

**Components:**
- `WorldManifest` — maps tracked file paths to SHA-256 hashes + mtimes
- `SyncEngine` — `preFlightSync()` before load, `shutdownSync()` after save
- Tracked file list (region, v_data, level.dat, per-UUID playerdata/stats/advancements)
- Atomic commit via `manifest_pending.json` → rename to `manifest.json`
- Hooks into `WorldEvent.Load` and `ServerStoppingEvent`

**Done when:** Play on machine A, exit, open on machine B, changes are there and no
corruption. Includes a dummy VS mod `v_data/` folder to exercise the physics-layer sync.

### 🔜 M4 — Relay Engine (Live Co-op Mode)

When one player is hosting, others can join directly instead of syncing.

**Components:**
- `E4mcBridge` — detects e4mc's generated public address on world open
- Publishes relay address into the session lock
- `HostPresence` — maintains `players_online` while hosting
- Client-side: if lock status is `"hosting"`, show [Join] button instead of [Play]
- e4mc becomes `mandatory=true` in mods.toml

**Done when:** A opens world, B sees "Hosted by A" and clicks Join, both are in the
same game session. On last-player-exit, shutdown sync runs as normal.

### 🔜 M5 — Contributor Worlds UI Tab

The world selection screen has a new tab listing shared worlds.

**Components:**
- Mixin into `SelectWorldScreen` (or `TitleScreen`, tbd) to add the tab
- Background poller refreshes lock state every 30s
- Per-world entry UI: name, status badge, host name, player count, action button
- "Add Contributor World" button accepts a shared Drive folder ID
- Settings screen for display name + player cap

**Done when:** Tab is visible, switches from "Available" to "🟢 Live" when other player
opens, clicking action buttons routes correctly.

### 🔜 M6 — Mod Manager

One-click modpack install for new contributors.

**Components:**
- `modpack.json` manifest generator (scans current `mods/`, looks up Modrinth IDs)
- `ModrinthClient` — resolves project/version IDs to download URLs via public API
- `LocalModScanner` — hashes current `mods/` contents
- Install dialog: shows diff vs manifest, one button installs all missing
- Always includes e4mc + WorldShare itself in generated manifests

**Done when:** Fresh install with only Forge + WorldShare can open Contributor Worlds
tab, see "12 missing mods", click install, restart, and launch successfully.

### 🔜 M7 — Hardening & Polish

The mod is trustworthy enough for daily use.

**Components:**
- VS ship UUID sanity check (v_data refs must match region chunks)
- Interrupted session recovery flow
- Upload progress overlay
- Offline mode flag (play without sync when Drive unreachable)
- In-game settings screen
- `worldshare.log` structured logging

**Done when:** You and your brother can use this for a month without touching the
config files or logs manually.

## Dependency Graph

```
M0 ──┬── M1 ──┬── M2 ──┬── M3 ──┬── M5
     │        │        │        │
     │        │        │        └── M4
     │        │        │
     │        │        └── M6 (can parallel with M4)
     │        │
     │        └── (nothing else blocks on auth alone)
     │
     └── M7 (runs continuously from M3 onward)
```

## Code Conventions

**Logging:** Use `WorldShareMod.LOGGER` (SLF4J). Never `System.out`.
Log levels:
- `error` — something is broken and the user should know
- `warn` — something is wrong but the mod recovered
- `info` — lifecycle events the user might care about (sync start, lock acquire)
- `debug` — details useful to us for troubleshooting

**Threading:**
- Anything touching Drive or the network **must not** run on the game thread.
  Use `CompletableFuture` or a dedicated executor service owned by the cloud module.
- Anything touching Minecraft's world/player/entity state **must** run on the game
  thread. Use `Minecraft.getInstance().execute(...)` to schedule back onto it.

**File I/O:**
- Always use `java.nio.file.Path`, never `java.io.File`, for anything written after M0.
- Prefer streaming (`Files.newInputStream` + buffer) over `Files.readAllBytes` for any
  file that might exceed a few MB — region files can be 20MB+.

**Testing:**
- Unit tests go in `src/test/java`, mirroring package structure.
- For anything involving Drive, use a recorded-response fake rather than hitting the
  real API during tests.

**Error handling:**
- Cloud errors should surface to the user in a screen or toast, never silently fail.
- Local file errors should fail loud in the log and abort the sync attempt rather than
  corrupt in-progress state.
