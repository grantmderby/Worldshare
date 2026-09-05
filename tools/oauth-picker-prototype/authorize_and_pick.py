#!/usr/bin/env python3
"""
WorldShare OAuth prototype - authorize_and_pick.py

Combined authorize + Picker flow for DESKTOP apps, per Google's official
guide:
https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker

Earlier scripts in this prototype (authorize.py + pick_folder.py /
pick_file.py) used the WEB-APP style Picker integration - a PickerBuilder
JS widget fed a pre-existing access token via setOAuthToken() - combined
with a token obtained through the desktop/installed-app OAuth flow. That
combination is not the documented desktop pattern, and consistently
failed to grant real per-file access in testing (empty folder listings,
404s on direct file-ID checks, even for the folder's own owner, even for
an individually-picked single file).

Google's actual documented desktop/mobile pattern triggers the Picker as
PART of the OAuth consent screen itself, via `trigger_onepick=true` on
the standard authorization URL. The redirect back to the app then carries
BOTH the normal authorization `code` AND a `picked_file_ids` parameter
naming what was selected - the file-grant and the token are issued
together in one transaction, which is presumably why the two-step
approach didn't work.

Usage:
    python authorize_and_pick.py --label accountA
    python authorize_and_pick.py --label accountA --allow-folder-selection
"""
import argparse
import json
import http.server
import socketserver
import threading
import urllib.parse
import webbrowser

from google_auth_oauthlib.flow import Flow

SCOPES = ["https://www.googleapis.com/auth/drive.file"]

_result = {}
_done = threading.Event()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/":
            self.send_response(404)
            self.end_headers()
            return
        qs = urllib.parse.parse_qs(parsed.query)
        _result["code"] = qs.get("code", [None])[0]
        _result["picked_file_ids"] = qs.get("picked_file_ids", [None])[0]
        _result["error"] = qs.get("error", [None])[0]
        _result["scope"] = qs.get("scope", [None])[0]
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(
            b"<html><body style='font-family:sans-serif;padding:2em'>"
            b"<h2>Got it - you can close this tab.</h2></body></html>"
        )
        _done.set()

    def log_message(self, format, *args):
        pass  # keep stdout quiet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="accountA")
    parser.add_argument("--client-secret", default="client_secret.json")
    parser.add_argument("--allow-folder-selection", action="store_true")
    parser.add_argument("--allow-multiple", action="store_true")
    parser.add_argument(
        "--file-ids",
        default=None,
        help="Comma-separated Drive file IDs. Documented as filtering the picker's "
             "search results - if it works as hoped, the picker shows ONLY these "
             "files, so a joining player never has to navigate Drive at all.",
    )
    parser.add_argument(
        "--mimetypes",
        default=None,
        help="Comma-separated MIME types to filter the picker by, "
             "e.g. application/zip,application/json",
    )
    args = parser.parse_args()

    with socketserver.TCPServer(("127.0.0.1", 0), Handler) as httpd:
        port = httpd.server_address[1]
        redirect_uri = f"http://127.0.0.1:{port}/"

        flow = Flow.from_client_secrets_file(
            args.client_secret, scopes=SCOPES, redirect_uri=redirect_uri
        )

        extra = {
            "access_type": "offline",
            "prompt": "consent",
            "trigger_onepick": "true",
        }
        if args.allow_folder_selection:
            extra["allow_folder_selection"] = "true"
        if args.allow_multiple:
            extra["allow_multiple"] = "true"
        if args.file_ids:
            extra["file_ids"] = args.file_ids
        if args.mimetypes:
            extra["mimetypes"] = args.mimetypes

        auth_url, _ = flow.authorization_url(**extra)

        print(f"Opening browser for consent + Picker (redirect_uri={redirect_uri}) ...")
        webbrowser.open(auth_url)

        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()
        _done.wait(timeout=300)
        httpd.shutdown()

    if _result.get("error"):
        raise SystemExit(f"Authorization/Picker error: {_result['error']}")
    if not _result.get("code"):
        raise SystemExit("Timed out or no authorization code received. Re-run and try again.")

    flow.fetch_token(code=_result["code"])
    creds = flow.credentials

    token_path = f"token_{args.label}.json"
    with open(token_path, "w", encoding="utf-8") as f:
        f.write(creds.to_json())
    print(f"Saved credentials to {token_path}")

    picked_raw = _result.get("picked_file_ids")
    picked_ids = picked_raw.split(",") if picked_raw else []
    picked_path = f"picked_file_ids_{args.label}.json"
    with open(picked_path, "w", encoding="utf-8") as f:
        json.dump({"picked_file_ids": picked_ids}, f, indent=2)

    if picked_ids:
        print(f"Picked file IDs: {picked_ids}")
    else:
        print(
            "No picked_file_ids came back - either nothing was selected, or the "
            "Picker step wasn't shown. Check the consent screen carefully."
        )
    print(f"Saved to {picked_path}")


if __name__ == "__main__":
    main()
