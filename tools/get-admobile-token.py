#!/usr/bin/env python3
"""Obtain the refresh token the patched AdMobile signs its API calls with.

Runs the OAuth consent flow against a loopback redirect, exchanges the authorization code, and
prints every value the credentials form asks for — including the publisher id, which it reads back
from the AdMob API so it does not have to be looked up in the console.

    python3 tools/get-admobile-token.py --client-id ... --client-secret ...

Needs an OAuth client of type Desktop in a Google Cloud project with the AdMob API enabled, and
your own account added as a test user on the consent screen. Standard library only.

Note that while the consent screen is in Testing, Google expires refresh tokens after seven days,
so this has to be re-run until the project is published.
"""

import argparse
import http.server
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
ACCOUNTS_ENDPOINT = "https://admob.googleapis.com/v1/accounts"

# The scopes AdMobile itself requests.
SCOPES = " ".join((
    "https://www.googleapis.com/auth/admob.readonly",
    "https://www.googleapis.com/auth/adsense.readonly",
))

DONE_PAGE = b"""<!doctype html><meta charset="utf-8">
<body style="font-family:system-ui;background:#121212;color:#e0e0e0;padding:3rem">
<h2 style="color:#f0c040">Authorised</h2><p>Back to the terminal.</p>"""


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    """Captures the single redirect Google sends after the consent screen."""

    code = None
    error = None

    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

        CallbackHandler.code = query.get("code", [None])[0]
        CallbackHandler.error = query.get("error", [None])[0]

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(DONE_PAGE)

    def log_message(self, *_):
        pass


def post_form(endpoint, fields):
    body = urllib.parse.urlencode(fields).encode()

    try:
        with urllib.request.urlopen(urllib.request.Request(endpoint, data=body)) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"{endpoint} returned {error.code}:\n{error.read().decode(errors='replace')}")


def authorize(client_id, client_secret, port):
    redirect_uri = f"http://127.0.0.1:{port}"

    server = http.server.HTTPServer(("127.0.0.1", port), CallbackHandler)
    threading.Thread(target=server.handle_request, daemon=True).start()

    url = AUTH_ENDPOINT + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        # Both are required for a refresh token to come back at all.
        "access_type": "offline",
        "prompt": "consent",
    })

    print("Open this in a browser if it does not open by itself:\n")
    print(url + "\n")
    try:
        webbrowser.open(url)
    except Exception:
        pass

    print(f"Waiting for the redirect on {redirect_uri} …")
    while CallbackHandler.code is None and CallbackHandler.error is None:
        pass
    server.server_close()

    if CallbackHandler.error:
        sys.exit(f"authorisation refused: {CallbackHandler.error}")

    return post_form(TOKEN_ENDPOINT, {
        "client_id": client_id,
        "client_secret": client_secret,
        "code": CallbackHandler.code,
        "grant_type": "authorization_code",
        "redirect_uri": redirect_uri,
    })


def publisher_id(access_token):
    """The AdMob API names the account of whoever holds the token, so it need not be typed in."""
    request = urllib.request.Request(ACCOUNTS_ENDPOINT)
    request.add_header("Authorization", "Bearer " + access_token)

    try:
        with urllib.request.urlopen(request) as response:
            accounts = json.load(response).get("account", [])
    except urllib.error.HTTPError as error:
        print(f"could not read the publisher id ({error.code}), fill it in by hand", file=sys.stderr)
        return None

    if not accounts:
        return None

    return accounts[0].get("publisherId"), accounts[0].get("currencyCode"), accounts[0].get("reportingTimeZone")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--client-secret", required=True)
    parser.add_argument("--port", type=int, default=8080, help="loopback port (default: 8080)")
    args = parser.parse_args()

    tokens = authorize(args.client_id, args.client_secret, args.port)

    refresh_token = tokens.get("refresh_token")
    if not refresh_token:
        sys.exit(
            "no refresh token returned. Google only sends one on a fresh consent: revoke the app "
            "at https://myaccount.google.com/permissions and run this again."
        )

    account = publisher_id(tokens.get("access_token", ""))

    print("\n" + "=" * 60)
    print("Paste these into the AdMobile credentials form\n")
    print(f"  OAuth client id      {args.client_id}")
    print(f"  OAuth client secret  {args.client_secret}")
    print(f"  Refresh token        {refresh_token}")

    if account:
        publisher, currency, time_zone = account
        print(f"  AdMob publisher id   {publisher}")
        if currency:
            print(f"  Report currency      {currency}")
        if time_zone:
            print(f"  Report time zone     {time_zone}")

    print("=" * 60)


if __name__ == "__main__":
    main()
