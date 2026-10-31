"""Headless-browser walk-through of the OAuth web UI.

Covers login continuation (an OAuth link opened while logged out survives the
detour through /login), the consent page (Allow, Deny, already-authorized skip),
both error paths (an untrusted redirect_uri must never be followed; a scope error
is reported back to the app), the Connected-apps panel with Disconnect, and a
plain login still landing on the dashboard.

DealerConnect (localhost:8900) is answered by a route stub, so the test does not
need it running. Expects no earlier consent for the test user: run it through
`run.sh --ui`, which resets the OAuth tables first.

Requires Playwright, which is not a project dependency:
    pip install playwright==1.49.1 && python -m playwright install chromium

Environment: CRAPI_URL (default http://localhost:8889); UI_SCREENSHOTS, the
directory for screenshots (default: <tmp>/crapi-oauth-ui).
"""

import os
import sys
import tempfile
import urllib.parse

from playwright.sync_api import sync_playwright

BASE = os.environ.get("CRAPI_URL", "http://localhost:8889").rstrip("/")
CALLBACK = os.environ.get("OAUTH_DEALER_REDIRECT_URI", "http://localhost:8900/oauth/callback")
SHOTS = os.environ.get("UI_SCREENSHOTS", os.path.join(tempfile.gettempdir(), "crapi-oauth-ui"))
EMAIL, PASSWORD = "adam007@example.com", "adam007!123"
# Pages fade in (index.css fadeInUp, 0.5s); redux-persist writes the session to
# localStorage shortly after login. Both need a moment before a screenshot or reload.
SETTLE_MS = 800

os.makedirs(SHOTS, exist_ok=True)

passed = failed = 0


def check(label, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  PASS  {label}")
    else:
        failed += 1
        print(f"  FAIL  {label} {detail}")


def authorize_url(scope="profile read:vehicles", redirect=CALLBACK, state="ui1"):
    return BASE + "/oauth-authorize?" + urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": "dealer-connect",
            "redirect_uri": redirect,
            "scope": scope,
            "state": state,
        }
    )


with sync_playwright() as p:
    browser = p.chromium.launch()
    context = browser.new_context(viewport={"width": 1280, "height": 900})
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))

    # Nothing listens on 8900 yet (DealerConnect is phase 3): answer for it so we can read the URL.
    landed = []

    def fake_dealer(route):
        landed.append(route.request.url)
        route.fulfill(status=200, content_type="text/html", body="<h1>DealerConnect callback</h1>")

    context.route("http://localhost:8900/**", fake_dealer)

    print("\n== logged out: consent link goes to login, then comes back ==")
    page.goto(authorize_url())
    page.wait_for_url("**/login", timeout=15000)
    check("redirected to /login", page.url.endswith("/login"), page.url)
    page.fill("input[placeholder='Email']", EMAIL)
    page.fill("input[placeholder='Password']", PASSWORD)
    page.click("button[type='submit']")
    page.wait_for_url("**/oauth-authorize?**", timeout=15000)
    check("back on the consent page after login", "/oauth-authorize?" in page.url, page.url)
    page.wait_for_timeout(SETTLE_MS)
    check("query string survived the login detour", "state=ui1" in page.url and "client_id=dealer-connect" in page.url, page.url)

    print("\n== consent screen ==")
    page.wait_for_selector("text=wants to access your crAPI account", timeout=15000)
    body = page.inner_text("body")
    check("names the client", "DealerConnect wants to access your crAPI account" in body)
    check("shows who is signed in", "Signed in as" in body and EMAIL in body, body[:300])
    check("describes profile scope", "See your name, email address, phone number and role" in body)
    check("describes read:vehicles scope", "See your vehicles and where they are" in body)
    check("does not list unrequested scopes", "Place and manage shop orders" not in body)
    check("shows where the user will be sent", "localhost:8900" in body)
    page.wait_for_timeout(SETTLE_MS)
    page.screenshot(path=os.path.join(SHOTS, "ui-consent.png"), full_page=True)

    print("\n== deny ==")
    page.click("button:has-text('Deny')")
    page.wait_for_url("http://localhost:8900/**", timeout=15000)
    q = urllib.parse.parse_qs(urllib.parse.urlparse(page.url).query)
    check("deny returns to the app", page.url.startswith(CALLBACK), page.url)
    check("with error=access_denied", q.get("error") == ["access_denied"], q)
    check("and the state", q.get("state") == ["ui1"], q)

    print("\n== allow ==")
    page.goto(authorize_url())
    page.wait_for_selector("button:has-text('Allow')", timeout=15000)
    page.click("button:has-text('Allow')")
    page.wait_for_url("http://localhost:8900/**", timeout=15000)
    q = urllib.parse.parse_qs(urllib.parse.urlparse(page.url).query)
    check("allow returns to the app", page.url.startswith(CALLBACK), page.url)
    check("with a code", bool(q.get("code")), q)
    check("and the state", q.get("state") == ["ui1"], q)

    print("\n== already authorized: no prompt ==")
    before = len(landed)
    page.goto(authorize_url(state="ui2"))
    page.wait_for_url("http://localhost:8900/**", timeout=15000)
    q = urllib.parse.parse_qs(urllib.parse.urlparse(page.url).query)
    check("sent straight back with a code", bool(q.get("code")) and q.get("state") == ["ui2"], page.url)
    check("callback hit once", len(landed) == before + 1, landed[before:])

    print("\n== untrusted redirect_uri: error, never redirected ==")
    page.goto(authorize_url(redirect="http://example.org/steal"))
    page.wait_for_selector("text=can't be completed", timeout=15000)
    body = page.inner_text("body")
    check("error page shown", "redirect_uri is not registered" in body, body[:300])
    check("offers the dashboard, not the untrusted URL", "Go to dashboard" in body and "example.org" not in body)
    check("still on crAPI", page.url.startswith(BASE), page.url)
    page.wait_for_timeout(SETTLE_MS)
    page.screenshot(path=os.path.join(SHOTS, "ui-error-untrusted.png"), full_page=True)

    print("\n== unregistered scope: error, can report back to the app ==")
    page.goto(authorize_url(scope="profile admin"))
    page.wait_for_selector("text=can't be completed", timeout=15000)
    body = page.inner_text("body")
    check("explains the scope problem", "admin" in body and "not allowed" in body, body[:300])
    check("offers to return to the app", "Return to localhost:8900" in body)
    page.click("button:has-text('Return to localhost:8900')")
    page.wait_for_url("http://localhost:8900/**", timeout=15000)
    q = urllib.parse.parse_qs(urllib.parse.urlparse(page.url).query)
    check("returned with error=invalid_scope", q.get("error") == ["invalid_scope"], q)

    print("\n== connected apps on the profile page ==")
    page.goto(BASE + "/my-profile")
    page.wait_for_selector("text=Connected Apps", timeout=15000)
    page.wait_for_selector(".connected-apps-list .ant-list-item", timeout=15000)
    card = page.inner_text(".connected-apps-card")
    check("DealerConnect listed", "DealerConnect" in card, card)
    check("granted scopes shown", "profile" in card and "read:vehicles" in card, card)
    check("authorization date shown", "Authorized" in card, card)
    page.wait_for_timeout(SETTLE_MS)
    page.locator(".connected-apps-card").screenshot(path=os.path.join(SHOTS, "ui-connected-apps.png"))
    page.click(".connected-apps-card button:has-text('Disconnect')")
    page.click(".ant-popconfirm button:has-text('Disconnect')")
    page.wait_for_selector("text=No apps are connected to your account.", timeout=15000)
    check("list empties after disconnect", True)

    print("\n== disconnected: the prompt is back ==")
    page.goto(authorize_url(state="ui3"))
    page.wait_for_selector("button:has-text('Allow')", timeout=15000)
    check("consent asked again", "wants to access your crAPI account" in page.inner_text("body"))

    print("\n== regression: a plain login still lands on the dashboard ==")
    fresh = browser.new_context().new_page()
    fresh.goto(BASE + "/login")
    fresh.fill("input[placeholder='Email']", EMAIL)
    fresh.fill("input[placeholder='Password']", PASSWORD)
    fresh.click("button[type='submit']")
    fresh.wait_for_url("**/dashboard", timeout=15000)
    check("login without a pending page -> /dashboard", fresh.url.endswith("/dashboard"), fresh.url)

    check("no uncaught page errors", not errors, errors)
    browser.close()

print(f"\nScreenshots: {SHOTS}")
print(f"==== {passed} passed, {failed} failed ====")
sys.exit(0 if failed == 0 else 1)
