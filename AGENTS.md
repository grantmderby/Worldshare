# WorldShare — Agent Instructions

NeoForge 1.21.1 mod. Shared singleplayer world between two players via Google Drive +
e4mc relay. No dedicated server. Java 21, Windows 11, Gradle + NeoGradle 7.0.145.

Project path: `C:\Users\Grant\claudeProjects\worldShare\`
Package root: `com.worldshare.mod`

---

## Current State

M8 complete and shipped. The mod is in daily use. Do not suggest re-architecting unless
there is a concrete bug. All data-safety invariants below are load-bearing — do not break them.

---

## Architectural Invariants

These have been established through real bugs and testing. Violating them will cause
data loss, corruption, or hard-to-diagnose crashes.

1. **`NeoForge.EVENT_BUS.register(AutoSyncListener.class)`** — register the CLASS, not an instance. Same for `DirtyRegionTracker.class`.
2. **Suppression token set BEFORE `mc.level.disconnect()`** in PauseMenuHijacker. Order matters.
3. **`mc.disconnect(new GenericMessageScreen(...))`** for singleplayer disconnect — not `mc.level.disconnect()`.
4. **`E4mcCoordinator` registered in `UiModule.init()`** — client-only, must not run on dedicated server.
5. **`detachLogAppender()` called FIRST** in `stopHostingIfActive()` before any other cleanup.
6. **`worldshare-link.json` and `worldshare-scan-cache.json` excluded from `TrackedPaths.isTracked()`** — they are per-machine files, not world state.
7. **No em-dashes or non-ASCII in chat messages** — use `[LOCK]`, `[OK]`, `[!]` etc. Basic emoji (in Unifont) is fine.
8. **`CloudModule.executor()` is single-threaded** — never call `submit(...).get()` from within a task already on that executor. Deadlock.
9. **`WorldShareMod` constructor takes `ModContainer container`** as second parameter.
10. **`mc.createWorldOpenFlows().openWorld(folderName, () -> {})`** — confirmed working API for opening worlds programmatically.
11. **`org.apache.commons` must be EXCLUDED (not relocated) in shadowJar** — NeoForge's `ModConfigSpec` uses the real `org.apache.commons.lang3.tuple.Pair`. Relocating it breaks this.
12. **`NbtIo.readCompressed(Path, NbtAccounter.unlimitedHeap())`** — correct API for 1.21.1. Do not use the File-based overload.
13. **Upload pool size 4, `WorldStateResolver` pool size 6** — tuned for residential bandwidth + Drive politeness.
14. **Lock check before `commitManifest`** in both `push()` AND `pushFirstTime()` — prevents overwriting the other player's work if the lock was stolen mid-upload.
15. **`DriveClient.downloadFile()` uses `executeMedia()`** not `executeMediaAndDownloadTo()` — the latter uses chunked range requests that fail on 0-byte files (HTTP 416).
16. **`LockManager.release()` falls back to writing `{"released":true}`** when delete fails. `drive.file` scope only allows deleting files your own session created — after a stale lock override, you can't delete the other player's lock file.
17. **`SessionLock.released` field checked before expiry/ownership logic** in `readStatus()`.
18. **`acquireLockThenPullThenOpen(world, skipPull=true)`** for LOCKED_BY_US crash recovery — local files are authoritative, do not pull.
19. **`SubscriptionStore.linkWorldToFolder` called BEFORE pull** so partial downloads are recoverable.
20. **`parallelUpload` pre-creates parent folders sequentially** before the parallel phase — avoids race conditions creating the same folder from multiple threads.
21. **NeoGradle must be pinned to `7.0.145`** in `settings.gradle`. Version `7.0.97` bundles an ASM version that can't read Java 21 class files.

---

## Data Safety Guarantees (Do Not Break)

1. No upload without lock
2. No manifest commit without lock (both push paths check this)
3. Atomic manifest: `manifest_pending.json` → rename to `manifest.json`
4. Snapshot uploads: MC can keep writing during upload without corrupting the uploaded copy
5. Per-player inventories: all `playerdata/<uuid>.dat` files sync; `level.dat` Player tag stripped on every pull
6. 3-attempt retry with backoff on transient download failures
7. Crash recovery: LOCKED_BY_US → `skipPull=true` → local files authoritative
8. SubscriptionStore corruption recovery: bad JSON renamed to `.corrupted-<timestamp>`, store starts fresh
9. Lock release always succeeds: delete or write-released-marker, never orphan lock
10. Lock stolen mid-session: heartbeat detects, posts red warning, stops further sync

---

## M8 Specifics (Recent Changes)

- **`DirtyRegionTracker`** hooks `ChunkDataEvent.Save` to track which `.mca` files MC wrote this session. `WorldFileScanner.scan()` skips all others when `shouldFilterRegions()` is true. Reduces ~860-file scans to ~52.
- **Scan cache** (`worldshare-scan-cache.json`): saved after push, deleted after pull. Lets `WorldFileScanner` skip SHA-256 for files whose mtime+size haven't changed.
- **Parallel downloads** in `pull()`: pool size 4, largest-first. Mirrors `parallelUpload` structure. Retry + 416 handling inside each worker via `downloadWithRetry()`.
- **Distant Horizons excluded**: `.sqlite`, `.sqlite-wal`, `.sqlite-shm` excluded from `TrackedPaths`. These are client-side LOD cache files that live in `data/` (which is otherwise fully tracked) and were uploading 400MB per session.
- **`pushFirstTime()` calls `DirtyRegionTracker.resetAfterPush()`** after successful commit — consistent with incremental push path.

---

## e4mc Integration Detail

- Mod ID is `e4mc` (not `e4mc_minecraft`) in the NeoForge build.
- Domain is captured via `ClientChatReceivedEvent` listener matching translation key `text.e4mc_minecraft.domainAssigned`. There is no public API field that exposes the relay domain.
- `startHosting()` fires automatically 1500ms after world open via `ContributorWorldsScreen`.
- `isHosting` flag prevents duplicate hosting — `/worldshare invite` is a no-op if already hosting.

---

## Known Gotchas

- `ChunkPos.getRegionX()` / `getRegionZ()` — if these don't exist in the NeoForge build, use `pos.x >> 5` and `pos.z >> 5`.
- `Files.createDirectories()` is idempotent and thread-safe — safe to call concurrently from multiple download workers.
- `Thread.sleep()` inside a pool worker is fine — only blocks that worker, other threads continue. No deadlock risk as long as workers never call back to the same pool.
- MC's `level.dat` contains a `Player` NBT tag that carries the host's inventory. If not stripped on pull, the downloading player inherits the host's inventory. `stripPlayerFromLevelDat()` runs on every pull.
- `CloudModule.executor()` has pool size 1. If any task on it calls `executor.submit(...).get()`, it deadlocks immediately.
- `org.apache.commons.lang3` ships with NeoForge. If shadowed/relocated, `ModConfigSpec` breaks at startup with a confusing `ClassCastException`.