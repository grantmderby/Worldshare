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
