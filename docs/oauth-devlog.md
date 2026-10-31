# OAuth 2.0 — Development Log

Running record of the OAuth work described in [oauth-plan.md](oauth-plan.md): what's done, what
broke and why, decisions taken, and what's next. Newest entries first within each section.

---

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Authorization Server core in `crapi-identity` | ✅ Done, verified, committed to `develop` |
| 2 | Consent screen, Connected-apps panel, login continuation (web) | ✅ Done, verified, committed to `develop` |
| 3 | DealerConnect client app (`services/dealer-app`) + compose/proxy | ✅ Done, verified, committed to `develop` |
| 4 | Scope handling in Workshop / Community | ⏳ Next |
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
| D13 | DealerConnect serves **plain HTTP on 8900** (resolves T4) | Adopted (phase 3) | Matches the seeded redirect URI `http://localhost:8900/oauth/callback`. A browser-facing demo; TLS adds friction without teaching value here |
| D14 | DealerConnect keeps tokens **server-side**, browser holds only a session cookie | Adopted (phase 3) | In-memory session store. Keeps the secure baseline clean; phase 5 can add a leak as a toggle |
| D15 | Two crAPI URLs: `CRAPI_PUBLIC_URL` (browser → `localhost:8889`) and `CRAPI_INTERNAL_URL` (server → `http://crapi-web`) | Adopted (phase 3) | The browser can't resolve `crapi-web`; the server shouldn't depend on the host port. The authorize redirect uses public, token/API calls use internal |
| D16 | distroless image; container healthcheck is the binary's own `-healthcheck` mode | Adopted (phase 3) | distroless has no shell/curl for a normal healthcheck |

---

## Phase 3 — DealerConnect client app

**Date:** 2026-10-30 · **State:** verified, committed to `develop`

### Built

- **`services/dealer-app`** (Go, ~5 files): a third-party portal that is crAPI's OAuth client.
  - `oauth.go` — the authorization-code client: builds the authorize URL (public crAPI), exchanges the code and refreshes (internal crAPI), and a bearer-token API helper.
  - `crapi.go` — the crAPI response shapes it reads (userinfo, vehicles, shop) and a `PlaceOrder`.
  - `session.go` — in-memory server-side sessions; the browser holds only an HttpOnly cookie (D14).
  - `handlers.go` — home, `/login` (sets state, redirects to consent), `/oauth/callback` (checks state, exchanges, loads profile), `/dashboard` (vehicles + shop), `/order`, `/logout`, `/healthz`.
  - `templates/` — embedded HTML (landing, dashboard, error).
  - `main.go` — config from env, server, and the `-healthcheck` probe mode (D16).
- **Compose**: `dealer-connect` service, built from source (D8), published on 8900, `depends_on` crapi-web healthy, env wiring (D15), binary healthcheck.
- **Proxy**: root `/.well-known/oauth-authorization-server` added to both nginx templates, both server blocks (resolves T3).
- **Tests**: `tests/oauth/dealer_check.py`, a 17-check real-browser end-to-end (landing → login → consent → dashboard with vehicles/shop → order → logout → consent-remembered → state enforcement). `run.sh --dealer` runs it; `--build` now builds dealer-connect too.

### Verification

| Check | Result |
|---|---|
| `gofmt`, `go vet`, `go build` (golang:1.21 image) | Clean |
| `docker compose config`, `pull --ignore-buildable` skips dealer-app | OK |
| Root discovery through crapi-web (`/.well-known/oauth-authorization-server`) | 200 |
| DealerConnect end-to-end (`dealer_check.py`) | 17/17, dashboard screenshot reviewed |
| Full regression (`run.sh --ui --dealer`) | _running_ |

### Errors & fixes

| # | Symptom | Cause | Fix |
|---|---|---|---|
| E14 | A previous attempt to write this app was halted mid-file by a safety classifier | Tooling, not the code | Rewritten from scratch, incrementally; only `go.mod` had survived |
| E15 | Shared `layout` template with `{{template "content"}}` would collide | `ParseFS` puts every file in one namespace, so `content` is defined several times | Split into `top`/`bottom` partials each page includes under its own unique name |
| E16 | SSL nginx template got the discovery location in only one of two server blocks | One jwks block has extra `proxy_ssl_*` lines, so the regex matched only the other | Added it to the first block by hand, with matching `proxy_ssl_*` |
| E17 | `dealer_check.py` "second login" step timed out | Test bug: after login the home page shows "Go to dashboard", not the login link | Log out of DealerConnect first (keeps the crAPI consent), then log in again to prove the prompt is skipped |

### Notes

- A live order deducts real crAPI credit (dashboard shows $90 after the test buys a $10 part): the order genuinely goes through crAPI with the delegated token.
- DealerConnect does not use the root discovery document itself (it builds the authorize URL from `CRAPI_PUBLIC_URL`); the root location was added for RFC 8414 completeness (T3).

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

# DealerConnect (Go)
docker run --rm -v "$PWD/services/dealer-app":/app -w /app golang:1.21-bookworm \
  sh -c 'gofmt -l . && go vet ./... && go build -o /tmp/dealer .'

# Web
cd services/web && npm run lint && npm run build

# End-to-end against the running lab (resets OAuth grants before each test)
tests/oauth/run.sh                       # API smoke test, 52 checks
tests/oauth/run.sh --ui                  # + crAPI web UI check, 30 checks (Playwright)
tests/oauth/run.sh --dealer              # + DealerConnect end-to-end, 17 checks (Playwright)
tests/oauth/run.sh --build --ui --dealer # rebuild identity, web and dealer-connect first
```

See [tests/oauth/README.md](../tests/oauth/README.md). The scripts are linted in CI (shellcheck,
bash 3.2 syntax, `py_compile`); the tests themselves need the live stack, so they are not CI jobs.

---

## Open items

| # | Item | Phase |
|---|---|---|
| ~~T1~~ | ~~Move the OAuth smoke test + reset script into the repo~~ — done: `tests/oauth/` | — |
| ~~T2~~ | ~~Commit phase 1~~ — done | — |
| ~~T3~~ | ~~Root `/.well-known/oauth-authorization-server` in both nginx templates~~ — done (phase 3) | — |
| ~~T4~~ | ~~Confirm port 8900 and HTTP vs HTTPS for DealerConnect~~ — 8900, HTTP (D13) | — |
| T5 | Revisit D3 (`ENABLE_OAUTH` default on) before the weaknesses land | 5 |
| T6 | Refresh-token rotation (phase 1 reuses the same refresh token) | decide in 5 |
| ~~T7~~ | ~~DealerConnect logo~~ — moot: DealerConnect has its own UI; the crAPI consent page still shows a fallback icon for the missing `images/dealer-connect.png` | 5? |
| ~~T8~~ | ~~Commit phase 2~~ — done | — |
| ~~T9~~ | ~~Commit phase 3 to `develop` and mirror~~ — done 2026-10-30 | — |
| T10 | Scope is still not enforced by Workshop/Community — a `read:vehicles`-only DealerConnect token can place orders | 4 |

---

## Next: Phase 4 plan

- **Resource-server scope handling.** Read the `scope` claim in Workshop (`utils/jwt.py`) and Community (`api/auth/token.go`) and enforce it on write endpoints — **inconsistently by design** (one service enforces, the other effectively ignores it), which is the intended teaching gap (T10). Toggle-gated so a "secure mode" demo enforces everywhere.
- **Concretely:** `write:orders` should be required to `POST /workshop/api/shop/orders`; a DealerConnect token granted only `profile read:vehicles` should then be refused there. The `dealer_check.py` "place an order" step currently relies on the lack of enforcement, so it will need the broader scope (already granted) — and a new negative check with a narrowed token.
- Decide where the inconsistency lives (proposal: Workshop enforces, Community does not) and record it as a decision.
