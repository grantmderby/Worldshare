#!/usr/bin/env python3
"""
WorldShare OAuth prototype - preexisting_folder_test.py

Answers the one question the earlier prototype scripts left open, and which
decides how painful WorldShare's setup flow has to be.

WHAT WE ALREADY KNOW (from pick_folder.py / authorize_and_pick.py):
  - Picking a folder does NOT grant access to files added to that folder
    AFTER the pick. Confirmed, twice, including under the correct desktop
    trigger_onepick flow.
  - Picking an individual file DOES grant durable access that survives later
    content overwrites and renames by other accounts. Confirmed.

WHAT NOBODY TESTED:
  - Does picking a folder grant access to the files ALREADY SITTING IN IT at
    the moment of the pick?

WHY IT MATTERS:
  WorldShare's redesign creates its entire remote file set up front at world
  setup: one control document, one presence file, and N bucket archives. They
  all exist before the second player ever authorises. So:

    - If pre-existing files ARE covered, the joining player picks ONE FOLDER
      and setup is a single click.
    - If they are NOT, the joining player has to multi-select every one of
      ~18 files by hand, and we should keep the bucket count low to limit
      how tedious that is.

HOW TO RUN (about 10 minutes, needs two Google accounts):

  Step 1 - account A creates the files:
      python authorize.py --label accountA
      python preexisting_folder_test.py setup --label accountA

    This prints a folder ID and several file IDs, and writes them to
    preexisting_test_state.json.

  Step 2 - share the folder, by hand, in the Drive web UI:
      Open the folder link the script prints, share it with account B as
      Editor. This is deliberately manual: it is exactly what a real
      WorldShare user does when inviting the other player.

  Step 3 - account B picks THE FOLDER, and nothing else:
      python authorize_and_pick.py --label accountB --allow-folder-selection

    In the Picker, select the shared folder. Do NOT open it and select the
    files individually - that would test the thing we already know works.

  Step 4 - check what account B can actually reach:
      python preexisting_folder_test.py verify --label accountB

    This prints a verdict.
"""
import argparse
import json
import os
import sys

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaInMemoryUpload

STATE_FILE = "preexisting_test_state.json"

FOLDER_MIME = "application/vnd.google-apps.folder"

# Deliberately mirrors the real remote layout: a JSON control document and a
# couple of binary bucket archives. If access behaves differently by MIME type
# we want to find that out here rather than in the mod.
TEST_FILES = [
    ("worldshare-control.json", "application/json", b'{"schemaVersion":1,"test":true}'),
    ("worldshare-bucket_00.zip", "application/zip", b"PK\x05\x06" + b"\x00" * 18),
    ("worldshare-bucket_01.zip", "application/zip", b"PK\x05\x06" + b"\x00" * 18),
]


def load_creds(label):
    path = f"token_{label}.json"
    if not os.path.exists(path):
        sys.exit(
            f"No {path} found. Authorise that account first:\n"
            f"    python authorize.py --label {label}"
        )
    with open(path, encoding="utf-8") as f:
        info = json.load(f)
    creds = Credentials.from_authorized_user_info(info)
    if not creds.valid and creds.refresh_token:
        import google.auth.transport.requests

        creds.refresh(google.auth.transport.requests.Request())
    return creds


def cmd_setup(args):
    """Account A creates a folder and drops the fixed file set into it."""
    drive = build("drive", "v3", credentials=load_creds(args.label))

    folder = (
        drive.files()
        .create(body={"name": args.folder_name, "mimeType": FOLDER_MIME}, fields="id, name")
        .execute()
    )
    folder_id = folder["id"]
    print(f"Created folder '{folder['name']}' -> {folder_id}")

    created = []
    for name, mime, content in TEST_FILES:
        media = MediaInMemoryUpload(content, mimetype=mime, resumable=False)
        f = (
            drive.files()
            .create(
                body={"name": name, "parents": [folder_id]},
                media_body=media,
                fields="id, name, size",
            )
            .execute()
        )
        created.append({"id": f["id"], "name": f["name"]})
        print(f"  created {f['name']:<28} -> {f['id']}")

    state = {
        "folder_id": folder_id,
        "folder_name": folder["name"],
        "created_by": args.label,
        "files": created,
    }
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)

    print(f"\nWrote {STATE_FILE}")
    print("\n" + "=" * 72)
    print("NEXT - do this by hand in your browser:")
    print(f"  1. Open  https://drive.google.com/drive/folders/{folder_id}")
    print("  2. Share that folder with your OTHER Google account, as Editor.")
    print("  3. As that other account, run:")
    print("       python authorize_and_pick.py --label accountB --allow-folder-selection")
    print("     and select THE FOLDER ITSELF - do not go inside and pick the files.")
    print("  4. Then run:")
    print("       python preexisting_folder_test.py verify --label accountB")
    print("=" * 72)


def cmd_verify(args):
    """Account B checks whether its folder pick reached the pre-existing files."""
    if not os.path.exists(STATE_FILE):
        sys.exit(f"No {STATE_FILE}. Run the 'setup' step with the first account first.")
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)

    drive = build("drive", "v3", credentials=load_creds(args.label))

    print(f"Folder under test: {state['folder_name']} ({state['folder_id']})")
    print(f"Files were created beforehand by: {state['created_by']}\n")

    # 1. Can this account see the folder itself?
    try:
        meta = drive.files().get(fileId=state["folder_id"], fields="id, name").execute()
        print(f"[ok]   folder is reachable: {meta['name']}")
    except HttpError as e:
        print(f"[FAIL] folder itself is NOT reachable ({e.status_code}).")
        print("       The pick probably didn't register. Re-run step 3.")
        return

    # 2. Does listing the folder reveal its pre-existing contents?
    listed = (
        drive.files()
        .list(
            q=f"'{state['folder_id']}' in parents and trashed = false",
            fields="files(id, name)",
            pageSize=100,
        )
        .execute()
        .get("files", [])
    )
    print(f"[info] listing the folder returns {len(listed)} file(s): "
          f"{[f['name'] for f in listed] or 'nothing'}")

    # 3. The real question - direct access to each pre-existing file by ID.
    reachable, blocked = [], []
    print()
    for entry in state["files"]:
        try:
            got = (
                drive.files()
                .get(fileId=entry["id"], fields="id, name, size, modifiedTime")
                .execute()
            )
            reachable.append(entry["name"])
            print(f"[ok]   {entry['name']:<28} reachable ({got.get('size', '?')} bytes)")
        except HttpError as e:
            blocked.append(entry["name"])
            print(f"[FAIL] {entry['name']:<28} NOT reachable (HTTP {e.status_code})")

    # 4. Verdict.
    total = len(state["files"])
    print("\n" + "=" * 72)
    if len(reachable) == total:
        print("VERDICT: folder pick DOES cover files that already existed in it.")
        print()
        print("  => A joining player can pick ONE FOLDER during setup.")
        print("  => Bucket count stops being a setup-tedium constraint.")
        print("  => Files created LATER still won't be covered - so the fixed")
        print("     up-front file set is still required. Nothing about the")
        print("     bucket design changes, only how many things get picked.")
    elif not reachable:
        print("VERDICT: folder pick does NOT cover pre-existing files either.")
        print()
        print("  => A joining player must multi-select every fixed file.")
        print("  => Keep the bucket count low (8 rather than 16) so setup stays")
        print("     tolerable, since picks = buckets + 2.")
    else:
        print("VERDICT: MIXED - this is the interesting one, capture it carefully.")
        print(f"  reachable: {reachable}")
        print(f"  blocked  : {blocked}")
        print("  => Access may depend on MIME type or on how each file was created.")
    print("=" * 72)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_setup = sub.add_parser("setup", help="account A: create the folder and its files")
    p_setup.add_argument("--label", default="accountA")
    p_setup.add_argument("--folder-name", default="WorldShare preexisting test")
    p_setup.set_defaults(func=cmd_setup)

    p_verify = sub.add_parser("verify", help="account B: check access after picking the folder")
    p_verify.add_argument("--label", default="accountB")
    p_verify.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
