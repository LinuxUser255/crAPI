# OAuth tests

End-to-end checks of crAPI's OAuth 2.0 authorization server and its web UI (see
`docs/oauth-plan.md` and `docs/oauth-devlog.md`), run against the live lab through crapi-web.

```bash
tests/oauth/run.sh                  # API smoke test (smoke_test.py)
tests/oauth/run.sh --ui             # + headless-browser UI check (ui_check.py)
tests/oauth/run.sh --build --ui     # rebuild crapi-identity and crapi-web from source first
```

`run.sh` waits for `crapi-identity` and `crapi-web` to be healthy and, **before each test, deletes
all OAuth consents, tokens and authorization codes** in the lab database (registered clients are
kept). It exits 0 only when every check passes.

## smoke_test.py — the API

Discovery is public and points at the web consent page; `/authorize` refuses requests without a
user token, refuses lookalike redirect URIs and unregistered scopes, and handles approve/deny with
`state`; `/token` authenticates the client, binds the code to its `redirect_uri`, issues a JWT with
`scope` / `client_id` / `aud`, rejects code replay and accepts HTTP Basic for refresh; `/userinfo`
and `/consents` work; consent is remembered; `/revoke` needs client credentials; disconnecting an
app (user side) removes it, kills its refresh token and brings the consent prompt back.

Python 3, standard library only.

## ui_check.py — the browser

Login continuation (an OAuth link opened while logged out survives the trip through `/login`), the
consent page (content, Allow, Deny, the no-prompt path once authorized), both error paths (an
untrusted `redirect_uri` is never followed; a scope error is reported back to the app), the
Connected Apps panel with Disconnect, and that a plain login still lands on the dashboard.
DealerConnect is stubbed, so it doesn't need to be running. Screenshots go to
`$UI_SCREENSHOTS` (default `<tmp>/crapi-oauth-ui`).

Needs Playwright, which is not a project dependency:

```bash
pip install playwright==1.49.1 && python -m playwright install chromium
```

## Notes

Point either test elsewhere with `CRAPI_URL`, e.g. `CRAPI_URL=http://localhost:30080`. Plain HTTP
only: the lab's HTTPS certificate is self-signed and is rejected.

These checks describe the **secure** behaviour. Once the phase-5 teaching weaknesses exist, run
them with their `ENABLE_OAUTH_*` toggles off, which is the default.
