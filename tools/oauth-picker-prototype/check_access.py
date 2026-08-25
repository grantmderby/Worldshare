#!/usr/bin/env python3
"""
WorldShare OAuth prototype - Step 3: check_access.py

Uses a saved token to list what's visible inside a picked folder, and
optionally check one specific file by ID. Run this right after
pick_folder.py as a baseline, then run it AGAIN later - after the other
account has written a new file into the same folder - to see whether it
shows up without re-picking anything. That's the actual question this
whole prototype exists to answer.

Usage:
    python check_access.py --label accountA
    python check_access.py --label accountA --check-file-id <fileId>
"""
import argparse
import json

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build


def load_creds(label):
    with open(f"token_{label}.json", encoding="utf-8") as f:
        info = json.load(f)
    creds = Credentials.from_authorized_user_info(info)
    if not creds.valid and creds.refresh_token:
        import google.auth.transport.requests

        creds.refresh(google.auth.transport.requests.Request())
    return creds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="accountA")
    parser.add_argument(
        "--check-file-id",
        default=None,
        help="Also try to fetch this specific file ID directly",
    )
    args = parser.parse_args()

    creds = load_creds(args.label)
    drive = build("drive", "v3", credentials=creds)

    with open(f"folder_{args.label}.json", encoding="utf-8") as f:
        folder = json.load(f)
    folder_id = folder["folderId"]

    print(
        f"Listing contents of folder {folder['folderName']} ({folder_id})"
        f" as seen by '{args.label}':"
    )
    resp = (
        drive.files()
        .list(
            q=f"'{folder_id}' in parents and trashed = false",
            fields="files(id, name, modifiedTime)",
        )
        .execute()
    )
    files = resp.get("files", [])
    if not files:
        print("  (nothing visible)")
    for f in files:
        print(f"  - {f['name']}  (id={f['id']}, modified={f['modifiedTime']})")

    if args.check_file_id:
        print(f"\nDirectly checking file {args.check_file_id}:")
        try:
            meta = (
                drive.files()
                .get(fileId=args.check_file_id, fields="id, name, modifiedTime")
                .execute()
            )
            print(f"  VISIBLE: {meta['name']} (modified={meta['modifiedTime']})")
        except Exception as e:  # noqa: BLE001 - prototype, just report it
            print(f"  NOT VISIBLE / ERROR: {e}")


if __name__ == "__main__":
    main()
