#!/usr/bin/env python3
"""
WorldShare OAuth prototype - owner_upload.py

Simulates exactly what DriveClient.uploadFile() / updateFile() / renameFile()
do in the real mod, using the file's OWNING/CREATING account's own
drive.file-scoped token. Files an account creates via the Drive API are
automatically visible to that same account's drive.file grant forever -
no Picker step needed on the creating side. This script exists to test
two open questions from the Google Doc test:

1. Does "an individually-picked file's access survives future content
   edits" hold for a PLAIN BINARY file (e.g. a .zip) updated via the
   standard media-upload API - the same path DriveClient.updateFile()
   uses - not just a native Google Doc edited through Docs' own UI?

2. Does a RENAME (metadata-only update, same file ID) made by the owning
   account show up to another account's separate persistent grant on
   that file? This is the mechanism behind the "[worldname]_LOCKED.zip"
   filename-based session lock idea.

Usage:
    # First push: create a new file
    python owner_upload.py --label accountB --local-file v1.zip --drive-name world_test.zip --parent-folder-id FOLDER_ID

    # Later push: update an EXISTING file's content in place (same file ID)
    python owner_upload.py --label accountB --local-file v2.zip --file-id FILE_ID

    # Rename only (e.g. to encode lock state), content untouched
    python owner_upload.py --label accountB --file-id FILE_ID --rename "world_test_LOCKED.zip"

    # Update content AND rename in one call (atomic - one API request)
    python owner_upload.py --label accountB --file-id FILE_ID --local-file v2.zip --rename "world_test_LOCKED.zip"
"""
import argparse
import json

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload


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
    parser.add_argument("--label", default="accountB")
    parser.add_argument("--local-file", help="local file whose bytes become the Drive file's content")
    parser.add_argument("--drive-name", help="name for a NEW file (create mode, no --file-id)")
    parser.add_argument("--parent-folder-id", help="parent folder for a NEW file (create mode)")
    parser.add_argument("--file-id", help="existing Drive file ID to update and/or rename")
    parser.add_argument("--rename", help="new name to set on --file-id (or the newly created file)")
    args = parser.parse_args()

    creds = load_creds(args.label)
    drive = build("drive", "v3", credentials=creds)

    if args.file_id:
        body = {}
        media = None
        if args.rename:
            body["name"] = args.rename
        if args.local_file:
            media = MediaFileUpload(args.local_file, resumable=False)
        if not body and media is None:
            raise SystemExit("With --file-id, pass --local-file and/or --rename.")
        kwargs = {"fileId": args.file_id, "fields": "id, name, modifiedTime"}
        if body:
            kwargs["body"] = body
        if media is not None:
            kwargs["media_body"] = media
        updated = drive.files().update(**kwargs).execute()
        print(f"Updated {updated['id']} -> name={updated['name']!r}, modified={updated['modifiedTime']}")
    else:
        if not args.local_file:
            raise SystemExit("Create mode needs --local-file (and usually --drive-name / --parent-folder-id).")
        metadata = {"name": args.drive_name or args.rename or "world_test.zip"}
        if args.parent_folder_id:
            metadata["parents"] = [args.parent_folder_id]
        media = MediaFileUpload(args.local_file, resumable=False)
        created = drive.files().create(
            body=metadata, media_body=media, fields="id, name, modifiedTime"
        ).execute()
        print(f"Created {created['id']} -> name={created['name']!r}, modified={created['modifiedTime']}")


if __name__ == "__main__":
    main()
