#!/usr/bin/env python3
"""
WorldShare OAuth prototype - Step 1: authorize.py

Runs the desktop OAuth flow requesting ONLY the drive.file scope and saves
the resulting credentials to a token file. This mirrors what
OAuthHelper.java does in the real mod, but in Python so this prototype
never has to touch the Gradle build.

Usage:
    python authorize.py --label accountA
    python authorize.py --label accountB

Run once per Google account you're testing with (e.g. once signed in as
yourself, once as your brother / a throwaway second account). Each run
opens a browser for that account's consent screen and saves a separate
token file so the two identities don't clobber each other.

Requires client_secret.json in this same folder - a Desktop OAuth client
from the THROWAWAY Google Cloud project you set up per README.md. Never
commit this file (it's covered by the local .gitignore here).
"""
import argparse
import os

from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = ["https://www.googleapis.com/auth/drive.file"]
CLIENT_SECRET_FILE = "client_secret.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--label",
        default="accountA",
        help="Identifies which token file to write, e.g. accountA / accountB",
    )
    args = parser.parse_args()

    if not os.path.exists(CLIENT_SECRET_FILE):
        raise SystemExit(
            f"Missing {CLIENT_SECRET_FILE}. Download it from your throwaway "
            "GCP project's Desktop OAuth client and place it in this folder. "
            "See README.md."
        )

    flow = InstalledAppFlow.from_client_secrets_file(CLIENT_SECRET_FILE, SCOPES)
    # run_local_server does the same loopback-redirect dance as
    # LocalRedirectReceiver.java in the real mod - opens a browser, listens
    # on a free localhost port, captures the auth code.
    creds = flow.run_local_server(port=0)

    token_path = f"token_{args.label}.json"
    with open(token_path, "w", encoding="utf-8") as f:
        f.write(creds.to_json())

    print(f"Saved credentials for '{args.label}' to {token_path}")
    print("Next: python pick_folder.py --label", args.label, "--api-key <your Picker API key>")


if __name__ == "__main__":
    main()
