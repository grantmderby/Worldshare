# WorldShare end-to-end test plan

The first test of the `drive.file` redesign inside the actual game. Everything
verified so far is either unit-level (`BucketLayout`, `BucketArchive`) or done
with standalone Python against the Drive API — which proves Drive behaves as
assumed, **not** that the mod's use of it is correct. This document is the bridge.

**Record results as you go.** When something fails, the useful report is the step
number, what you expected, what happened, and the output of **`/worldshare
doctor`** — which dumps link state, control file contents, lock holder, bucket
sizes and a local-vs-remote diff in one go, to chat and to `latest.log`. Most
"check the log for X" steps below collapse into reading that.

Use `/worldshare doctor full` when a step involves bucket sizes or a
local-vs-remote comparison; it adds a world scan and one API call per bucket, so
it's slower.

---

## What you need

**Two Google accounts.** Call them **A** (creator) and **B** (joiner). This is the
one thing that can't be faked — the whole design turns on files being shared
between two Drive accounts.

**One machine is enough.** Run two dev clients side by side:

```
./gradlew runClient        # player A, game dir run/
./gradlew runClientTwo     # player B, game dir run2/
```

They are genuinely independent, not just two windows. Everything WorldShare uses
to tell players apart lives under the game directory:

| `run/config/worldshare/` | what it separates |
|---|---|
| `tokens/StoredCredential` | which Google account is signed in |
| `subscriptions.json` | which worlds are subscribed |
| `machine_id` | **lock ownership** — what `SessionLock.isOwnedBy()` compares |

That last one is why stale-lock override can be tested here at all. `runClientTwo`
also passes `--username Player2`, so the two clients get different offline UUIDs
and therefore separate `playerdata/<uuid>.dat` files — without which the
per-player inventory behaviour couldn't be observed.

**e4mc** only matters for Phase 5. It's an optional dependency now, so the game
launches fine without it.

> Testing the *shipped artifact* instead is also valid, and stricter: build with
> `./gradlew build` and install `build/libs/worldshare-<version>.jar` (the plain
> one, not `-slim`) into two Prism/MultiMC instances. Slower to iterate, but it
> catches packaging problems the dev runtime hides.

Set logging to catch what matters:

```
run/config/worldshare-client.toml
```

Confirm `logLevel` (or the Forge logging config) is at least `INFO` — several
assertions below rely on a log line the mod emits.

Before starting, sanity-check the harness itself: run `/worldshare doctor` in a
world that has **never** been set up. It should report "Not set up for sharing"
cleanly rather than throwing. If that misbehaves, fix it before trusting anything
it says later.

---

## Phase 1 — Setup, account A (creator)

| # | Action | Expected |
|---|---|---|
| 1.1 | Launch, open or create a singleplayer world | World loads normally |
| 1.2 | Run `/worldshare setup` | Chat shows *"Opening Google sign-in. Pick (or create) a Drive folder..."*, browser opens |
| 1.3 | Consent, then pick or create a Drive folder | Browser shows the success page |
| 1.4 | Return to game | Chat: *"Set up '<world>' for sharing"* and *"Created 18 files in your Drive folder"* |
| 1.5 | Check the Drive folder in a browser | Exactly **18** files: `worldshare-control.json`, `worldshare-presence.json`, `worldshare-bucket_00.zip` … `_15.zip`. All 0 bytes |

**Log check:** `Setting up world '<name>' in Drive folder` followed by
`setup: 0 file(s) already present, 18 created`.

> **Watch for:** more than 18 files, or duplicate names. That would mean the
> adoption logic in `WorldSetup.createNewWorld` didn't fire and it created a
> second set — the exact bug that logic exists to prevent.

### 1.6 — The duplicate-prevention check (do this, it's cheap)

Run `/worldshare setup` **again** in the same world.

- **Expect:** *"This world is already set up for sharing."* No browser, no new files.
- Then delete `<world>/worldshare-link.json`, restart, and run `/worldshare setup`
  once more, picking **the same folder**.
- **Expect:** chat reports setup succeeded, and the folder **still has exactly 18
  files**. Log says `setup: adopting the existing world already in this folder`
  and `18 file(s) already present, 0 created`.
- **If the folder now has 36 files, stop.** That's silent world-orphaning and
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
| 2.6 | Open `worldshare-control.json` in Drive's viewer | Valid JSON with `bucketCount: 16`, a populated `manifest.files`, and `lock.status: "hosting"` |

**Log check:** `push: N changed file(s) dirty M of 16 bucket(s)` then
`commitControl: published manifest with N entries`.

> **Watch for:** most of the 16 buckets dirty on a *second* push after a small
> change. **Two or three** is the expected figure — bucket 0 (the hot bucket,
> holding `level.dat`, playerdata, stats and `data/`, which Minecraft rewrites
> every session) plus the one or two region tiles covering wherever you played.
> If every push rewrites everything, the bucket assignment or the dirty-tracking
> is not working.

### 2.7 — Incremental push

Play a little more in **one area**, then `/worldshare push` again.

- **Expect:** 2–3 dirty buckets (hot + the region tile you played in), not 16.
  Regions are grouped into 4×4 tiles, so wandering within a 2048-block square
  should stay in one region bucket.
- Note the reported MB. That's the number that says whether 16 is the right
  count — report it back either way.

---

## Phase 3 — Join, account B

| # | Action | Expected |
|---|---|---|
| 3.1 | As A, share the Drive folder with B's account as **Editor** | — |
| 3.2 | As A, copy the folder link `/worldshare setup` printed in chat | A `drive.google.com/drive/folders/...` URL |
| 3.3 | As B, **Contributor Worlds** → **Add World**, paste the link | — |
| 3.4 | Click *Sign in and pick world files* | Picker opens showing **only that folder** — not B's whole Drive |
| 3.5 | Try to select the folder itself | **Not selectable.** It can only be opened |
| 3.6 | Open it, select **all 18** `worldshare-*` files | Returns to Contributor Worlds, world appears in the list |
| 3.7 | Select the world → download/open | Pull runs, world opens with A's terrain and buildings |

**Log check on 3.6:** `join: matched 18 of 18 required file(s)`.

> **Step 3.4 and 3.5 are the point of this phase.** Scoping the picker to the
> invite folder and making that folder unselectable are what stop the commonest
> setup failure. If B sees their whole Drive, the `file_ids` scoping didn't take.
> If the folder *can* be selected, `allow_folder_selection` is leaking into the
> join flow and users will pick it and get a grant that reaches nothing.

### 3.8 — The no-invite path

Repeat with the link box left **blank**.

- **Expect:** the picker opens on B's whole Drive, B navigates to the shared
  folder, and selection works the same way. Slower, but it must work — invites
  get lost.

### 3.9 — Partial selection

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

## Phase 5.5 — The guards added after the player-data audit

These are error paths, so they never run in a happy-path test. Both were added in
`2aa58fb` and neither has been exercised in-game.

### 5.5a — a normal push still works (the regression risk)

Before anything else: after Phase 4, confirm an ordinary pull → play → save & quit
cycle still pushes cleanly. The stale-push refusal is new, and the thing to rule
out is it firing when it shouldn't. **If a normal push starts refusing, stop and
report it** — that's worse than the bug it guards against.

### 5.5b — push refuses once the lock is no longer yours

1. Set `lockExpiryMinutes = 1` in `run/config/worldshare-client.toml` on both
   installs, so a lock goes stale in a minute instead of a day.
2. As A, open the world via Contributor Worlds and stay in it.
3. Wait for the lock to go stale, then as B open the same world and accept the
   override prompt. Play briefly and save & quit, so B's data is current on Drive.
4. Back as A, still in-world, save & quit.

- **Expect:** A's push aborts with *"This world's session lock is no longer yours
  (B holds it now)…"* — and crucially, **before uploading anything**. Watch the log
  for the absence of any `push: worldshare-bucket_NN.zip` upload lines.
- **Then confirm B's data survived:** reopen as B and check their inventory and
  anything they built. This is the whole point of the fix.
- **Reset `lockExpiryMinutes` to 1440 afterwards on both installs.**

> Before the fix, A's push would have uploaded bucket 0 — containing A's hours-old
> copy of B's inventory — and only then noticed the lock was gone, leaving B's
> work overwritten and the manifest describing contents the archives no longer
> had.

## Phase 6 — Failure modes worth provoking

Each of these is a path that will happen in real use and has never been exercised.

- **Offline push.** Disconnect the network, then `/worldshare push`. Expect a
  clear "Drive unreachable, local changes preserved", not a crash or a hang.
- **Interrupted push.** Kill the game mid-push. Relaunch, push again. Expect
  recovery, with no corruption and no half-written archive adopted as truth.
- **Lock takeover.** With A holding the lock, force B to override a stale lock
  (or wait out the expiry). Expect A to get the chat warning that their session
  was overridden and that changes won't sync.
- **Corrupted archive.** Download a bucket zip from Drive, alter a byte, re-upload
  it, and pull. Expect a refusal naming the file — *"came out of
  worldshare-bucket_NN.zip with different content than the world's manifest
  describes"* — rather than the bad bytes silently landing in the world.
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
