# OAuth Credentials

This directory is where you place `client_secret.json` downloaded from Google Cloud
Console. See `docs/GOOGLE_CLOUD_SETUP.md` in the repo root for the full walkthrough.

## What belongs here

Exactly one file: `client_secret.json`

## What it looks like

A JSON file starting with something like:

```json
{
  "installed": {
    "client_id": "...............apps.googleusercontent.com",
    "project_id": "worldshare-...",
    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
    "token_uri": "https://oauth2.googleapis.com/token",
    ...
  }
}
```

The outer key MUST be `installed` (meaning it's a Desktop app credential).
If yours says `web`, you created the wrong credential type — go back to GCP Console
and make a Desktop app OAuth client instead.

## Why it's .gitignored

This file, while not strictly secret for desktop OAuth apps, identifies YOUR
Google Cloud project. Committing it to git means:

1. Anyone who forks the repo gets access to your OAuth quota
2. If the repo goes public, abuse could lead Google to flag/suspend the project

The `.gitignore` in the project root excludes `client_secret.json` anywhere.
Keep it that way.

## Alternate location

You can alternatively place the file at `<minecraft_dir>/config/worldshare/client_secret.json`.
If that file exists at runtime, it takes precedence over the bundled one. Useful during
development when you don't want to rebuild the jar to swap credentials.
