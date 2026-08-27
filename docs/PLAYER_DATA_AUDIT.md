# Player data under the bucket redesign: an audit

*Written 2026-08-27. Question asked: did the move from per-file sync to bucket
archives break the player-inventory handling, and does the design hold up beyond
two players?*

> **Status: all findings resolved in `2aa58fb`.** One correction to what follows,
> made while planning the fixes: section 3 framed a stale-lock override as a
> routine hazard, and it isn't. The lock expires only after 24 hours of no
> heartbeat (raised from 8 in the same commit), the heartbeat runs every 15
> minutes, overriding is gated behind a screen that already states what it costs
> the other player, and with e4mc installed two players online at once join the
> same live session rather than contending. The real defect was narrower and is
> stated in section 4: push did its destructive work *before* checking the lock.
> Fixing that closes the section 3 scenario too.*

## Summary

The inventory mechanism itself is **intact and untouched** by the migration. But
the migration changed something underneath it that the mechanism was implicitly
relying on: **uploads used to be selective, and are now destructive.** That
doesn't break two-player use in the normal flow, and it introduces two failure
modes that didn't exist before, both of which land on player data specifically
because of where player data now lives.

Ordered by how much they should worry you:

| # | Finding | Severity | New with buckets? | Fixed |
|---|---|---|---|---|
| A | A dirty bucket republishes *every* file in it from local disk, including other players' inventories | **High** | **Yes** | push refuses, naming the files |
| B | Uploads happen before the lock check, so a lost lock leaves archives and manifest disagreeing | **High** | Semantics much worse | lock read from Drive before uploading |
| C | Nothing verifies extracted content against the manifest hash | Medium | Blast radius bigger | pull verifies every extracted file |
| D | `level.dat_old` syncs but never gets its Player tag stripped | Medium | No — pre-existing | both files stripped |
| E | `ownPlayerUuid` is threaded through three layers and never read | Low | No — pre-existing | parameter removed |
| F | Contention, not correctness, is what limits player count | Low | No | expiry raised to 24h |

---

## 1. How inventories work, and confirmation it survived

Two pieces cooperate, and both are still present and wired in:

**Every player's data syncs, regardless of UUID.** `TrackedPaths.isTracked()`
includes all of `playerdata/*.dat`, `stats/*.json`, `advancements/*.json`. The
class comment describes this as "dedicated-server-style behaviour where each
player's character lives in their own playerdata file and follows them between
machines." Correct, and unchanged.

**The singleplayer host slot is emptied on arrival.** `SyncEngine.pull()` calls
`stripPlayerFromLevelDat()`, which removes `Data.Player` from `level.dat`. This
is the fix for the LAN quirk: a singleplayer/LAN host's character is stored in
`level.dat`, not in `playerdata/`, so without stripping it the incoming host
would load the *previous* host's inventory. Still called, on line 163 of the
pull path.

Together these give per-player inventories that follow the player. Nothing in
the bucket work altered either.

**Where they live now.** All of `level.dat`, `playerdata/`, `stats/` and
`advancements/` hash to the reserved hot bucket — bucket 0 — for every player.
Verified: five players' data plus `level.dat` map to `[0]` and nothing else.
That is good for bandwidth (one small archive) and is precisely why the finding
below concentrates there.

---

## 2. The structural change: selective vs destructive uploads

This is the heart of the audit.

**Old system.** `push()` built a `toUpload` list of changed and local-only paths,
then called `uploadOneToFolder()` once per path. A file that hadn't changed
locally was never named, never uploaded, and its Drive object was never touched.
Uploads were *additive*: they only ever affected files you had actually modified.

**New system.** `push()` determines which buckets are dirty, then calls
`uploadBucket()` for each — which packs **every file assigned to that bucket**
from local disk and replaces the remote archive wholesale. It has to: Drive
replaces a file's entire content, so a partial archive would delete whatever it
omitted.

The consequence: **rewriting one file in a bucket republishes all of them, from
your local copies.** For bucket 0, "all of them" means every player's inventory,
stats and advancements.

And bucket 0 is dirty on *every* push, because Minecraft rewrites `level.dat`
every session.

---

## 3. Finding A — stale overwrite of another player's data

**Mechanism.** Suppose players A, B and C share a world. C plays and pushes;
their `playerdata/<C>.dat` on Drive is now current. A then pushes without having
pulled C's changes first. A's local copy of C's file is stale. Bucket 0 is dirty
(it always is), so A repacks it from local disk — including their stale copy of
C's data — and it replaces the remote archive. **C's progress is gone.**

**The old system was structurally immune.** C's file was unchanged on A's disk,
so it never entered `toUpload`, so it was never uploaded. There was no code path
by which A could overwrite a file A hadn't touched.

**What currently prevents it.** The normal flow does protect you: opening a world
through Contributor Worlds pulls before opening, and the session lock means only
one player is live at a time. Pull-then-play means your copies are current, so
repacking them is harmless.

**Where the protection is thin:**

- Opening the world from vanilla Singleplayer skips the pull entirely. A push
  afterwards repacks stale data. This is the same door as the existing "open via
  Contributor Worlds" warning, but the consequence is now worse — it went from
  "your own changes may not sync" to "you may revert someone else's".
- A partially failed pull throws, but any buckets that *did* download are applied
  and the rest aren't. A subsequent push repacks a mixture.
- Overriding a stale lock is explicitly offered in the UI, and by definition
  happens when someone else's state may be newer than yours.

**Two players versus five.** With two, "the other player's data" is one file and
you almost certainly pulled it. With five, there are four other players' files in
bucket 0 at all times, and the chance that at least one is stale rises with every
participant. This is the finding that makes >2 players genuinely riskier, and it
is not a contention problem — it is a data-loss problem.

---

## 4. Finding B — uploads precede the lock check

`push()` uploads all dirty buckets (line 342), *then* checks
`LockManager.weHoldLock()` (line 358) before committing the manifest. The old
code had the same ordering, but the failure semantics differed sharply.

**Old:** archives didn't exist. Losing the lock meant some individual files had
been overwritten on Drive and the manifest wasn't updated. Untidy, self-healing
on the next push, and each affected file was one the pusher had genuinely
changed.

**New:** the bucket archive on Drive has already been replaced with the pusher's
version of *all* its contents, while the manifest still describes the previous
contents. **The manifest and the archives now disagree**, and the manifest is
what every reader trusts.

The existing code does warn the user their changes weren't committed — but the
damage isn't to their changes, it's to the remote archive, and the message
doesn't say so.

---

## 5. Finding C — no verification after extraction

Neither system verifies a downloaded file against the manifest's SHA-256. The old
`downloadOne()` accepted an `expected` entry but used it only for size reporting.
The new `pull()` extracts and trusts.

On its own this is minor. Combined with Finding B it is what makes divergence
*silent*: a puller extracts bytes whose hash doesn't match what the manifest
claims, and nothing notices. Under the old design a bad file was one file; under
buckets a bad archive is up to a sixteenth of the world.

---

## 6. Finding D — `level.dat_old` is never stripped

`TrackedPaths` syncs both `level.dat` and `level.dat_old`.
`stripPlayerFromLevelDat()` only touches `level.dat`.

`level.dat_old` is Minecraft's rollback copy, used when the primary is corrupt.
If that fallback ever fires, it restores a `level.dat` **containing the previous
host's `Data.Player`** — reintroducing exactly the inventory inheritance the
strip exists to prevent, at the moment the player is already recovering from a
problem.

Pre-existing, not caused by the migration. Narrow, but it is a real path to the
specific bug that was hardest to fix in the first place.

---

## 7. Finding E — a parameter that promises filtering it doesn't do

`WorldFileScanner.scan(worldRoot, ownPlayerUuid, ...)` passes `ownPlayerUuid` to
`collectTrackedFiles()`, which passes it to `TrackedPaths.isTracked()`, which
never reads it. Its javadoc still says "used to filter per-UUID files."

Harmless today. The hazard is a future reader noticing the unused parameter and
"fixing" it by restoring UUID filtering — which would stop other players' data
syncing and silently break the shared-inventory behaviour.

---

## 8. Finding F — what actually limits player count

Nothing in the lock, the manifest, the bucket assignment or the per-UUID player
files is two-player-specific. The lock is single-holder mutual exclusion; N
players simply queue.

What degrades is contention: more waiting, and more temptation to override a
stale lock — which is the path that walks into Finding A.

**Assessment:** the architecture holds for five. The *risk* at five is Finding A,
which is a real bug rather than a scaling limit, and is worth fixing before
anyone tries.

---

## What got safer

Worth recording alongside the risks:

- A push is now a handful of API calls rather than one per world file, so there
  are far fewer independent operations to fail halfway.
- Manifest and lock are written in a single `files.update()`, so nobody can
  observe a new manifest beside a stale lock.
- `ControlFileClient.update()` serialises read-modify-write, closing the window
  where a heartbeat could erase a freshly published manifest.

---

## What was done

All in `2aa58fb`:

1. **Push verifies the lock against Drive before uploading**, not just before
   committing, and reads it rather than trusting the cached `weHoldLock()` flag
   that only the 15-minute heartbeat refreshes. This is the fix; the rest is
   defence in depth.
2. **Push refuses if a dirty bucket holds a file that differs from the remote
   manifest and wasn't changed this session**, naming the files. Should never fire
   with (1) in place — it exists for states where something already went wrong,
   like a pull that failed partway.
3. **Pull verifies every extracted file against the manifest hash**, so a
   manifest/archive divergence fails loudly instead of writing wrong bytes.
4. **`level.dat_old` is stripped alongside `level.dat`.**
5. **The dead parameter is gone**, along with the javadoc claiming it filtered.
6. **The `baseline` branch was removed** — not in the original findings, spotted
   while fixing. It skipped an upload and adopted the remote manifest entry, which
   is right for per-file sync and wrong under buckets, where the file is repacked
   from local disk regardless and the two then disagree. Inert (every caller
   passed null) but a trap for whoever enabled it.
7. **Lock expiry raised from 8 to 24 hours**, so someone who loses power has a day
   to get back and upload. Only affects fresh installs — an existing
   `worldshare-client.toml` keeps its old value and needs editing by hand.
