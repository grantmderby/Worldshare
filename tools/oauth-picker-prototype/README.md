# OAuth Picker prototype — does drive.file + Picker cover a shared folder over time?

This is a throwaway prototype, not shipped in the mod. It exists to answer one
question before any real code changes happen: if two people each grant the app
`drive.file` access to the *same* shared folder via the Picker, does each of
them keep seeing files the other one writes into it later — without re-picking
anything?

If yes: `OAuthHelper.SCOPES` can safely move from `DriveScopes.DRIVE` to
`DriveScopes.DRIVE_FILE`, which avoids the CASA security assessment entirely.
If no: the fallback is either restructuring the Drive layout (each player
writes into a subfolder the *other* player explicitly picks) or staying on
full `drive` scope and budgeting for CASA (~$540–$1,800/yr, see the main
publishing plan doc).

## 1. Google Cloud setup (do this once, ~10 minutes)

Use a **brand-new, throwaway GCP project** — not the real WorldShare project.
This keeps the prototype from touching your real app's consent-screen state
or verification progress at all.

1. https://console.cloud.google.com/ → new project, e.g. `worldshare-picker-test`.
2. APIs & Services → Library → enable **Google Drive API**.
3. APIs & Services → Library → enable **Google Picker API**.
4. APIs & Services → Credentials → **+ Create Credentials → API key**. This is
   your Picker key (`--api-key` below). No need to restrict it for a
   throwaway project, but you can restrict it to the Picker API if you want.
5. APIs & Services → Credentials → **+ Create Credentials → OAuth client ID**
   → Application type **Desktop app** → download the JSON, rename it to
   `client_secret.json`, put it in this folder (`tools/oauth-picker-prototype/`).
   It's gitignored here — never commit it.
6. OAuth consent screen: External, add both Google accounts you're testing
   with as test users (this project can stay in Testing status the whole
   time — non-sensitive `drive.file` scope doesn't need anything more for
   this experiment).

You'll also want a second Google account — a real second person (your
brother) or just a second free Gmail you make for the test.

## 2. Set up the shared folder (do this manually, outside the app)

1. In **Account B**'s Drive, create a folder (e.g. `worldshare-picker-test`).
2. Share it with **Account A**'s email as Editor.

This mirrors the real scenario: your brother owns/shares the folder, you
need `drive.file` access to it without having created it yourself.

## 3. Install dependencies

```
pip install -r requirements.txt
```

## 4. Run the test

All commands run from this folder.

**As Account A** (sign into Account A when the browser opens):
```
python authorize.py --label accountA
python pick_folder.py --label accountA --api-key YOUR_PICKER_API_KEY
```
In the Picker, select the folder Account B shared with you.

**Baseline check** — confirm Account A can see the folder's current contents:
```
python check_access.py --label accountA
```

**As Account B** (sign into Account B when the browser opens — same folder,
picked independently, since B owns it and needs its own `drive.file` grant
to write into it via the API):
```
python authorize.py --label accountB
python pick_folder.py --label accountB --api-key YOUR_PICKER_API_KEY
```
Then have Account B's session actually write a new file into the folder.
Easiest way for this prototype: just upload a file directly through
drive.google.com signed in as Account B — no need to script the upload
itself, since the real question is what Account A can *see*, not how the
file got there.

**The actual test — re-run Account A's check:**
```
python check_access.py --label accountA
```
Does the new file show up in the listing? That's sub-test 1, the one that
matters for WorldShare's real design (both players independently authorize
once, then push/pull over time).

**Sub-test 2 (secondary, informational only):** repeat the above but have
the new file uploaded by a *third* identity that never went through Picker
at all — e.g. just check whether Account A can see a file that was already
sitting in the folder before either Picker grant happened but wasn't
individually selected. This doesn't gate the decision since normal
WorldShare usage always goes through the app, but it's useful context.

## 5. Recording the result

Write up what you saw (pass/fail on sub-test 1, and the exact error text if
it failed) — that's the deciding input for whether `OAuthHelper.java` moves
to `drive.file` as planned. Happy to fold the result into the main planning
doc once you've run it.
