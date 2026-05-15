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
- Not for huge worlds. The first upload of a multi-GB world takes a while. Subsequent syncs are fast (only changes upload).

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x (any 21.1 version works)
- Java 21
- A Google account with Drive access
- The [e4mc mod](https://modrinth.com/mod/e4mc) installed (used for live co-op)

## Setup — Host

The "host" is whoever creates the world. You only do this once.

1. **Install the mod.** Drop `worldshare-0.1.0.jar` and `e4mc-neoforge-6.1.0.jar` into your NeoForge 1.21.1 mods folder.
2. **Create a folder in Google Drive** for your world. Name it whatever you want — it'll show up in the Contributor Worlds tab.
3. **Share the folder** with your friend(s) by email. **Set their permission to Editor**, not Viewer. They cannot sync without write access.
4. **Copy the folder URL** from your browser's address bar.
5. **Launch Minecraft.** Open or create the world you want to share.
6. **Run `/worldshare setDriveLink <url>`** — paste the URL you copied. Sign in with Google when prompted.
7. That's it. The world is now linked to Drive. WorldShare also automatically generates `modpack.json` so your friend's client knows which mods they need.

## Setup — Guest

The "guest" is anyone joining a host's world.

1. **Install the same mods the host has.** WorldShare will help with this — install just `worldshare-0.1.0.jar` and `e4mc-neoforge-6.1.0.jar` to start.
2. **Launch Minecraft** to the title screen.
3. **Click "Contributor Worlds"** (the button below Multiplayer).
4. **Click "+ Add World"** and paste the Drive folder URL the host shared with you.
5. **Sign in with Google** when prompted (separate from your Minecraft account).
6. The world appears in your Contributor Worlds list. Click **Download**.
7. If the host has more mods than you, a **Modpack Sync** screen will appear automatically when you click Open. Click **Install**, then restart Minecraft. WorldShare downloads the missing mods directly from Modrinth.

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
| `/worldshare setDriveLink <url>` | Link the current world to a Drive folder |
| `/worldshare clearDriveLink` | Unlink the current world (releases the lock and unsubscribes) |
| `/worldshare lock` | Acquire the session lock (host control) |
| `/worldshare unlock` | Release the session lock |
| `/worldshare lockinfo` | Show current lock state |
| `/worldshare push` | Manually push to Drive |
| `/worldshare pull` | Manually pull from Drive (warning: do this from the title screen, not in-game) |
| `/worldshare status` | Show what would be synced |
| `/worldshare invite` | Open the world to LAN via e4mc (auto-runs when you hold the lock) |
| `/worldshare modpack generate` | Regenerate `modpack.json` (also auto-runs on every upload) |
| `/worldshare test` | Verify Drive auth is working |
| `/worldshare signout` | Sign out of Google |

## How it works (briefly)

- **Drive folder** holds your world files plus a `manifest.json` (file hashes), `session.lock` (current player), `presence.json` (live session info), and `modpack.json` (mod list).
- **Session lock** is a JSON file on Drive. Acquiring it writes your machine ID and a heartbeat timestamp. Other players see "Locked by <name>" in the Contributor tab.
- **Sync** uses SHA-256 hashes — only files that actually changed get uploaded. Initial upload is a few MB to several hundred. Subsequent syncs are usually a few hundred KB to a few MB.
- **Live co-op** uses [e4mc](https://modrinth.com/mod/e4mc) for hole-punched relay connections. Your friend doesn't need to know your IP, set up port forwarding, or use Hamachi.
- **Player data** syncs per-character. Your inventory follows your Minecraft UUID, not the world's host. Switching machines preserves your gear.

## Privacy / data

- WorldShare only accesses Drive folders you explicitly subscribe to. The OAuth scope is restricted to file-level access.
- All files are stored in your own Drive (or the host's). Anthropic, the mod author, and Google's third parties have no access.
- The mod connects to: Google Drive API (sync), Modrinth API (mod lookup), e4mc relay (live co-op).

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Credits

- [e4mc](https://modrinth.com/mod/e4mc) — relay technology that makes live co-op possible
- [Modrinth](https://modrinth.com) — mod CDN and API for automatic modpack sync
- NeoForge — the modding platform

## License

MIT License