# OAuth 2.0 — Development Log

Running record of the OAuth work described in [oauth-plan.md](oauth-plan.md): what's done, what
broke and why, decisions taken, and what's next. Newest entries first within each section.

---

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Authorization Server core in `crapi-identity` | ✅ Done, verified, committed to `develop` |
| 2 | Consent screen, Connected-apps panel, login continuation (web) | ✅ Done, verified, committed to `develop` |
| 3 | DealerConnect client app (`services/dealer-app`) + compose/proxy | ⏳ Next |
| 4 | Scope handling in Workshop / Community | ⏳ |
| 5 | Teaching weaknesses behind `ENABLE_OAUTH_*` toggles | ⏳ |
| 6 | Challenges, solutions, Postman, chatbot OpenAPI spec | ⏳ |

---

## Decisions

Answers to the open questions in plan §10, plus decisions made while building.

| # | Decision | Status | Notes |
|---|---|---|---|
| D1 | Token format: RS256 JWT, signed by the existing `JwtProvider` key | Adopted (phase 1) | Other services accept OAuth tokens with no changes |
| D2 | DealerConnect host port **8900**, redirect `http://localhost:8900/oauth/callback` | Assumed | Set via `OAUTH_DEALER_REDIRECT_URI`; confirm in phase 3 |
| D3 | `ENABLE_OAUTH=true` by default | Adopted (phase 1) | Safe while phase 1 only follows the RFCs; revisit before phase 5 lands |
| D4 | Clients are seeded only, no dynamic registration | Adopted (phase 1) | `InitialDataConfig.addOAuthClients()` |
| D5 | Scopes: `profile`, `read:vehicles`, `write:orders`, `mechanic`, `admin` | Adopted (phase 1) | DealerConnect is registered for `profile read:vehicles write:orders` |
| D6 | **Authorize step is web-app-mediated, not a raw browser 302** | Adopted (phase 1) | crAPI uses bearer tokens, not browser sessions, so a bare browser redirect to `/authorize` can't identify the user. `GET /authorize` returns JSON describing the request for the consent screen; `POST /authorize` records the decision and returns `redirect_url`. Deviates from a textbook AS; the alternative is a cookie session. |
| D7 | `/revoke` authenticates the **client**, not the user (RFC 7009) | Adopted (phase 1) | See error E3 |
| D8 | Every service the fork modifies gets a `build:` context in compose | Adopted (phase 1) | `crapi-identity` added; see error E5 |
| D9 | Consent page route is **`/oauth-authorize`** (one path segment), not `/oauth/authorize` | Adopted (phase 2) | The web app's API base URLs are relative (`identity/`), so on a two-segment route every call would resolve under `/oauth/…`. See E7 |
| D10 | Discovery `authorization_endpoint` = the web consent page (`<base>/oauth-authorize`) | Adopted (phase 2) | RFC 8414: it's where the client sends the user's browser. The API endpoint needs a bearer token a browser doesn't have. See E8 |
| D11 | Users disconnect apps with **`DELETE /identity/api/oauth/consents/{clientId}`** (user token) | Adopted (phase 2) | `/revoke` is client-authenticated (D7), so the user side needed its own endpoint. Removes the consent, revokes the client's tokens and burns its unredeemed codes for that user |
| D12 | Login continuation via router state (`AfterLogin` → `/login` with `state.from`), helper `pathAfterLogin` in `utils.ts` | Adopted (phase 2) | Router state is set by the app, never read from the URL, so it can't become an open redirect. Applies to every protected page, not just OAuth |

---

## Phase 2 — Consent screen, connected apps, login continuation

**Date:** 2026-10-30 · **State:** verified, committed to `develop`

### Built

- **Web**
  - `/oauth-authorize` consent page (`containers/oauthAuthorize`, `components/oauthAuthorize`): validates the request with the user's token, shows the app, who is signed in, each requested scope in plain words and where the user will be sent; Allow/Deny then leaves the SPA for the app's `redirect_uri`. Skips the prompt when consent already covers the scopes.
  - Two error paths: an unknown client or unregistered `redirect_uri` shows an error with **"Go to dashboard"** and never follows the untrusted URL; protocol errors after that (bad scope, wrong `response_type`) offer **"Return to <app>"** with the error in the redirect.
  - Connected Apps card on `/my-profile` (`containers/connectedApps`, `components/connectedApps`): app, granted scopes, authorization date, Disconnect with confirmation.
  - Login continuation (D12): `layout.tsx` (`AfterLogin`, `BeforeLogin`), `containers/login/login.js`, `utils.pathAfterLogin`.
  - Data layer following the existing pattern: `actions/oauthActions.ts`, `sagas/oauthSaga.ts` (registered in `sagas/index.ts`), URLs in `APIConstant.ts`, action types, messages.
- **Identity**: `DELETE /consents/{clientId}` (D11) + `disconnectClient`, repository queries; discovery points at the consent page (D10). 2 new unit tests.
- **Tests**: `smoke_test.py` gains discovery and disconnect checks (43 → 52); new `tests/oauth/ui_check.py`, a headless-browser walk-through (30 checks); `run.sh --ui` runs it and `--build` now rebuilds web too.

### Verification

| Check | Result |
|---|---|
| Identity suite | 115 tests, 0 failures |
| `npm run lint`, `tsc` on `src/`, `npm run build` | Clean; no warnings in new or changed files (the build's warnings are all pre-existing) |
| API smoke test | 52/52 |
| Browser UI check | 30/30, screenshots reviewed |

### Errors & fixes

| # | Symptom | Cause | Fix |
|---|---|---|---|
| E7 | (Caught in design) API calls from a nested route would 404 | `crapienv` base URLs are relative (`identity/`) and `index.html` has no `<base>`; every existing route is one segment deep | Single-segment route `/oauth-authorize` (D9) |
| E8 | Discovery advertised `/identity/api/oauth/authorize` as `authorization_endpoint` | Phase 1 pointed it at the API, which needs a bearer token a browser doesn't have | Points at the web consent page (D10) |
| E9 | (Caught in planning) The Disconnect button had no endpoint to call | `/revoke` only accepts client credentials (D7) | New user-side `DELETE /consents/{clientId}` (D11) |
| E10 | Circular import `layout.tsx` ↔ `containers/login/login.js` | The `pathAfterLogin` helper lived in `layout.tsx`, which imports the login container | Moved to `utils.ts` |
| E11 | Consent card stretched full width instead of a 520px panel | Global leak: the forum stylesheet sets `.ant-card { max-width: 100% }` with equal specificity, loaded later | Selector scoped to `.oauth-page .oauth-card` |
| E12 | UI test: reloading a page right after login bounced to `/login` | Test timing, not an app bug: redux-persist writes the session to localStorage just after login; a reload within milliseconds reads the old state | Test waits ~0.8s after login (`SETTLE_MS`) |
| E13 | First screenshots looked washed out | Captured mid-animation (`index.css` `fadeInUp`, 0.5s); opacity is 1 once settled | Test waits before screenshots |

### Observed, not fixed (pre-existing, outside OAuth)

- The chatbot uses the 🤖 emoji as an avatar **image URL**, so every page requests `/%F0%9F%A4%96` and gets a 404.
- `containers/login/login.js`: after a successful login the callback also falls into the error branch (`if SUCCESS … if REDIRECT … else setHasErrored`). Harmless because the page navigates away.
- Unused imports in `profile.tsx`, `post.tsx`, `Bot.tsx` and others produce build warnings.

---

## Phase 1 — Authorization Server core

**Date:** 2026-10-30 · **State:** verified, committed to `develop`

### Built

- **Entities** (`entity/`): `OAuthClient`, `OAuthAuthorizationCode`, `OAuthAccessToken`, `OAuthConsent`. Tables are created automatically (`ddl-auto=update`).
- **Repositories**: one per entity.
- **DTOs** (`model/`): `OAuthTokenResponse`, `OAuthError`, `OAuthAuthorizeInfo`, `UserInfoResponse`, `OAuthMetadata`.
- **Logic**: `OAuthService` + `OAuthServiceImpl`, `exception/OAuthException`.
- **HTTP**: `OAuthController` under `/identity/api/oauth`:

  | Endpoint | Auth | Purpose |
  |---|---|---|
  | `GET /authorize` | user token | Validate the request and describe it for the consent screen |
  | `POST /authorize` | user token | Approve/deny; issue code; return `redirect_url` |
  | `POST /token` | client creds (Basic or form) | `authorization_code` and `refresh_token` grants |
  | `GET /userinfo` | OAuth access token | Profile, filtered by scope |
  | `GET /consents` | user token | Connected apps |
  | `POST /revoke` | client creds | Revoke the client's own tokens |
  | `GET /.well-known/oauth-authorization-server` | none | RFC 8414 metadata |

- **Wiring**: `JwtProvider.generateOAuthToken` (adds `scope`, `client_id`, `aud`); `WebSecurityConfig` makes `/token`, `/revoke` and discovery public; seeded DealerConnect client; `app.oauth.*` in `application.properties`; `ENABLE_OAUTH` in `.env` + compose.
- **Tests**: `OAuthServiceImplTest` (18 cases).

### Verification

| Check | Result |
|---|---|
| Full identity suite (`gradle spotlessCheck test`, jdk17 image) | 113 tests, 0 failures |
| End-to-end smoke test against the running stack | 43/43 |
| OAuth access token on Identity / Community / Workshop | 200 / 200 / 200 |
| Regular login token (regression) | Still works |

What the smoke test covers: discovery is public; `/authorize` refuses requests without a user token; exact `redirect_uri` match (three lookalikes refused); unregistered scope refused; approve/deny; `state` round trip; wrong client secret; `redirect_uri` binding at `/token`; code exchange + claims; single-use code; refresh over HTTP Basic; userinfo; consents; consent skip on second request; revoke needs client auth; unknown token returns 200; revoked refresh token rejected.

### Errors & fixes

| # | Symptom | Cause | Fix |
|---|---|---|---|
| E1 | `GET /userinfo` → **500 "Unable to access lob stream"** | `UserDetails.picture` is a Postgres large object, readable only inside a transaction; `getUserInfo` loaded it without one | `@Transactional` on `getUserInfo`, as the other services do |
| E2 | Controller `if (user == null)` branches could never run | `UserService.getUserFromToken` **throws** `EntityNotFoundException` instead of returning null | `currentUser(request)` helper catches and returns null |
| E3 | `POST /revoke` → **401** in the smoke test | Design flaw: the endpoint had no authentication of its own, so if it were opened anyone could revoke any token; only the default "needs a user token" rule blocked it, which is the wrong gate | Client authentication (as at `/token`); a client may revoke only tokens issued to it; endpoint made public in security config |
| E4 | Smoke test "consent required the first time" fails on rerun | Test artifact: consent from the earlier run is stored in Postgres | Run script clears `oauth_consent`, `oauth_access_token`, `oauth_authorization_code` before each run |
| E5 | (Caught before it bit) Changes to identity wouldn't run | `crapi-identity` was **pulled** from upstream; compose had no `build:` context | Added `build: context: ../../services/identity`; `pull --ignore-buildable` now skips it (D8) |
| E6 | `spotlessCheck` crashes locally (`NoSuchMethodError … DCReference.getSourcePosition`) | google-java-format 1.7 doesn't work on JDK 19 | Build/test in `gradle:8.1-jdk17-focal` (the Dockerfile's toolchain). Installing JDK 17 locally also fixes it |

### Notes / observations

- jjwt serializes `aud` as an array (`["dealer-connect"]`). Valid per RFC 7519; consumers must accept both forms.
- **Scope isn't enforced yet.** A token granted only `read:vehicles` can read Community posts and Workshop products, the same reach as the user's own login. This is expected until phase 4.
- The seeded DealerConnect secret is `DealerConnect!Secret123` (only its BCrypt hash is stored). It's a lab credential; phase 3's client is configured with it.

---

## How to verify

```bash
# Unit + full identity suite (local JDK 19 can't run spotless; use the build image)
docker run --rm -v "$PWD/services/identity":/app -w /app gradle:8.1-jdk17-focal \
  gradle spotlessCheck test --no-daemon

# Web
cd services/web && npm run lint && npm run build

# End-to-end against the running lab (resets OAuth grants before each test)
tests/oauth/run.sh                  # API smoke test, 52 checks
tests/oauth/run.sh --ui             # + headless-browser UI check, 30 checks (needs Playwright)
tests/oauth/run.sh --build --ui     # rebuild crapi-identity and crapi-web first
```

See [tests/oauth/README.md](../tests/oauth/README.md). The scripts are linted in CI (shellcheck,
bash 3.2 syntax, `py_compile`); the tests themselves need the live stack, so they are not CI jobs.

---

## Open items

| # | Item | Phase |
|---|---|---|
| ~~T1~~ | ~~Move the OAuth smoke test + reset script into the repo~~ — done 2026-10-30: `tests/oauth/` | — |
| ~~T2~~ | ~~Commit phase 1 to `develop` and mirror~~ — done 2026-10-30 | — |
| T3 | Root `/.well-known/oauth-authorization-server` in both nginx templates (today it's only under `/identity/api/oauth/`) | 3 |
| T4 | Confirm D2 (port 8900) and whether DealerConnect serves HTTP or HTTPS | 3 |
| T5 | Revisit D3 (default on) before the weaknesses land | 5 |
| T6 | Refresh-token rotation (phase 1 reuses the same refresh token) | decide in 5: secure default vs teaching surface |
| T7 | DealerConnect logo: the seeded `images/dealer-connect.png` doesn't exist, so the UI shows a fallback icon | 3 |
| ~~T8~~ | ~~Commit phase 2 to `develop` and mirror~~ — done 2026-10-30 | — |

---

## Next: Phase 3 plan

- **`services/dealer-app`** — a small Go web app modeled on `services/gateway-service`, published on port 8900 (D2/T4), with a `build:` context in compose (D8).
- **Flow** — a "Log in with crAPI" button sends the browser to the discovery `authorization_endpoint` (`<crapi-web>/oauth-authorize?response_type=code&client_id=dealer-connect&redirect_uri=…&scope=…&state=…`). `/oauth/callback` checks `state`, exchanges the code at `/identity/api/oauth/token` with the client secret, then calls crAPI APIs with the access token: list the user's vehicles, place an order.
- **Wiring** — the secret through env (`OAUTH_DEALER_CLIENT_SECRET`, same value as identity's seed); crAPI's base URL for the browser (`localhost:8889`) versus for server-to-server calls (`crapi-web` on the Docker network); the root discovery location in both nginx templates (T3); a real logo (T7).
- **Tests** — extend `ui_check.py` to drive the real DealerConnect instead of the route stub, end to end.
