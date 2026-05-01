# Google Cloud Console Setup

**Do this once, before running `/worldshare test`.** It's about 10 minutes of clicking
through Google's web UI.

## Why

WorldShare uses Google Drive to store shared worlds. Google requires you to register an
"OAuth application" that identifies the mod when it asks a user to sign in. You'll be
the owner of this OAuth app; your brother (and anyone else you share the mod with)
authenticates as a *test user* of your app.

## Step 1 — Create a Google Cloud Project

1. Go to https://console.cloud.google.com/
2. Sign in with the Google account that owns the Drive folder you want to use
3. In the top bar, click the project dropdown (to the right of "Google Cloud") → **New Project**
4. Name it `WorldShare` (or anything - this is just for you). Leave Organization as "No organization".
5. Click **Create**. Wait a few seconds. Make sure this new project is selected in the top bar.

## Step 2 — Enable the Google Drive API

1. In the left nav (or the search bar at top), navigate to **APIs & Services → Library**
2. Search for `Google Drive API`
3. Click the result → **Enable**
4. You should land on a "Drive API is enabled" page

## Step 3 — Configure the OAuth Consent Screen

This is the screen users will see when they authorize the mod to access their Drive.

1. Left nav: **APIs & Services → OAuth consent screen**
2. User Type: **External** → Create
3. Fill in:
   - **App name:** `WorldShare`
   - **User support email:** (your email)
   - **App logo:** skip
   - **Application home page:** skip
   - **Authorized domains:** skip
   - **Developer contact:** (your email)
4. Click **Save and Continue**

### Scopes
5. Click **Add or Remove Scopes**
6. In the filter, search for `drive`
7. Check the box next to `.../auth/drive` — "See, edit, create, and delete all of your Google Drive files"
8. Click **Update**, then **Save and Continue**

### Test Users
9. Click **Add Users**
10. Add your own Google account email
11. Add your brother's Google account email (you can add up to 100 test users later)
12. Click **Add**, then **Save and Continue**

### Summary
13. Review and click **Back to Dashboard**

> ⚠️ **About Testing mode:** While your app is in "Testing" status, Google limits
> refresh tokens to **7 days**. Every week, each user will be prompted to re-authorize
> (a single browser click). This is OK for a personal 2-person mod. Publishing the app
> would remove this limit but requires a Google verification process that takes weeks
> — not worth it.

## Step 4 — Create OAuth Client ID Credentials

1. Left nav: **APIs & Services → Credentials**
2. Top bar: **+ Create Credentials → OAuth client ID**
3. Application type: **Desktop app**
4. Name: `WorldShare Desktop`
5. Click **Create**
6. A dialog pops up with your client ID and secret. Click **Download JSON**
7. The downloaded file will be named something like `client_secret_xxxx.apps.googleusercontent.com.json`

## Step 5 — Install the Credentials in the Mod

1. **Rename** the downloaded file to exactly: `client_secret.json`
2. **Move** it into your project at exactly this path:
   ```
   worldshare/src/main/resources/worldshare/oauth/client_secret.json
   ```
3. The file is automatically excluded from git by `.gitignore`. Never commit it.
4. When you build the mod, this file gets bundled into the jar. When your brother
   installs the built mod jar, he has your credentials automatically — no GCP setup
   needed on his side.

## Step 6 — (Later) Create a Shared Drive Folder

For Milestone 1's test command, we don't need a folder yet — the test uploads to
the Drive root. But for later milestones you'll want:

1. Go to https://drive.google.com/
2. Create a new folder called `WorldShare` (or whatever)
3. Right-click → **Share** → add your brother's Google account as Editor
4. Open the folder. The URL will look like `https://drive.google.com/drive/folders/ABC123...`
5. The ID is the last part after `/folders/` — copy that string
6. In Minecraft, set the folder ID in WorldShare's config (we'll add UI for this later;
   for now it lives in `config/worldshare-client.toml`)

## Security Notes

- **client_secret.json** for desktop-app OAuth isn't truly secret. Google documents this.
  Anyone who decompiles the mod jar will see it. For a personal 2-person mod this is fine.
- **Your stored access/refresh tokens** (at `config/worldshare/tokens/`) ARE sensitive —
  they give access to your Drive. That directory is in `.gitignore`. Don't share it.
- If you ever want to revoke access: go to https://myaccount.google.com/permissions and
  remove "WorldShare" from the list of third-party apps.

## Troubleshooting

**"This app isn't verified" screen during auth:**
This is expected for apps in Testing mode. Click "Advanced" → "Go to WorldShare (unsafe)".
It's only "unsafe" because Google hasn't audited it — not because there's anything wrong.

**"access_denied" error:**
The user trying to auth isn't in your Test Users list. Go back to step 3.9 and add them.

**"invalid_client" error:**
Something is wrong with client_secret.json — possibly a typo in the path, or the file
was downloaded from a different GCP project than the one whose app is configured.
