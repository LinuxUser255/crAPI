"""End-to-end smoke test of the crAPI OAuth 2.0 authorization server.

Drives the whole flow against a running lab through crapi-web: discovery, the
authorize step (describe, approve, deny), the token endpoint (code exchange,
refresh, client authentication), userinfo, consents and revocation, plus the
negative cases the protocol requires (unregistered redirect URIs and scopes,
wrong client secret, code replay, redirect_uri mismatch, revoked tokens).

It expects a clean slate: no earlier consent or tokens for the test user. Run it
through run.sh, which resets the OAuth tables first.

Environment overrides:
    CRAPI_URL                  base URL of crapi-web (default http://localhost:8889)
    OAUTH_DEALER_CLIENT_SECRET DealerConnect's secret (default: the lab value)
    OAUTH_DEALER_REDIRECT_URI  DealerConnect's registered redirect URI

Exit status is 0 when every check passes, 1 otherwise.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("CRAPI_URL", "http://localhost:8889").rstrip("/")
OAUTH = BASE + "/identity/api/oauth"
CLIENT_ID = "dealer-connect"
CLIENT_SECRET = os.environ.get("OAUTH_DEALER_CLIENT_SECRET", "DealerConnect!Secret123")
REDIRECT = os.environ.get("OAUTH_DEALER_REDIRECT_URI", "http://localhost:8900/oauth/callback")
USER = ("adam007@example.com", "adam007!123")

passed = 0
failed = 0


def check(label, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  PASS  {label}")
    else:
        failed += 1
        print(f"  FAIL  {label} {detail}")
    return condition


def field(body, key):
    """body[key] when body is a JSON object, else None (error bodies may be text)."""
    return body.get(key) if isinstance(body, dict) else None


def randtag():
    """A short random suffix so repeated runs don't collide on unique fields."""
    return base64.urlsafe_b64encode(os.urandom(6)).decode().rstrip("=")


def finish():
    print(f"\n==== {passed} passed, {failed} failed ====")
    sys.exit(0 if failed == 0 else 1)


def abort(reason):
    print(f"\n  ABORT {reason}: later steps depend on it")
    finish()


def call(method, url, body=None, token=None, form=False, basic=None):
    data = None
    headers = {}
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        raw = base64.b64encode(f"{basic[0]}:{basic[1]}".encode()).decode()
        headers["Authorization"] = "Basic " + raw
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw.decode(errors="replace")[:200]
    except urllib.error.URLError as error:
        print(f"  ABORT cannot reach {url}: {error.reason}")
        sys.exit(1)


def claims(jwt):
    payload = jwt.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def authorize_query(scope, redirect=REDIRECT, state=None):
    params = {
        "response_type": "code",
        "client_id": CLIENT_ID,
        "redirect_uri": redirect,
        "scope": scope,
    }
    if state:
        params["state"] = state
    return urllib.parse.urlencode(params)


def main():
    print(f"Target: {BASE}")

    print("\n== discovery (no auth) ==")
    status, meta = call("GET", OAUTH + "/.well-known/oauth-authorization-server")
    check("metadata is public", status == 200, status)
    check("advertises the code grant", "code" in (field(meta, "response_types_supported") or []))
    check(
        "advertises both grants",
        set(field(meta, "grant_types_supported") or []) == {"authorization_code", "refresh_token"},
    )
    check(
        "authorization_endpoint is the web consent page",
        str(field(meta, "authorization_endpoint")).endswith("/oauth-authorize"),
        field(meta, "authorization_endpoint"),
    )

    print("\n== consent page is served by the web app ==")
    request = urllib.request.Request(
        BASE + "/oauth-authorize?client_id=" + CLIENT_ID, headers={"Accept": "text/html"}
    )
    try:
        with urllib.request.urlopen(request) as response:
            html = response.read().decode(errors="replace")
            check("GET /oauth-authorize returns the SPA", response.status == 200 and "<div id=\"root\"" in html)
    except urllib.error.URLError as error:
        check("GET /oauth-authorize returns the SPA", False, error)

    print("\n== login ==")
    status, body = call(
        "POST", BASE + "/identity/api/auth/login", {"email": USER[0], "password": USER[1]}
    )
    if not check("user login", status == 200 and field(body, "token"), status):
        abort("login failed")
    user_token = body["token"]

    query = authorize_query("profile read:vehicles", state="xyz123")

    print("\n== authorize: describe the pending request ==")
    status, info = call("GET", f"{OAUTH}/authorize?{query}", token=user_token)
    check("GET /authorize succeeds", status == 200, status)
    check("names the client", field(info, "client_name") == "DealerConnect", info)
    check("lists requested scopes", field(info, "scopes") == ["profile", "read:vehicles"], info)
    check(
        "consent is required the first time",
        field(info, "consent_required") is True,
        "(stale consent? run via run.sh, which resets it)",
    )
    check("echoes state", field(info, "state") == "xyz123")

    print("\n== authorize: unauthenticated is refused ==")
    status, _ = call("GET", f"{OAUTH}/authorize?{query}")
    check("no user token -> 401", status == 401, status)

    print("\n== authorize: unregistered redirect_uri is refused ==")
    for lookalike in [
        REDIRECT + "/extra",
        REDIRECT.rsplit("/", 1)[0],
        "http://example.org/oauth/callback",
    ]:
        status, body = call(
            "GET", f"{OAUTH}/authorize?{authorize_query('profile', lookalike)}", token=user_token
        )
        check(
            f"refuses {lookalike}",
            status == 400 and field(body, "error") == "invalid_request",
            (status, body),
        )

    print("\n== authorize: scope the client did not register is refused ==")
    status, body = call(
        "GET", f"{OAUTH}/authorize?{authorize_query('profile admin')}", token=user_token
    )
    check("admin scope refused", status == 400 and field(body, "error") == "invalid_scope", (status, body))

    print("\n== authorize: approve ==")
    approve = {
        "response_type": "code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "scope": "profile read:vehicles",
        "state": "xyz123",
        "approved": "true",
    }
    status, body = call("POST", OAUTH + "/authorize", approve, token=user_token, form=True)
    if not check("approval succeeds", status == 200 and field(body, "redirect_url"), (status, body)):
        abort("no authorization code")
    parsed = urllib.parse.parse_qs(urllib.parse.urlparse(body["redirect_url"]).query)
    code = parsed["code"][0]
    check("redirect carries the state back", parsed.get("state") == ["xyz123"], parsed)
    check("redirect targets the registered uri", body["redirect_url"].startswith(REDIRECT))

    print("\n== authorize: deny ==")
    deny = dict(approve, scope="profile", approved="false")
    deny.pop("state")
    status, body = call("POST", OAUTH + "/authorize", deny, token=user_token, form=True)
    check("denial reports access_denied", field(body, "error") == "access_denied", (status, body))

    exchange = {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT,
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
    }

    print("\n== token: wrong client secret ==")
    status, body = call("POST", OAUTH + "/token", dict(exchange, client_secret="nope"), form=True)
    check(
        "bad secret -> invalid_client",
        status == 401 and field(body, "error") == "invalid_client",
        (status, body),
    )

    print("\n== token: redirect_uri must match the authorization request ==")
    status, body = call(
        "POST",
        OAUTH + "/token",
        dict(exchange, redirect_uri="http://example.org/oauth/callback"),
        form=True,
    )
    check(
        "mismatched redirect -> invalid_grant",
        status == 400 and field(body, "error") == "invalid_grant",
        (status, body),
    )

    print("\n== token: exchange the code ==")
    status, tokens = call("POST", OAUTH + "/token", exchange, form=True)
    if not check("exchange succeeds", status == 200 and field(tokens, "access_token"), (status, tokens)):
        abort("no access token")
    check("token_type is Bearer", tokens.get("token_type") == "Bearer", tokens)
    check("expires_in is seconds", tokens.get("expires_in") == 3600, tokens)
    check("refresh token issued", bool(tokens.get("refresh_token")))
    check("granted scope echoed", tokens.get("scope") == "profile read:vehicles", tokens)

    token_claims = claims(tokens["access_token"])
    print("  claims:", {k: token_claims.get(k) for k in ("sub", "scope", "client_id", "aud", "role")})
    check("sub is the user", token_claims.get("sub") == USER[0], token_claims)
    check("scope claim present", token_claims.get("scope") == "profile read:vehicles", token_claims)
    check("client_id claim present", token_claims.get("client_id") == CLIENT_ID, token_claims)
    check("aud is the client", token_claims.get("aud") in (CLIENT_ID, [CLIENT_ID]), token_claims)

    print("\n== token: the code is single-use ==")
    status, body = call("POST", OAUTH + "/token", exchange, form=True)
    check("replay -> invalid_grant", status == 400 and field(body, "error") == "invalid_grant", (status, body))

    client_basic = (CLIENT_ID, CLIENT_SECRET)
    refresh = {"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"]}

    print("\n== token endpoint accepts HTTP Basic client auth ==")
    status, body = call("POST", OAUTH + "/token", refresh, form=True, basic=client_basic)
    check("refresh via Basic auth", status == 200 and field(body, "access_token"), (status, body))
    check("scope preserved on refresh", field(body, "scope") == "profile read:vehicles", body)

    print("\n== userinfo with the access token ==")
    status, info = call("GET", OAUTH + "/userinfo", token=tokens["access_token"])
    check("userinfo succeeds", status == 200, (status, info))
    check("sub matches", field(info, "sub") == USER[0], info)
    check("profile scope returns the name", bool(field(info, "name")), info)

    print("\n== connected apps ==")
    status, consents = call("GET", OAUTH + "/consents", token=user_token)
    listed = status == 200 and isinstance(consents, list) and len(consents) > 0
    check("consents listed", listed, (status, consents))
    if listed:
        check("DealerConnect listed", consents[0].get("client_name") == "DealerConnect", consents[0])

    print("\n== second authorization skips the prompt ==")
    status, info = call("GET", f"{OAUTH}/authorize?{query}", token=user_token)
    check("consent no longer required", field(info, "consent_required") is False, info)

    print("\n== revoke ==")
    revoke = {"token": tokens["refresh_token"]}
    status, body = call("POST", OAUTH + "/revoke", revoke, form=True)
    check(
        "revoke without client auth -> 401",
        status == 401 and field(body, "error") == "invalid_client",
        (status, body),
    )
    status, _ = call("POST", OAUTH + "/revoke", revoke, form=True, basic=(CLIENT_ID, "nope"))
    check("revoke with wrong secret -> 401", status == 401, status)
    status, _ = call("POST", OAUTH + "/revoke", {"token": "no-such-token"}, form=True, basic=client_basic)
    check("unknown token still reports 200", status == 200, status)
    status, _ = call("POST", OAUTH + "/revoke", revoke, form=True, basic=client_basic)
    check("revoke returns 200", status == 200, status)
    status, body = call("POST", OAUTH + "/token", refresh, form=True, basic=client_basic)
    check(
        "revoked refresh token is rejected",
        status == 400 and field(body, "error") == "invalid_grant",
        (status, body),
    )

    print("\n== scope enforcement at the resource servers ==")

    def mint_token(scope):
        """A delegated access token for the given scope, via a full approve + exchange."""
        request = dict(approve, scope=scope)
        _, resp = call("POST", OAUTH + "/authorize", request, token=user_token, form=True)
        issued = urllib.parse.parse_qs(
            urllib.parse.urlparse(field(resp, "redirect_url") or "").query
        ).get("code", [""])[0]
        _, tok = call("POST", OAUTH + "/token", dict(exchange, code=issued), form=True)
        return field(tok, "access_token")

    full_token = mint_token("profile read:vehicles write:orders")
    narrow_token = mint_token("profile read:vehicles")
    order_body = {"product_id": 1, "quantity": 1}
    coupon_body = {"coupon_code": "SCOPETEST" + randtag(), "amount": "10"}

    # Workshop enforces write:orders on order creation (its default).
    status, _ = call("POST", BASE + "/workshop/api/shop/orders", order_body, token=narrow_token)
    check("Workshop refuses an order without write:orders (403)", status == 403, status)
    status, _ = call("POST", BASE + "/workshop/api/shop/orders", order_body, token=full_token)
    check("Workshop allows an order with write:orders", status == 200, status)
    status, _ = call("POST", BASE + "/workshop/api/shop/orders", order_body, token=user_token)
    check("Workshop still allows a first-party login token", status == 200, status)

    # Community does NOT enforce scope by default: the same narrow token that
    # Workshop refused can still create a coupon here. That gap is the lesson.
    status, _ = call("POST", BASE + "/community/api/v2/coupon/new-coupon", coupon_body, token=narrow_token)
    check("Community does not enforce scope on new-coupon (not 403)", status != 403, status)

    print("\n== disconnect the app (user side) ==")
    # A fresh grant, so there is a live refresh token for disconnect to kill.
    status, body = call("POST", OAUTH + "/authorize", approve, token=user_token, form=True)
    fresh_code = urllib.parse.parse_qs(urllib.parse.urlparse(field(body, "redirect_url") or "").query).get(
        "code", [""]
    )[0]
    status, fresh = call("POST", OAUTH + "/token", dict(exchange, code=fresh_code), form=True)
    check("fresh grant for the disconnect test", status == 200 and field(fresh, "refresh_token"), (status, fresh))

    disconnect_url = OAUTH + "/consents/" + CLIENT_ID
    status, _ = call("DELETE", disconnect_url)
    check("disconnect without a user token -> 401", status == 401, status)
    status, _ = call("DELETE", disconnect_url, token=user_token)
    check("disconnect returns 204", status == 204, status)
    status, consents = call("GET", OAUTH + "/consents", token=user_token)
    check("app no longer listed", status == 200 and consents == [], consents)
    status, body = call(
        "POST",
        OAUTH + "/token",
        {"grant_type": "refresh_token", "refresh_token": field(fresh, "refresh_token") or ""},
        form=True,
        basic=client_basic,
    )
    check(
        "the app's refresh token stops working",
        status == 400 and field(body, "error") == "invalid_grant",
        (status, body),
    )
    status, info = call("GET", f"{OAUTH}/authorize?{query}", token=user_token)
    check("consent is asked for again", field(info, "consent_required") is True, info)
    status, body = call("DELETE", disconnect_url, token=user_token)
    check("disconnecting twice -> 404", status == 404, (status, body))

    finish()


if __name__ == "__main__":
    main()
