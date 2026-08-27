# WorldShare end-to-end test plan

The first test of the `drive.file` redesign inside the actual game. Everything
verified so far is either unit-level (`BucketLayout`, `BucketArchive`) or done
with standalone Python against the Drive API — which proves Drive behaves as
assumed, **not** that the mod's use of it is correct. This document is the bridge.

**Record results as you go.** When something fails, the useful report is the step
number, what you expected, what happened, and the relevant lines from
`run/logs/latest.log`. "It didn't work" costs a round trip to localise.

---

## What you need

- Two machines, or one machine with two Minecraft installations.
- Two Google accounts. Call them **A** (creator) and **B** (joiner).
- A dev build installed on both: `./gradlew build`, then the jar from
  `build/libs/worldshare-<version>.jar` (the plain one, not `-slim`).
- `client_secret.json` present on both — see `docs/GOOGLE_CLOUD_SETUP.md`.
- e4mc installed on both **only if** you're doing Phase 5. It's now an optional
  dependency, so the game will launch without it.

Set logging to catch what matters:

```
run/config/worldshare-client.toml
```

Confirm `logLevel` (or the Forge logging config) is at least `INFO` — every
assertion below relies on a log line the mod actually emits.

---

## Phase 1 — Setup, account A (creator)

| # | Action | Expected |
|---|---|---|
| 1.1 | Launch, open or create a singleplayer world | World loads normally |
| 1.2 | Run `/worldshare setup` | Chat shows *"Opening Google sign-in. Pick (or create) a Drive folder..."*, browser opens |
| 1.3 | Consent, then pick or create a Drive folder | Browser shows the success page |
| 1.4 | Return to game | Chat: *"Set up '<world>' for sharing"* and *"Created 10 files in your Drive folder"* |
| 1.5 | Check the Drive folder in a browser | Exactly **10** files: `worldshare-control.json`, `worldshare-presence.json`, `worldshare-bucket_00.zip` … `_07.zip`. All 0 bytes |

**Log check:** `Setting up world '<name>' in Drive folder` followed by
`setup: 0 file(s) already present, 10 created`.

> **Watch for:** more than 10 files, or duplicate names. That would mean the
> adoption logic in `WorldSetup.createNewWorld` didn't fire and it created a
> second set — the exact bug that logic exists to prevent.

### 1.6 — The duplicate-prevention check (do this, it's cheap)

Run `/worldshare setup` **again** in the same world.

- **Expect:** *"This world is already set up for sharing."* No browser, no new files.
- Then delete `<world>/worldshare-link.json`, restart, and run `/worldshare setup`
  once more, picking **the same folder**.
- **Expect:** chat reports setup succeeded, and the folder **still has exactly 10
  files**. Log says `setup: adopting the existing world already in this folder`
  and `10 file(s) already present, 0 created`.
- **If the folder now has 20 files, stop.** That's silent world-orphaning and
  everything after it is invalid.

---

## Phase 2 — First push, account A

| # | Action | Expected |
|---|---|---|
| 2.1 | Play briefly — walk around, place blocks, generate some chunks | — |
| 2.2 | `/worldshare lock` | *"Lock acquired"* |
| 2.3 | `/worldshare status` | Reports files differing from Drive (everything, on a first push) |
| 2.4 | `/worldshare push` | Progress messages naming **bucket archives**, not individual files |
| 2.5 | Check Drive | Bucket zips now have real sizes; `worldshare-control.json` is no longer 0 bytes |
| 2.6 | Open `worldshare-control.json` in Drive's viewer | Valid JSON with `bucketCount: 8`, a populated `manifest.files`, and `lock.status: "hosting"` |

**Log check:** `push: N changed file(s) dirty M of 8 bucket(s)` then
`commitControl: published manifest with N entries`.

> **Watch for:** all 8 buckets dirty on a *second* push after a small change.
> **Two** is the expected figure — bucket 0 (the hot bucket, holding `level.dat`,
> playerdata, stats and `data/`, which Minecraft rewrites every session) plus the
> one region bucket covering wherever you played. If every push rewrites
> everything, the bucket assignment or the dirty-tracking is not working.

### 2.7 — Incremental push

Play a little more in **one area**, then `/worldshare push` again.

- **Expect:** log reports 2 dirty buckets (hot + one region), not 8. Three is fine
  if you crossed a region boundary.
- Note the reported MB. This is the number that decides whether 8 buckets is the
  right default — report it back either way.

---

## Phase 3 — Join, account B

| # | Action | Expected |
|---|---|---|
| 3.1 | As A, share the Drive folder with B's account as **Editor** | — |
| 3.2 | As B, launch Minecraft → **Contributor Worlds** → **Add World** | Screen explains to open the folder and select the files inside |
| 3.3 | Click *Sign in and pick world files* | Browser opens to consent + picker |
| 3.4 | **First, deliberately do it wrong:** select only the *folder* | Back in game: *"Selecting the folder only works for a world you created yourself..."* |
| 3.5 | Retry, open the folder, select **all 10** `worldshare-*` files | Returns to Contributor Worlds, world appears in the list |
| 3.6 | Select the world → download/open | Pull runs, world opens with A's terrain and buildings |

**Log check on 3.5:** `join: matched 10 of 10 required file(s)`.

> **Step 3.4 is not optional padding.** It's the mistake every real user will make
> first, and the whole point of that error message. If it instead reports "10 files
> missing", the guidance is useless and needs rewording.

### 3.7 — Partial selection

Worth one run: pick only **half** the files.

- **Expect:** *"Missing 5 file(s): worldshare-bucket_04.zip, ..."* naming them
  specifically, and the world is **not** added in a broken state.

---

## Phase 4 — The round trip (the real test)

| # | Action | Expected |
|---|---|---|
| 4.1 | As B, build something distinctive at a known location | — |
| 4.2 | As B, save and quit | Sync-on-exit screen appears and completes |
| 4.3 | As A, open the world via Contributor Worlds | Pull runs; **B's build is present** |
| 4.4 | As A, verify your own earlier work is still there | Nothing of A's was lost |

**This is the step that matters.** Everything before it tests plumbing; this tests
whether the world actually survives a round trip through eight zip archives.

### 4.5 — Locking

With A holding the lock and still in the world, have B try to open it.

- **Expect:** B is blocked, shown A as the holder, and offered wait/retry rather
  than being allowed in.

### 4.6 — Release

A saves and quits. B opens it.

- **Expect:** B gets in. `worldshare-control.json` shows `lock.status: "unlocked"`
  — and **the file still exists**, with the same Drive file ID as in step 1.5.
- **If the control file's ID changed, stop.** Something deleted and recreated it,
  which silently severs the other player's grant — the single most important
  invariant in this design.

---

## Phase 5 — Live co-op (only with e4mc installed)

| # | Action | Expected |
|---|---|---|
| 5.1 | Without e4mc installed, run `/worldshare invite` | *"Live co-op needs the e4mc mod, which isn't installed."* — and the game did launch, since it's optional now |
| 5.2 | Install e4mc on both, restart | — |
| 5.3 | As A, hold the lock and run `/worldshare invite` | Relay domain assigned; `worldshare-presence.json` gains content |
| 5.4 | As B, at the title screen | Join prompt appears for A's live session |
| 5.5 | A stops hosting | Presence file is **cleared, not deleted** — same file ID, empty-ish JSON |

---

## Phase 6 — Failure modes worth provoking

Each of these is a path that will happen in real use and has never been exercised.

- **Offline push.** Disconnect the network, then `/worldshare push`. Expect a
  clear "Drive unreachable, local changes preserved", not a crash or a hang.
- **Interrupted push.** Kill the game mid-push. Relaunch, push again. Expect
  recovery, with no corruption and no half-written archive adopted as truth.
- **Lock takeover.** With A holding the lock, force B to override a stale lock
  (or wait out the expiry). Expect A to get the chat warning that their session
  was overridden and that changes won't sync.
- **Bucket-count mismatch.** Hand-edit `bucketCount` in `worldshare-control.json`
  to `4` and try to sync. Expect a refusal naming the mismatch — **not** a
  best-effort sync. This guard exists because proceeding corrupts the world
  gradually and silently.

---

## Reporting back

For each phase, the useful shape is:

```
Phase 2.4 — FAIL
Expected: progress naming bucket archives
Got:      "0 files to upload", push completed instantly
Log:      push: nothing changed (0 file(s) left to the other player)
```

Phases 1, 2 and 4 are the ones that decide whether this design works at all.
5 and 6 can wait if time is short.
