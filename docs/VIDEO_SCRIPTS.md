# Video shot lists

Click paths only — no narration. Timings are the real waits measured during
testing, so you can plan cuts rather than discover them while recording.

Record at 1080p or better; the chat text is the content and it compresses badly.
Turn the render distance down and use a plain world — the point of every shot is
a screen or a chat line, not scenery.

**Before recording either video**, run `/worldshare signout` and delete the world
you'll be demonstrating with. Both videos are worth doing on a genuinely fresh
state, because the sign-in step only appears once and it is the step people get
stuck on.

---

## Video 1 — Setting up a world

| # | Action | What to show / wait for |
|---|---|---|
| 1 | Title screen | The **Contributor Worlds** button, below Multiplayer. Just point at it — you come back to it in video 2. |
| 2 | Singleplayer → Create New World | Any world. Name it something legible on camera. |
| 3 | In the world, open chat | — |
| 4 | Type `/worldshare setup` | Chat: *"Setting up '<name>' for sharing. This takes about half a minute."* |
| 5 | **Sign-in appears** | Three lines: the reason, `[Click here to authorize]`, and *"WorldShare will continue after authorization."* Hold here — this is the step people stall on. |
| 6 | Click the link | Browser opens Google sign-in. Pick the account. |
| 7 | Google consent screen | Approve. Browser shows *"Authorization successful"* and mentions the wait. |
| 8 | Back to Minecraft | **Progress bar appears**, counting `Creating files in Drive — n / 26`. **~30 seconds.** Good place to cut. |
| 9 | Setup finishes | Chat: `✅ '<name>' is ready to share — 26 files created in Drive`, then `[Open in Drive]` `[Copy link]`, then the invite line. |
| 10 | Click **[Open in Drive]** | Browser shows the folder: `WorldShare / WorldShare - <name>` with 26 files. Worth showing so people know where their world lives. |
| 11 | Back in game, type `/worldshare invite <email>` | Type the address bare — no quotes. Chat: `✅ <email> can now edit this world's Drive folder`, then the link. |
| 12 | Press **Esc** | The pause menu reads **"Save and Upload to Drive"**, not "Save and Quit to Title". This is the bit that surprises people. |
| 13 | Click it | Upload runs, then the title screen. |
| 14 | Contributor Worlds | The world is listed, badge **`- Available`**. |

**Optional tail:** open it again from Contributor Worlds to show it goes straight
in — no sign-in, no setup, just play.

---

## Video 2 — Joining a world

Record from a second account. If you have only one, the second dev client works —
but the sign-in is a different Google account, so make that clear on screen.

| # | Action | What to show / wait for |
|---|---|---|
| 1 | Show the email | Google's *"…shared a folder with you"* notification. This is what `invite` sent. |
| 2 | Title screen → **Contributor Worlds** | Empty list. |
| 3 | **+ Add World** | — |
| 4 | Paste the folder link | The one the host sent. |
| 5 | Click **Sign in and pick world files** | Browser opens. |
| 6 | Google sign-in, then the file picker | **Select every file in the folder** — all 26. Worth showing the select-all, and worth saying the folder itself can't be picked. |
| 7 | Back in game | Chat/log: `join: matched 26 of 26 required file(s)`. World appears, badge **`- Not Downloaded`**. |
| 8 | Click **Download** | Progress bar. Time depends on world size — cut here. |
| 9 | Finished | Badge changes to **`- Available`**. |
| 10 | Click **Open** | It pulls, takes the lock, and loads. |
| 11 | Build something obvious | One block, on camera, somewhere recognisable. |
| 12 | Press **Esc** → **Save and Upload to Drive** | Upload runs. |
| 13 | **Switch to the host account** | Contributor Worlds → Open. The block is there. That's the whole point of the mod in one shot. |

---

## Worth filming if you want a third

- **Someone else has the world.** Host opens it; on the other account the row
  reads `- Locked` with *"<name> is playing right now"* and the button is
  disabled. Shows the lock doing its job.
- **Downloading while busy.** Same state, but on an account that has never
  downloaded: the row still offers **Download**, and clicking it explains you can
  fetch now and open later. Two seconds of reading, saves a support question.
- **Live co-op.** Host runs `/worldshare host`; the other account's row flips to
  `- LIVE` with a **Join** button. Both players in one world. Needs e4mc on both
  machines — say so, because it's the single most common cause of "Join doesn't
  work".
