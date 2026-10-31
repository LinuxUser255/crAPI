"""Verify crAPI's OAuth mode flag: `vulnerable` opens the OAuth weaknesses,
`secure` closes them.

The whole lab's OAuth behaviour is one flag, OAUTH_MODE. This script recreates
the OAuth services in vulnerable mode, checks the weaknesses are open, then
recreates them in secure mode (the default) and checks they are closed again.
So it proves both the weaknesses and that the flag gates them.

It drives `docker compose` itself, so it changes the running lab while it runs
and restores the secure default afterwards. Run it from a host with the stack up.

Weaknesses checked in vulnerable mode:
  - Loose redirect_uri validation (prefix match; OWASP API8)
  - Scope not enforced (a delegated token is over-privileged; OWASP API1/API5)

Environment: CRAPI_URL (default http://localhost:8889).
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("CRAPI_URL", "http://localhost:8889").rstrip("/")
OAUTH = BASE + "/identity/api/oauth"
CLIENT_ID = "dealer-connect"
CLIENT_SECRET = os.environ.get("OAUTH_DEALER_CLIENT_SECRET", "DealerConnect!Secret123")
REDIRECT = os.environ.get("OAUTH_DEALER_REDIRECT_URI", "http://localhost:8900/oauth/callback")
USER = ("adam007@example.com", "adam007!123")
COMPOSE_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "deploy", "docker")
)
# The services whose OAuth behaviour the flag changes.
MODE_SERVICES = ["crapi-identity", "crapi-workshop", "crapi-community"]

passed = failed = 0


def check(label, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  PASS  {label}")
    else:
        failed += 1
        print(f"  FAIL  {label} {detail}")


def call(method, url, body=None, token=None, form=False, basic=None):
    data, headers = None, {}
    if body is not None:
        data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        import base64

        headers["Authorization"] = "Basic " + base64.b64encode(
            f"{basic[0]}:{basic[1]}".encode()
        ).decode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")[:200]


def field(body, key):
    return body.get(key) if isinstance(body, dict) else None


def set_mode(mode):
    """Recreate the OAuth services with OAUTH_MODE=mode and wait for health."""
    print(f"  switching OAUTH_MODE={mode} (recreating {', '.join(MODE_SERVICES)})...")
    env = dict(os.environ, OAUTH_MODE=mode)
    subprocess.run(
        ["docker", "compose", "up", "-d", "--no-build", "--force-recreate", *MODE_SERVICES],
        cwd=COMPOSE_DIR,
        env=env,
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for service in MODE_SERVICES:
        for _ in range(60):
            result = subprocess.run(
                ["docker", "inspect", "-f", "{{.State.Health.Status}}", service],
                cwd=COMPOSE_DIR,
                capture_output=True,
                text=True,
            )
            if result.stdout.strip() == "healthy":
                break
            time.sleep(3)
        else:
            raise RuntimeError(f"{service} did not become healthy")
    time.sleep(2)  # health can lag the proxy a touch


def login():
    _, body = call("POST", BASE + "/identity/api/auth/login", {"email": USER[0], "password": USER[1]})
    return field(body, "token")


def authorize_query(redirect):
    return urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": CLIENT_ID,
            "redirect_uri": redirect,
            "scope": "profile read:vehicles",
            "state": "w1",
        }
    )


def mint_narrow_token(user_token):
    """A delegated token scoped to profile read:vehicles (no write:orders)."""
    approve = {
        "response_type": "code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "scope": "profile read:vehicles",
        "state": "w1",
        "approved": "true",
    }
    _, body = call("POST", OAUTH + "/authorize", approve, token=user_token, form=True)
    code = urllib.parse.parse_qs(urllib.parse.urlparse(field(body, "redirect_url") or "").query).get(
        "code", [""]
    )[0]
    _, tok = call(
        "POST",
        OAUTH + "/token",
        {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": REDIRECT,
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
        },
        form=True,
    )
    return field(tok, "access_token")


def run_checks(mode):
    """In vulnerable mode the weaknesses are open; in secure mode they are closed."""
    vulnerable = mode == "vulnerable"
    user_token = login()
    lookalike = REDIRECT + ".attacker.example"

    # 1. Loose redirect_uri validation.
    status, body = call("GET", f"{OAUTH}/authorize?{authorize_query(lookalike)}", token=user_token)
    if vulnerable:
        check("loose redirect_uri: a lookalike is accepted", status == 200, (status, body))
        approve = {
            "response_type": "code",
            "client_id": CLIENT_ID,
            "redirect_uri": lookalike,
            "scope": "profile read:vehicles",
            "state": "w1",
            "approved": "true",
        }
        status, body = call("POST", OAUTH + "/authorize", approve, token=user_token, form=True)
        url = field(body, "redirect_url") or ""
        check(
            "loose redirect_uri: the code is sent to the attacker URL",
            status == 200 and url.startswith(lookalike) and "code=" in url,
            (status, body),
        )
    else:
        check(
            "exact redirect_uri: the lookalike is refused",
            status == 400 and field(body, "error") == "invalid_request",
            (status, body),
        )

    # 2. Scope enforcement at the resource servers.
    narrow = mint_narrow_token(user_token)
    status, _ = call("POST", BASE + "/workshop/api/shop/orders", {"product_id": 1, "quantity": 1}, token=narrow)
    if vulnerable:
        check("no scope check: Workshop accepts an order without write:orders", status == 200, status)
    else:
        check("scope enforced: Workshop refuses an order without write:orders (403)", status == 403, status)

    coupon = {"coupon_code": "WK" + os.urandom(4).hex(), "amount": "10"}
    status, _ = call("POST", BASE + "/community/api/v2/coupon/new-coupon", coupon, token=narrow)
    if vulnerable:
        check("no scope check: Community accepts a coupon without write:orders", status != 403, status)
    else:
        check("scope enforced: Community refuses a coupon without write:orders (403)", status == 403, status)


def main():
    print(f"Target: {BASE}  (compose: {COMPOSE_DIR})")
    try:
        print("\n== vulnerable mode ==")
        set_mode("vulnerable")
        run_checks("vulnerable")
        print("\n== secure mode (default) ==")
        set_mode("secure")
        run_checks("secure")
    finally:
        print("\n  ensuring OAUTH_MODE=secure (the default)...")
        try:
            set_mode("secure")
        except Exception as exc:  # noqa: BLE001
            print(f"  WARNING could not restore secure mode: {exc}")
    print(f"\n==== {passed} passed, {failed} failed ====")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
