# OAuth tests

End-to-end checks of crAPI's OAuth 2.0 authorization server and its web UI (see
`docs/oauth-plan.md` and `docs/oauth-devlog.md`), run against the live lab through crapi-web.

```bash
tests/oauth/run.sh                       # API smoke test (smoke_test.py)
tests/oauth/run.sh --ui                  # + crAPI web UI check (ui_check.py)
tests/oauth/run.sh --dealer              # + DealerConnect end-to-end (dealer_check.py)
tests/oauth/run.sh --weaknesses          # + weakness toggles (weaknesses.py)
tests/oauth/run.sh --build --ui --dealer # rebuild the modified services first
```

`run.sh` waits for the services to be healthy and, **before each test, deletes all OAuth consents,
tokens and authorization codes** in the lab database (registered clients are kept). It exits 0 only
when every check passes.

## smoke_test.py — the API

Discovery is public and points at the web consent page; `/authorize` refuses requests without a
user token, refuses lookalike redirect URIs and unregistered scopes, and handles approve/deny with
`state`; `/token` authenticates the client, binds the code to its `redirect_uri`, issues a JWT with
`scope` / `client_id` / `aud`, rejects code replay and accepts HTTP Basic for refresh; `/userinfo`
and `/consents` work; consent is remembered; `/revoke` needs client credentials; disconnecting an
app (user side) removes it, kills its refresh token and brings the consent prompt back. It also
checks scope enforcement in the default secure mode: a `profile read:vehicles` token is refused an
order by Workshop and a coupon by Community (403), while a `write:orders` token and a first-party
login token are allowed.

Python 3, standard library only.

## ui_check.py — the browser

Login continuation (an OAuth link opened while logged out survives the trip through `/login`), the
consent page (content, Allow, Deny, the no-prompt path once authorized), both error paths (an
untrusted `redirect_uri` is never followed; a scope error is reported back to the app), the
Connected Apps panel with Disconnect, and that a plain login still lands on the dashboard.
DealerConnect is stubbed, so it doesn't need to be running. Screenshots go to
`$UI_SCREENSHOTS` (default `<tmp>/crapi-oauth-ui`).

## dealer_check.py — DealerConnect end-to-end

Drives a real browser through the whole third-party flow against the running `dealer-connect`
container: the landing page, "Log in with crAPI", approving on crAPI's consent page, landing back
on DealerConnect's dashboard with the user's vehicles and the parts shop, placing an order, logging
out, logging in again without a prompt (consent remembered), and DealerConnect rejecting a callback
with a forged `state`. Needs the full stack with `dealer-connect` up (`--dealer` starts it).

## weaknesses.py — the `OAUTH_MODE` flag

The whole lab's OAuth behaviour is one flag, `OAUTH_MODE` (`secure` by default, or `vulnerable`).
This recreates the OAuth services in `vulnerable` mode and checks the weaknesses are open, then in
`secure` mode and checks they are closed — proving both the weaknesses and that the flag gates them.
It drives `docker compose` itself, so it changes the running lab and restores the secure default.
Covered: loose `redirect_uri` validation, and scope not enforced. Python 3, standard library only.

## Playwright

`ui_check.py` and `dealer_check.py` need Playwright, which is not a project dependency:

```bash
pip install playwright==1.49.1 && python -m playwright install chromium
```

## Notes

Point either test elsewhere with `CRAPI_URL`, e.g. `CRAPI_URL=http://localhost:30080`. Plain HTTP
only: the lab's HTTPS certificate is self-signed and is rejected.

These checks describe the **secure** behaviour. Once the phase-5 teaching weaknesses exist, run
them with their `ENABLE_OAUTH_*` toggles off, which is the default.
