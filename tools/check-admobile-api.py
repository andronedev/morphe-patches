#!/usr/bin/env python3
"""Replay the calls patched AdMobile makes, to tell credentials apart from app plumbing.

Reads a file of `key=value` lines — the same values the credentials form takes — then walks the
chain the app walks: refresh the access token, list the account, list its apps, and generate a
network report. Each step prints its HTTP status and what came back.

    python3 tools/check-admobile-api.py credentials.txt

    client_id=000000000000-xxxx.apps.googleusercontent.com
    client_secret=GOCSPX-…
    refresh_token=1//…
    publisher_id=pub-0000000000000000        # optional, read from the API when absent

If every step passes here, the credentials are sound and any remaining problem is in the app.
If a step fails, its response says why, which is not visible from inside the app at all.

Standard library only, so it runs anywhere without setup.
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
ADMOB = "https://admob.googleapis.com"


def load(path):
    values = {}

    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue

            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()

    for required in ("client_id", "client_secret", "refresh_token"):
        if not values.get(required):
            sys.exit(f"{path} is missing {required}")

    return values


def request(method, url, token=None, body=None, form=None):
    data = None
    headers = {}

    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"

    if token:
        headers["Authorization"] = "Bearer " + token

    call = urllib.request.Request(url, data=data, headers=headers, method=method)

    try:
        with urllib.request.urlopen(call) as response:
            return response.status, response.read().decode(errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")
    except Exception as error:
        return 0, str(error)


def step(label, status, body, limit=600):
    ok = status == 200
    print(f"\n[{'ok' if ok else 'FAIL'}] {label} — HTTP {status}")
    print(body[:limit] + ("…" if len(body) > limit else ""))
    return ok


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)

    values = load(sys.argv[1])

    # 1. What the OkHttp authenticators do whenever the access token has expired.
    status, body = request("POST", TOKEN_ENDPOINT, form={
        "client_id": values["client_id"],
        "client_secret": values["client_secret"],
        "refresh_token": values["refresh_token"],
        "grant_type": "refresh_token",
    })
    if not step("refresh the access token", status, body):
        sys.exit("\nThe refresh token itself is not usable. Nothing else can work until it is.")

    access_token = json.loads(body).get("access_token")

    # 2. Names the publisher account, and is where the app gets the id it uses everywhere else.
    status, body = request("GET", f"{ADMOB}/v1/accounts", token=access_token)
    accounts_ok = step("list accounts", status, body)

    publisher = values.get("publisher_id")
    if accounts_ok and not publisher:
        found = json.loads(body).get("account", [])
        publisher = found[0].get("publisherId") if found else None

    if not publisher:
        sys.exit("\nNo AdMob account is reachable with this token, so there is nothing to report on.")

    print(f"\npublisher: {publisher}")

    # 3. The call whose emptiness is visible on the dashboard.
    status, body = request("GET", f"{ADMOB}/v1/accounts/{publisher}/apps", token=access_token)
    step("list apps", status, body)

    # 4. The report the home screen draws.
    today = date.today()
    start = today - timedelta(days=7)

    status, body = request(
        "POST",
        f"{ADMOB}/v1/accounts/{publisher}/networkReport:generate",
        token=access_token,
        body={"reportSpec": {
            "dateRange": {
                "startDate": {"year": start.year, "month": start.month, "day": start.day},
                "endDate": {"year": today.year, "month": today.month, "day": today.day},
            },
            "dimensions": ["DATE"],
            "metrics": ["ESTIMATED_EARNINGS", "CLICKS", "IMPRESSIONS"],
        }},
    )
    step("generate a network report for the last 7 days", status, body, limit=1200)

    print("\nEvery step above returning 200 means the credentials are sound: an empty dashboard "
          "then points at the app, not at Google.")


if __name__ == "__main__":
    main()
