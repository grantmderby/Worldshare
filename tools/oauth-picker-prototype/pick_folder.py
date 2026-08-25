#!/usr/bin/env python3
"""
WorldShare OAuth prototype - Step 2: pick_folder.py

Opens the Google Picker so the signed-in account (from authorize.py) can
select a Drive folder - including one owned/shared by someone else. This
is the step that actually grants drive.file-scoped access to that folder;
just knowing a folder ID isn't enough under drive.file.

Usage:
    python pick_folder.py --label accountA --api-key YOUR_PICKER_API_KEY

Prints the picked folder's ID and name, and saves them to
folder_<label>.json for the next step.
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
<html><head><title>WorldShare - pick a folder</title></head>
<body style="font-family:sans-serif;padding:2em">
<h2>WorldShare prototype: select the shared Drive folder</h2>
<p>Pick the folder you want this account to have drive.file access to.</p>
<script src="https://apis.google.com/js/api.js"></script>
<script>
function loadPicker() {{
  gapi.load('picker', {{callback: onPickerApiLoad}});
}}
function onPickerApiLoad() {{
  var view = new google.picker.DocsView(google.picker.ViewId.FOLDERS)
      .setSelectFolderEnabled(true)
      .setIncludeFolders(true);
  var picker = new google.picker.PickerBuilder()
      .addView(view)
      .setOAuthToken('{access_token}')
      .setDeveloperKey('{api_key}')
      .setCallback(pickerCallback)
      .build();
  picker.setVisible(true);
}}
function pickerCallback(data) {{
  if (data.action === google.picker.Action.PICKED) {{
    var folder = data.docs[0];
    window.location = 'http://127.0.0.1:{callback_port}/callback?folderId='
        + encodeURIComponent(folder.id)
        + '&folderName=' + encodeURIComponent(folder.name);
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
            _result["folderId"] = qs.get("folderId", [None])[0]
            _result["folderName"] = qs.get("folderName", [None])[0]
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
        print(f"Opening {url} - pick the folder in the browser tab...")
        webbrowser.open(url)

        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()
        _done.wait(timeout=300)
        httpd.shutdown()

    if not _result.get("folderId"):
        raise SystemExit("Timed out or no folder selected. Re-run and try again.")

    out_path = f"folder_{args.label}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(_result, f, indent=2)

    print(f"Picked folder: {_result['folderName']} ({_result['folderId']})")
    print(f"Saved to {out_path}")


if __name__ == "__main__":
    main()
