# WorldShare

A Minecraft mod that lets two players share a single singleplayer world across the internet via Google Drive — with no dedicated server.

Either player can open the world solo. When both are online simultaneously, they play live via the e4mc relay. A session lock on Google Drive prevents corruption from concurrent edits, and world files sync automatically using a snapshot-based atomic commit pattern.

---

## Why I Built This

I wanted to play multiplayer with my brother (he's in Utah, I'm in Texas) without either of us having to maintain a dedicated server. WorldShare lets either of us boot up the world solo, with our changes synced to Drive when we save and quit. When we're both online, we connect live through e4mc.

The interesting engineering problem is what happens when both people want to play. You can't just sync files naively — you'll corrupt the world. You need session locking. You need to handle the case where someone's computer crashes while holding the lock. You need atomic commits so a half-uploaded world doesn't replace the good one on Drive. That's most of what this mod is.

---

## Architecture

### Session Locking

A `session.lock` JSON file on Google Drive identifies the current player. Five lock states:

| State | Meaning |
|-------|---------|
| FREE | No active session |
| MINE | I hold the lock |
| THEIRS | Someone else holds the lock |
| STALE_MINE | I held the lock, last heartbeat is too old (probably crashed) |
| STALE_THEIRS | They held the lock, last heartbeat is too old |

The lock is refreshed every 15 minutes via a heartbeat. If a heartbeat is missed for the configured expiry (default 8 hours), the lock becomes stale and can be claimed by the other player after a confirmation prompt.

### Atomic Commits

Snapshot-based upload: world files are copied to a temp directory before upload, ensuring Minecraft can't write to them mid-transfer. Upload sequence:

1. Snapshot world directory to temp
2. Upload all changed files to Drive
3. Write `manifest_pending.json` with new hashes
4. Atomic rename: `manifest_pending.json` → `manifest.json`

If anything fails, the old manifest is untouched. There is no partial state on Drive.

### OAuth 2.0 Without Jetty

The Google API Java client wants a full embedded web server (Jetty) for the OAuth redirect. That's a 5+ MB dependency tree just to receive one HTTP request. Instead, this mod uses a hand-rolled `LocalRedirectReceiver` — a single-purpose `ServerSocket` that listens on `localhost`, parses the redirect URL, and shuts down. Tokens are stored at `config/worldshare/tokens/StoredCredential` and cached for subsequent launches.

### Save and Upload UX

When the player clicks Escape during a singleplayer session, the vanilla "Save and Quit to Title" button is replaced with "Save and Upload to Drive" (via `ScreenEvent.Init.Post` — no Mixin needed). Clicking it shows a progress screen with file-by-file upload status, a Cancel button, and a "Continue in Background" option that appears after 30 seconds.

A separate `AutoSyncListener` handles the case where the player closes the window via the X button: it captures the world path on `ServerStoppingEvent` and triggers a push on `ServerStoppedEvent`. A suppression token prevents double-pushing when the UI screen is already handling the upload.

---

## Tech Stack

- **Java 21** (required by Minecraft 1.21.1)
- **Gradle** (NeoGradle 7.0.x)
- **NeoForge 1.21.1** (Minecraft modding framework)
- **Google Drive API v3** (Java client, shaded into mod jar)
- **e4mc relay** (live multiplayer hosting via reverse-tunnel)

---

## Commands

All commands are under `/worldshare`. No special permissions required.

| Command | Description |
|---------|-------------|
| `/worldshare test` | OAuth roundtrip — opens browser if no cached token |
| `/worldshare signout` | Delete stored OAuth tokens |
| `/worldshare setfolder <url-or-id>` | Configure Drive folder |
| `/worldshare lock` | Acquire session lock on Drive |
| `/worldshare unlock` | Release session lock |
| `/worldshare lockinfo` | Show lock state |
| `/worldshare push` | Manual push to Drive |
| `/worldshare pull` | Pull from Drive to local |
| `/worldshare status` | Dry-run: show what would change |

---

## Project Status

| Milestone | Status |
|-----------|--------|
| M0 — Project scaffold + config | Complete |
| M1 — Google Drive OAuth | Complete |
| M2 — Session locking | Complete |
| M3 — Sync engine + Save and Upload UI | Complete (6 of 6 tests passing) |
| Forge 1.20.1 → NeoForge 1.21.1 migration | In progress |
| M4 — Live co-op via e4mc | Designed, not yet built |
| M5 — Title screen UI + world gating | Planned |
| M6 — Mod jar bundling for distribution | Planned |

---

## Key Technical Lessons

### Event registration: class vs instance

```java
// WRONG — instance registration doesn't find static methods:
NeoForge.EVENT_BUS.register(new AutoSyncListener());

// CORRECT — for classes with @SubscribeEvent on static methods:
NeoForge.EVENT_BUS.register(AutoSyncListener.class);
```

This took hours to diagnose. Events were firing on the bus but no handler was running. The fix is one character (adding `.class`).

### `clearLevel()` overload matters in singleplayer

`mc.clearLevel()` (no args) takes the multiplayer client disconnect path and does NOT stop the integrated server. In singleplayer, you must use `mc.clearLevel(new GenericDirtMessageScreen(...))` — this is the path that BLOCKS the render thread until the integrated server is fully stopped, allowing `ServerStoppedEvent` to fire predictably.

### Suppression token timing

The token that prevents double-pushing must be set BEFORE `mc.level.disconnect()`, not after. `ServerStoppedEvent` fires inside the disconnect call, and if the token isn't set yet, the auto-sync listener fires its push concurrently with the UI's push.

---

## License

MIT

---

*Built by Grant Derby. Computer Engineering student, Brigham Young University.*
