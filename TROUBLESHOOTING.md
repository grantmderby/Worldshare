# Troubleshooting

## "Could not reach Drive" / "Permission denied"

**Cause:** Your Google account doesn't have write access to the host's Drive folder.

**Fix:** Ask the host to re-share the folder with **Editor** permissions, not Viewer. In Drive, right-click the folder → Share → change your access from Viewer to Editor.

## "Drive folder not found"

**Cause:** The Drive folder was deleted, moved, or renamed by the owner.

**Fix:** In your world, run `/worldshare clearDriveLink`. Then ask the host for the new folder URL and re-add via the Contributor Worlds tab.

## Lock won't acquire — "Cannot lock - your local copy is out of date"

**Cause:** Drive has changes your local copy doesn't have. Locking now would risk overwriting those changes on your next upload.

**Fix:** Save and quit. Open the world from the **Contributor Worlds tab**, not from vanilla Singleplayer. The Contributor tab pulls the latest changes before loading.

## Lock won't acquire — "Locked by <name>"

**Cause:** Another player is currently playing.

**Fix:** Wait for them to finish, or message them to save and quit.

## "Locked by <name>" but they crashed / aren't actually playing

**Cause:** Their game crashed before releasing the lock. The lock will expire automatically after 8 hours.

**Fix:** Wait, or use the Contributor Worlds tab — if the lock is older than the heartbeat threshold, you'll see a "Stale Lock" badge with an option to override.

## "Your session lock was overridden by <name>"

**Cause:** Someone else took the lock while you were playing — likely overrode a stale lock thinking you were offline.

**Fix:** Save and quit. Your local files are preserved but won't upload (the lock-holder is now authoritative). Coordinate with the other player and re-acquire the lock when they're done.

## Modpack Sync screen keeps appearing even after installing mods

**Cause:** The downloaded mod's hash doesn't match what's in `modpack.json` — possibly a different version was downloaded, or the host's manifest is stale.

**Fix:**
1. Have the host run `/worldshare modpack generate` to refresh the manifest.
2. If that doesn't help, the mod jar may have been mid-download corrupted. Check the file size against Modrinth's listing.

## Modpack download fails / network errors during install

**Cause:** Modrinth CDN hiccup, your network dropped, or one of the mods isn't on Modrinth (rare).

**Fix:** Click **Cancel Download** if you're stuck, then retry. Mods that downloaded successfully won't re-download. If a mod consistently fails, check whether it's actually on Modrinth — some mods are CurseForge-exclusive and need to be installed manually.

## "No session lock held" warning when I open my world

**Cause:** You opened the world from vanilla Singleplayer instead of the Contributor Worlds tab. WorldShare can't sync without a lock.

**Fix:**
- For routine play: always open via Contributor Worlds.
- If you really need to open from Singleplayer (testing, emergency access): run `/worldshare lock` after you load in. Note that locking will be refused if Drive has changes you don't have locally.

## Push fails with timeout / network errors

**Cause:** Drive request took too long, or your connection dropped mid-upload.

**Fix:** Run `/worldshare push` to retry. Failed uploads don't update the manifest, so retrying is safe — only files that didn't make it last time will re-upload.

## I want to use a different Google account

**Fix:** Run `/worldshare signout`. Next Drive operation will prompt for sign-in.

## Modrinth Launcher: "Mod worldshare requires neoforge 21.1 or above, and below 21.2"

**Cause:** Your profile is using a different Minecraft version (e.g. 1.21.11 has NeoForge 21.11.x).

**Fix:** Create or switch to a profile that uses **Minecraft 1.21.1** specifically, with NeoForge 21.1.x.

## Game crashes on startup with "Modules ... export package ... to module ..."

**Cause:** You're building from source and a shaded library conflicts with another mod.

**Fix:** This shouldn't happen with the released jar. If you see it, check `build.gradle` for missing exclude/relocate directives in `shadowJar`. The full set is documented in the project's build configuration.

## My friend can see I'm playing (lock badge) but no Join button appears

**Cause:** Your machine isn't writing `presence.json` to Drive — usually means e4mc didn't successfully open to LAN.

**Fix:**
1. Confirm you have e4mc installed (mod ID `e4mc`).
2. Check your log for `e4mc` chat messages. You should see "Local game hosted on domain..." within 1-2 seconds of acquiring the lock.
3. If you don't see that, manually run `/worldshare invite`.
4. If e4mc is failing to start the relay, check your firewall — e4mc needs outbound internet access.

## "Inventory was empty when my friend pulled the world"

**Expected behavior on first download.** When your friend downloads the world for the first time, their character data doesn't exist yet. MC creates a fresh inventory for them. Once they save and upload, their character is preserved going forward.

If their inventory is empty *after* they've already played: that's a bug, please file an issue with logs.

## I want to delete an old subscription

**Fix:** Currently no UI button for this. Edit `config/worldshare/subscriptions.json` directly — remove the entry for the folder you don't want anymore. Restart Minecraft.

## Where do I find the logs?

- **Minecraft launcher logs:** `<minecraft-instance>/logs/latest.log`
- **OAuth tokens:** `<minecraft-instance>/config/worldshare/tokens/`
- **Subscription store:** `<minecraft-instance>/config/worldshare/subscriptions.json`
- **Per-world Drive link:** `<minecraft-instance>/saves/<world-name>/worldshare-link.json`

When reporting a bug, attach `latest.log`. The most useful lines start with `WorldShareMod`, `AutoSync`, or `LockManager`.