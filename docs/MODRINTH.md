# Modrinth listing copy

Everything that goes on the project page, kept here so it can be reviewed and
revised like anything else rather than typed into a web form once and forgotten.

---

## Project metadata

| Field | Value |
|---|---|
| Project type | Mod |
| Name | WorldShare |
| Slug | `worldshare` |
| Licence | MIT |
| Categories | Utility, Multiplayer (also consider: Storage) |
| Client side | **Required** |
| Server side | **Unsupported** |
| Game version | 1.21.1 |
| Loader | NeoForge |
| Version number | 1.0.0 |
| Release channel | Release |
| Dependency | `e4mc` — **optional** |
| Flags | **Contains AI-generated content** |

Client required / server unsupported is not a guess: WorldShare works on
singleplayer saves and LAN, and does nothing on a dedicated server. Getting this
pair wrong is a common cause of "doesn't work" reviews from people who installed
it server-side.

Icon: `docs/branding/worldshare-icon-512.png`.

---

## Summary (one line)

> Share one singleplayer world with a friend through Google Drive — no server, no
> port forwarding, no paid host.

---

## Description

### Share a world without running a server

WorldShare keeps a single Minecraft world in sync between two people through
**your own Google Drive**. You play, you quit, it uploads. Your friend opens the
same world and picks up where you left off. Nobody has to be online at the same
time, nobody rents a server, and nobody forwards a port.

Only one person can have the world open at a time — WorldShare takes a **session
lock** while you play and releases it when you quit, so you can't both edit the
world and lose half of it. If someone else has it, the mod tells you who, and
when they last touched it.

Want to actually play together? Install [e4mc](https://modrinth.com/mod/e4mc)
and run `/worldshare host` to open your world for live co-op. Whoever is joining
needs it too. That part is optional — everything above works without it.

### Getting started

1. Open the world you want to share and run `/worldshare setup`. Sign in with
   Google when the link appears in chat. WorldShare makes its own Drive folder;
   you don't have to go and create one.
2. Run `/worldshare invite their-email@example.com`. That shares the folder with
   them and prints the link to send.
3. They install WorldShare, click **Contributor Worlds** on the title screen, and
   pick the world's files when Google asks.

From then on it's just: open the world, play, quit. Uploads happen on their own.

### How it stays fast

The world is split across twenty-four archives by location, so an evening spent
building in one area re-uploads a couple of them rather than the whole world.
Drive replaces a file's entire contents on every write — there's no partial
update — so the split is what keeps a mature world from costing hundreds of
megabytes per session.

### What it is not

- **Not a server.** One player at a time, unless you add e4mc for live co-op.
- **Not a backup tool.** It syncs the current state; it doesn't keep history.
- **Not for big groups.** Designed for two, comfortable up to about five. Live
  co-op is capped at eight by Minecraft's own LAN limit.

---

## Known limitations

Worth stating plainly rather than letting people discover them:

- **Live co-op needs e4mc on both sides.** It has to be installed by everyone
  who wants to play together, not just the host — it is what makes the host's
  address reachable. WorldShare tells you if it is missing rather than failing at
  the network layer. Drive sync works without it.
- **Mods that change how the world is stored on disk are untested.** WorldShare
  syncs the save folder as it finds it, so anything writing its own custom
  storage format may or may not survive the round trip.
- **A world's layout is fixed when it's created.** The number of archives can't
  be changed afterwards without making a new folder and having everyone re-join.

---

## Privacy

**Your world goes to your own Google Drive**, in a folder WorldShare creates.
Nobody can read it unless you share that folder with them. WorldShare asks Google
only for access to files it created or you picked — it cannot see the rest of
your Drive.

Three other things worth knowing:

- **When you set up a world**, WorldShare sends a fingerprint of each mod jar you
  have installed to Modrinth, to work out which mods they are. Fingerprints only
  — never the files themselves, and never your world.
- **When you run `/worldshare host`**, your game connects to the e4mc relay so
  your friend can join. That's the same relay the e4mc mod uses on its own, run
  by different people under their terms. Don't install e4mc and nothing is ever
  sent there.
- **Anyone you share a world with** can see your Minecraft username, when you last
  played, and a random ID for your installation. That's how the mod knows who
  currently has the world open. The ID identifies the install, not you and not
  your hardware.

WorldShare has no server of its own. No account, no telemetry, and nothing sent
anywhere else.

---

## AI disclosure

The **Contains AI-generated content** flag is set on this project, and here is
what it covers.

WorldShare's code was written by Claude, working from my direction. I decided
what it should do and how it should behave, chose the architecture, tested every
release path by hand across multiple accounts and machines, and I'm responsible
for the result. The project has 80 automated tests, a written test plan, and
documented reasoning for the design decisions — all of that is in the source
repository, which is linked on this page and which you're welcome to read before
installing anything.

The icon and any images on this page are not AI-generated. The icon is drawn by a
script in the repository (`docs/branding/make_icon.py`) from explicit geometry.

---

## Links to set

- **Source** — the GitHub repository
- **Issues** — the repository's issue tracker
- **Wiki / Website** — the project site under `docs/site`, if it's published

---

## Gallery suggestions

Screenshots do more than prose here. Worth taking:

1. The **Contributor Worlds** screen with a world listed and its lock state.
2. The **setup progress bar** mid-run.
3. Chat right after `/worldshare invite`, showing the confirmation and the link.
4. The **session lock message** when the other player has the world.
