#!/usr/bin/env python3
"""
WorldShare OAuth prototype - pick_file.py

Opens the Google Picker so the signed-in account (from authorize.py) can
select a single Drive FILE (not a folder) - including one owned/shared by
someone else. Unlike pick_folder.py, this does not use the folder-select
view; it grants drive.file-scoped access to exactly the one file picked.

This exists to test a narrower, more promising design than "pick a shared
folder": does drive.file access to an individually-picked file persist
when that file's *content* is later overwritten by someone else (same
file ID), even though access to a picked *folder*'s other contents does
not (confirmed separately via pick_folder.py + check_access.py).

Usage:
    python pick_file.py --label accountA --api-key YOUR_PICKER_API_KEY

Prints the picked file's ID and name, and saves them to
picked_file_<label>.json for the next step.
"""
import argparse
import http.server
import json
import socketserver
import threading
import urllib.parse
import webbrowser

from google.oauth2.credentials import Credentials

PICKER_HTML = """<!doctype html>
<html><head><title>WorldShare - pick a file</title></head>
<body style="font-family:sans-serif;padding:2em">
<h2>WorldShare prototype: select the shared Drive file</h2>
<p>Pick the specific file you want this account to have drive.file access to.</p>
<script src="https://apis.google.com/js/api.js"></script>
<script>
function loadPicker() {{
  gapi.load('picker', {{callback: onPickerApiLoad}});
}}
function onPickerApiLoad() {{
  var view = new google.picker.DocsView(google.picker.ViewId.DOCS)
      .setIncludeFolders(true)
      .setOwnedByMe(false);
  var sharedView = new google.picker.DocsView(google.picker.ViewId.DOCS)
      .setIncludeFolders(true)
      .setEnableTeamDrives(false);
  var picker = new google.picker.PickerBuilder()
      .addView(view)
      .addView(google.picker.ViewId.RECENTLY_PICKED)
      .addView(sharedView)
      .setOAuthToken('{access_token}')
      .setDeveloperKey('{api_key}')
      .setCallback(pickerCallback)
      .build();
  picker.setVisible(true);
}}
function pickerCallback(data) {{
  if (data.action === google.picker.Action.PICKED) {{
    var file = data.docs[0];
    window.location = 'http://127.0.0.1:{callback_port}/callback?fileId='
        + encodeURIComponent(file.id)
        + '&fileName=' + encodeURIComponent(file.name);
  }} else if (data.action === google.picker.Action.CANCEL) {{
    document.body.innerHTML += '<p>Cancelled - close this tab and re-run the script.</p>';
  }}
}}
window.onload = loadPicker;
</script>
</body></html>
"""

_result = {}
_done = threading.Event()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(self._page.encode("utf-8"))
        elif parsed.path == "/callback":
            qs = urllib.parse.parse_qs(parsed.query)
            _result["fileId"] = qs.get("fileId", [None])[0]
            _result["fileName"] = qs.get("fileName", [None])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(
                b"<html><body style='font-family:sans-serif;padding:2em'>"
                b"<h2>Got it - you can close this tab.</h2></body></html>"
            )
            _done.set()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # keep stdout quiet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="accountA")
    parser.add_argument("--api-key", required=True, help="Picker API browser key")
    args = parser.parse_args()

    with open(f"token_{args.label}.json", encoding="utf-8") as f:
        creds_info = json.load(f)
    creds = Credentials.from_authorized_user_info(creds_info)
    if not creds.valid and creds.refresh_token:
        import google.auth.transport.requests

        creds.refresh(google.auth.transport.requests.Request())

    with socketserver.TCPServer(("127.0.0.1", 0), Handler) as httpd:
        port = httpd.server_address[1]
        Handler._page = PICKER_HTML.format(
            access_token=creds.token, api_key=args.api_key, callback_port=port
        )
        url = f"http://127.0.0.1:{port}/"
        print(f"Opening {url} - pick the FILE (not folder) in the browser tab...")
        webbrowser.open(url)

        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()
        _done.wait(timeout=300)
        httpd.shutdown()

    if not _result.get("fileId"):
        raise SystemExit("Timed out or no file selected. Re-run and try again.")

    out_path = f"picked_file_{args.label}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(_result, f, indent=2)

    print(f"Picked file: {_result['fileName']} ({_result['fileId']})")
    print(f"Saved to {out_path}")


if __name__ == "__main__":
    main()
