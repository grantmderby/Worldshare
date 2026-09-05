# WorldShare

A Minecraft mod that lets you and a friend share a singleplayer world via Google Drive — no dedicated server needed. Take turns playing, with each session's changes automatically synced to the cloud, plus live LAN co-op when both of you are online at the same time.

Built for **NeoForge 1.21.1**.

## What it does

- **Asynchronous play** — Play your shared world whenever you want. Save, exit, and your changes upload to Drive. The next person plays your version.
- **Live co-op** — When you're hosting, your friend can join via the title screen with one click. Powered by [e4mc](https://modrinth.com/mod/e4mc).
- **Session locking** — Only one player edits at a time. The lock prevents anyone else from saving over your changes while you're playing.
- **Modpack sync** — Your friend automatically downloads any mods they're missing. No more "you need to install these 47 mods first."
- **Same character, anywhere** — Your inventory, XP, and advancements follow you between machines. Play on your desktop, switch to your laptop, your gear's still there.

## What it's NOT

- Not a server replacement. If you want 5+ players online 24/7, get a real server.
- Not a backup tool. WorldShare uploads your latest state — there's no version history beyond what's on Drive right now.
- Not built for huge worlds. The first upload of a multi-GB world takes a while.
  Later syncs only re-upload the archives you actually touched, so a session in
  one corner of the map is quick — but a session that ranges everywhere isn't.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x (any 21.1 version works)
- Java 21
- A Google account with Drive access
- Optionally, the [e4mc mod](https://modrinth.com/mod/e4mc) — only needed for
  live co-op. Everything else works without it.

## Setup — Host

The "host" is whoever creates the world. You only do this once per world.

1. **Install the mod.** Drop `worldshare-1.0.0.jar` into your NeoForge 1.21.1
   mods folder. Add `e4mc` too if you want live co-op.
2. **Launch Minecraft** and open the world you want to share.
3. **Run `/worldshare setup`.** Sign in with Google when the browser opens. That
   is the whole of it — WorldShare makes the folder itself, as
   `WorldShare/WorldShare - <world name>` in your My Drive, so however many worlds
   you share they stay in one place. It creates twenty-six files in there — a
   settings file, a presence file, and twenty-four world archives — and publishes
   your mod list so your friend's game knows what it needs. Takes about half a
   minute, with a progress bar.

   Interrupted, or on a bad connection? Run it again. Setup finds the folder it
   already made and fills in the gaps rather than starting a second world beside
   the first. Run it on a world that's already set up and it just hands you the
   link again.
4. **Run `/worldshare invite <their email>`.** WorldShare shares the folder with
   that Google account as an Editor — Google emails them about it — and prints the
   link to send them. You never have to open Drive.

   Prefer to do the sharing yourself? `/worldshare invite` with no email just
   prints the link. Set their permission to **Editor**, not Viewer; they cannot
   sync without write access.
5. **Send them the link.** It isn't a password — it only tells Google which folder
   to show them. They still have to pick the world's files themselves, because
   access to a folder conveys nothing about what's inside it.

> **Lost your link file?** If you reinstalled and the world no longer looks set
> up, just run `/worldshare setup` again. It finds the folder it made for that
> world — by a private tag, so it still works if you've renamed the folder or
> dragged it somewhere else in Drive — and reuses it rather than starting a
> second world beside the first.

> **Why so many files rather than one?** Google Drive replaces a file's entire
> contents on every write — there's no way to update part of one. Splitting the
> world across several archives means a session spent in one area re-uploads one
> or two of them instead of the whole world. See
> [the backend decision report](docs/CLOUD_BACKEND_DECISION.md) if you want the
> long version.

## Setup — Guest

The "guest" is anyone joining a host's world.

1. **Install the mod.** Just `worldshare-1.0.0.jar` to start — WorldShare will
   tell you which other mods the world needs and fetch them for you.
2. **Accept the host's Drive share** (check your email) so the folder appears in
   your own Drive.
3. **Launch Minecraft** to the title screen.
4. **Click "Contributor Worlds"** (the button below Multiplayer), then
   **"+ Add World"**.
5. **Paste the folder link the host sent you**, then click *Sign in and pick
   world files*. (No link? Leave it blank — you'll browse to the folder yourself.)
6. **Sign in with Google** — separate from your Minecraft account. Google shows
   you the world's folder. **Open it and select every file inside.**

   > The folder itself isn't selectable, on purpose. Google grants access only to
   > files you pick individually — a folder grants nothing about its contents, so
   > selecting it would look like it worked and then fail. You do this once; it's
   > remembered from then on, even if you sign out or reinstall.

7. The world appears in your Contributor Worlds list. Click **Download**.
8. If the host has mods you don't, a **Modpack Sync** screen appears when you
   click Open. Click **Install**, then restart. WorldShare downloads them from
   Modrinth.

## Daily Use

### Playing solo

1. Title screen → **Contributor Worlds**
2. Click **Open** on the world you want to play
3. WorldShare acquires the session lock (prevents others from playing simultaneously) and pulls the latest changes
4. Play normally
5. **ESC → Save and Upload to Drive** (replaces vanilla "Save and Quit")
6. Wait for the upload to finish — you'll see a progress bar
7. Done. Your changes are on Drive for the next person.

### Joining a friend's live session

1. Title screen → if your friend is currently playing, you'll see a **prompt to join their session**
2. Click **Join** — connects you via e4mc to their world
3. Play together until they save and quit (or you disconnect)

### Switching from solo to live co-op

If you're already in your world and want to invite your friend:
- It's automatic. As long as you hold the lock (which the Contributor tab acquires for you), your world is open to LAN. Your friend will see the join prompt on their title screen.

### WARNING
Playing your world from the vanilla singleplayer screen is possible but **CHANGES ARE NOT UPLOADED TO DRIVE**.
Worlds set up with WorldShare should be loaded from the singleplayer tab only for creating local backups or testing.



## Commands

| Command | What it does |
|---|---|
| `/worldshare setup` | Set the current world up for sharing (creates its Drive files) |
| `/worldshare clearDriveLink` | Unlink the current world (releases the lock and unsubscribes) |
| `/worldshare lock` | Acquire the session lock (host control) |
| `/worldshare unlock` | Release the session lock |
| `/worldshare lockinfo` | Show current lock state |
| `/worldshare push` | Manually push to Drive |
| `/worldshare status` | Show what would be synced |
| `/worldshare invite` | Open the world to LAN via e4mc (auto-runs when you hold the lock) |
| `/worldshare modpack generate` | Regenerate `modpack.json` (also auto-runs on every upload) |
| `/worldshare test` | Verify Drive auth is working |
| `/worldshare signout` | Sign out of Google |

> **Why there's no `pull` command.** Pulling rewrites world files underneath
> whatever has them open, so it's only safe before a world loads. Open the world
> from the **Contributor Worlds** tab instead — that pulls first, then opens.

## How it works (briefly)

- **Drive folder** holds a fixed set of twenty-six files:
  `worldshare-control.json` (file hashes, session lock state, and the mod list),
  `worldshare-presence.json` (live session info), and twenty-four
  `worldshare-bucket_NN.zip` archives holding the world itself. The set never
  grows, which is what lets each player grant access to it once and never again.
  The world is spread across those archives by location, so an evening spent in
  one area re-uploads a couple of them rather than the whole world.

  **Move the folder wherever you like** — Drive keeps a file's identity through
  moves and renames, and sharing travels with the folder. **Don't move files out
  of it**, though: the people you've shared with get their access from the folder,
  and WorldShare finds an existing world by looking inside it. `/worldshare doctor
  full` will tell you if anything has wandered.
- **Session lock** is a JSON file on Drive. Acquiring it writes your machine ID and a heartbeat timestamp. Other players see "Locked by <name>" in the Contributor tab.
- **Sync** uses SHA-256 hashes — only files that actually changed get uploaded. Initial upload is a few MB to several hundred. Subsequent syncs are usually a few hundred KB to a few MB.
- **Live co-op** uses [e4mc](https://modrinth.com/mod/e4mc) for hole-punched relay connections. Your friend doesn't need to know your IP, set up port forwarding, or use Hamachi.
- **Player data** syncs per-character. Your inventory follows your Minecraft UUID, not the world's host. Switching machines preserves your gear.

## Privacy / data

**Your world goes to your own Google Drive**, in a folder WorldShare creates.
Nobody can read it unless you share that folder with them. WorldShare asks
Google only for access to files it created or you picked — it cannot see the
rest of your Drive.

Three other things worth knowing:

- **When you set up a world**, WorldShare sends a fingerprint of each mod jar you
  have installed to Modrinth, to work out which mods they are. Fingerprints only
  — never the files themselves, and never your world.
- **When you run `/worldshare host`**, your game connects to the e4mc relay so
  your friend can join. That's the same relay the e4mc mod uses on its own, run
  by different people under their terms. Don't install e4mc and nothing is ever
  sent there.
- **Anyone you share a world with** can see your Minecraft username, when you
  last played, and a random ID for your installation. That's how the mod knows
  who currently has the world open. The ID identifies the install, not you and
  not your hardware, and a fresh install gets a new one.

WorldShare has no server of its own. No account, no telemetry, and nothing sent
anywhere else.

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Credits

- [e4mc](https://modrinth.com/mod/e4mc) — relay technology that makes live co-op possible
- [Modrinth](https://modrinth.com) — mod CDN and API for automatic modpack sync
- NeoForge — the modding platform

## License

MIT License — see [LICENSE](LICENSE).

The published jar bundles the Google Drive API client, Gson, Guava and a few
other libraries so that it works without extra downloads. They are Apache-2.0
and MPL-2.0 licensed; see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt),
which is also shipped inside the jar.