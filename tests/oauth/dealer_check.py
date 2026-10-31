"""End-to-end check of DealerConnect, the third-party OAuth client.

Drives a real browser through the whole flow: open DealerConnect, "Log in with
crAPI", approve on crAPI's consent page, land back on the DealerConnect
dashboard with the user's vehicles and the parts shop, place an order, and log
out. Also checks that DealerConnect enforces the OAuth state value.

Needs the full stack up with dealer-connect running, and no earlier consent for
the test user (run through run.sh --dealer, which resets the OAuth tables).

Requires Playwright:
    pip install playwright==1.49.1 && python -m playwright install chromium

Environment: DEALER_URL (default http://localhost:8900), CRAPI_URL (default
http://localhost:8889), UI_SCREENSHOTS (default <tmp>/crapi-oauth-ui).
"""

import os
import sys
import tempfile

from playwright.sync_api import sync_playwright

DEALER = os.environ.get("DEALER_URL", "http://localhost:8900").rstrip("/")
CRAPI = os.environ.get("CRAPI_URL", "http://localhost:8889").rstrip("/")
SHOTS = os.environ.get("UI_SCREENSHOTS", os.path.join(tempfile.gettempdir(), "crapi-oauth-ui"))
EMAIL, PASSWORD = "adam007@example.com", "adam007!123"

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


def crapi_login(page):
    """Log in to crAPI directly (the SPA), so the consent page has a session."""
    page.goto(CRAPI + "/login")
    page.fill("input[placeholder='Email']", EMAIL)
    page.fill("input[placeholder='Password']", PASSWORD)
    page.click("button[type='submit']")
    page.wait_for_url("**/dashboard", timeout=15000)
    page.wait_for_timeout(800)  # let redux-persist save the session


with sync_playwright() as p:
    browser = p.chromium.launch()
    context = browser.new_context(viewport={"width": 1280, "height": 900})
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))

    print("\n== DealerConnect landing page ==")
    page.goto(DEALER + "/")
    body = page.inner_text("body")
    check("shows the DealerConnect brand", "DealerConnect" in body, body[:200])
    check("offers Log in with crAPI", page.locator("a:has-text('Log in with crAPI')").count() == 1)

    print("\n== log in with crAPI (consent then back) ==")
    crapi_login(page)
    page.goto(DEALER + "/")
    page.click("a:has-text('Log in with crAPI')")
    # DealerConnect -> crAPI consent page.
    page.wait_for_url("**/oauth-authorize?**", timeout=15000)
    check("sent to crAPI's consent page", "/oauth-authorize?" in page.url, page.url)
    check("for the DealerConnect client", "client_id=dealer-connect" in page.url)
    page.wait_for_selector("button:has-text('Allow')", timeout=15000)
    page.click("button:has-text('Allow')")
    # crAPI -> DealerConnect callback -> dashboard.
    page.wait_for_url(DEALER + "/dashboard", timeout=15000)
    check("landed on the DealerConnect dashboard", page.url == DEALER + "/dashboard", page.url)

    body = page.inner_text("body")
    check("greets the user by name", "Hello, Adam" in body, body[:200])
    check("shows the granted scopes", "read:vehicles" in body and "write:orders" in body)
    check("lists the user's vehicle (VIN)", "VIN" in body and "Hyundai" in body, body[:600])
    check("shows the parts shop", "Parts shop" in body and "Seat" in body)
    check("shows account credit", "Account credit" in body)
    page.screenshot(path=os.path.join(SHOTS, "dealer-dashboard.png"), full_page=True)

    print("\n== place an order through DealerConnect ==")
    page.click("form[action='/order'] button:has-text('Order')")
    page.wait_for_url("**/dashboard?notice=ordered", timeout=15000)
    check("order placed via crAPI", "Your order was placed" in page.inner_text("body"))

    print("\n== log out of DealerConnect ==")
    page.click("form[action='/logout'] button")
    page.wait_for_url(DEALER + "/", timeout=15000)
    check("logged out, back to landing", page.locator("a:has-text('Log in with crAPI')").count() == 1)

    print("\n== logging in again skips the crAPI prompt (consent remembered) ==")
    saw_consent = {"v": False}
    page.on("framenavigated", lambda f: saw_consent.update(v=True) if "/oauth-authorize?" in f.url else None)
    page.click("a:has-text('Log in with crAPI')")
    page.wait_for_url(DEALER + "/dashboard", timeout=15000)
    check("straight back to the dashboard", page.url == DEALER + "/dashboard", page.url)
    check("passed through the consent page without a prompt", saw_consent["v"])
    check("no Allow button was shown", page.locator("button:has-text('Allow')").count() == 0)

    print("\n== DealerConnect enforces the OAuth state value ==")
    # A callback carrying a code but a state that was never issued must be rejected.
    page.goto(DEALER + "/oauth/callback?code=whatever&state=forged")
    body = page.inner_text("body")
    check("forged callback refused", "can't be completed" in body or "did not match" in body, body[:200])

    check("no uncaught page errors on DealerConnect", not errors, errors)
    browser.close()

print(f"\nScreenshots: {SHOTS}")
print(f"==== {passed} passed, {failed} failed ====")
sys.exit(0 if failed == 0 else 1)
