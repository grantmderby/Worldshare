# Future work

Things worth doing, deliberately not done yet. Each entry says what it is, why it
was deferred, and enough of the design to pick up cold.

---

## View-only worlds — post-release, low priority

**The idea.** A creator shares a world with an audience rather than a collaborator:
publish one Drive link, anyone with it downloads the world and plays it, nobody can
upload anything back. A YouTuber releasing their survival world, a mapmaker
shipping an adventure map, a server owner handing out a build.

**It very nearly works already**, which is what makes it cheap:

| Step | Today | Why |
|---|---|---|
| Picker selects view-only files | works | `drive.file` grants at the user's own permission level, and read access is a permission level |
| Read the control file | works | reading is enough |
| Download the world | **works** | `ContributorWorldsScreen.onDownload` never acquires a lock - it only pulls |
| Open via Contributor Worlds | fails, 403 | `LockManager.acquire` does a `files.update` on the control file |
| Push | fails, 403 | which is the entire point |

So a viewer can already download the world, and then can't open it through
Contributor Worlds - though they *can* open it from the Singleplayer list, where
`OpenWorldGateScreen` offers **Play offline anyway**. Which is precisely right
behaviour for a world nobody is meant to upload to. Even the error is already
correct: `formatDriveError` says *"Permission denied. Ask the world owner to share
the Drive folder with Editor (not Viewer) access."*

**Making it deliberate rather than accidental:**

- Detect the 403 once, at subscribe time, and record `readOnly` on the
  `WorldSubscription`.
- For a read-only subscription: skip `LockManager.acquire` entirely, skip the pull's
  lock checks, open directly, and never offer push or the pause-menu hijack.
- Label the row *"Read-only — download and play, changes stay local"*, and say the
  same on `OpenWorldGateScreen` instead of warning about unsynced changes, since
  there is nothing to sync.
- `/worldshare doctor` should say the world is read-only, or the first bug report
  will be "why won't it upload".

**Why it was deferred.** Nothing about it is needed for the mod's actual purpose -
two people sharing one world - and it wants its own testing pass with a genuinely
view-only Drive folder and a second account. It is a distribution feature wearing a
sync feature's clothes.

**Why it may be worth more than it looks.** It turns a sync tool into a
one-click way to hand somebody a modded world, mod list included: the modpack
manifest in the control file already tells the joiner which mods to install and
resolves them from Modrinth. That is a meaningfully different pitch from "sync a
world with your friend", and it costs perhaps an afternoon.

---

## Support e4all, and use it to test live co-op

**Status: investigated, not implemented.** Everything below is from reading
e4all's source at tag `v1.6.4`; none of it has been run.

**Why it came up.** Live co-op is the one WorldShare feature never verified end
to end. Hosting works - e4mc assigns a domain and we capture it - but joining
failed with an *invalid session*, which is Minecraft's auth rejecting the offline
accounts dev clients use, not our code.
[e4all](https://modrinth.com/mod/e4all) is a fork of e4mc whose entire purpose is
allowing offline accounts, and it runs on e4mc's own relays. So it can test the
half we cannot otherwise reach, and supporting it is worth doing anyway: 176k
downloads, and today those users get a silent refusal from `startHosting()`.

**What it costs: two lines.** `E4mcCoordinator` names e4mc in three places, and
only two of them are wrong.

| Coupling | e4mc | e4all | Change needed |
|---|---|---|---|
| `ModList.isLoaded("e4mc")` | ok | `modId = "e4all"` | accept either |
| `LogManager.getLogger("e4mc")` | ok | logger is `e4all` | attach to both |
| scrape `"Domain assigned:"` | ok | **identical** | none |
| `domain.contains(".e4mc.link")` | ok | **still e4mc domains** | none |

The message is `LOGGER.info("Domain assigned: {}", domain)` in
`QuiclimeSession`, byte-identical to what we already parse - and it is the same
in the unreleased 2.0 rewrite, so it is not about to move. The domain check
survives because e4all uses e4mc's infrastructure outright: its config points at
`broker.e4mc.link` and `test.e4mc.link`.

**An accidental vindication of log scraping.** e4all has a `hideDomainInChat`
option. Turn it on and the chat message omits the domain - but the log line still
carries it. Scraping the log keeps working where reading chat would have broken.

**Caveats worth keeping.**

- Passing with e4all proves *e4all* works. It does not directly prove e4mc's join
  path. It is still worth doing: the part that has never been exercised is
  Minecraft connecting through the relay tunnel, and that is shared.
- e4all **1.6.4+neoforge** (July 2026) is the current release covering 1.21.1.
  The repository's default branch is `rererewrite`, a 2.0.0-beta pinned to
  Minecraft 1.20.2 - do not read it expecting shipped behaviour.
- e4all keeps a dummy `link.e4mc.E4mcClient` holding `MOD_ID = "e4mc"` purely so
  addons that hardcode it do not crash, and isolates its real code in
  `link.e4all` so both mods can be installed together. Detection by mod id is
  therefore the right approach; that class is not a reliable signal.

**To test once supported:** put `e4all-neoforge-1.6.4.jar` in `run/mods`, host
from one dev client with `/worldshare host`, and join from another.
