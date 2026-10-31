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
| 4 | Scope handling in Workshop / Community | ✅ Done, verified, committed to `develop` |
| 5 | Teaching weaknesses behind the `OAUTH_MODE` flag | 🔄 In progress — flag + 2 weaknesses committed; more to come |
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
| D17 | Scope is enforced only on **delegated** tokens (those with a `scope` claim); first-party login tokens pass through | Adopted (phase 4) | Keeps the web app and API keys working; realistic — a first-party session isn't scope-limited |
| D18 | ~~The inconsistency: Workshop enforces, Community does not, via `ENABLE_OAUTH_SCOPE_CHECK`~~ **Superseded by D22.** | Superseded (phase 5) | The per-service split was dropped for the single `OAUTH_MODE` flag; in secure mode both services enforce, in vulnerable mode neither does |
| D19 | `crapi-workshop` gains a `build:` context | Adopted (phase 4) | Its source changed; without it the upstream image runs and the scope check is absent (the D8 trap again) |
| D20 | Weaknesses are built incrementally; the first is loose redirect_uri | Adopted (phase 5) | User chose "redirect_uri first" to review the pattern before the rest |
| D21 | Weaknesses are verified by `tests/oauth/weaknesses.py` (flips the mode, checks vulnerable then secure), not by the default suite | Adopted (phase 5) | Keeps `run.sh` (no flag) testing the secure baseline; `run.sh --weaknesses` drives the flag |
| D22 | **One flag for the whole lab: `OAUTH_MODE` = `secure` (default) \| `vulnerable`.** Every OAuth service reads it; `vulnerable` opens all the OAuth weaknesses at once, `secure` closes them | Adopted (phase 5) | User asked for a single easy vuln/secure switch instead of per-weakness toggles. Replaces `ENABLE_OAUTH_WEAK_REDIRECT_URI` and `ENABLE_OAUTH_SCOPE_CHECK` (and the D18 per-service split) |
| D23 | Lab ships **`OAUTH_MODE=vulnerable` by default** (set in `deploy/docker/.env`) | Adopted (phase 5) | crAPI is vulnerable out of the box; OAuth challenges live on a fresh deploy. `run.sh` pins secure for its baseline tests and restores the .env mode after (E20) |

---

## Phase 5 — Teaching weaknesses (in progress)

**Date:** 2026-10-30 · **State:** redirect_uri weakness done, verified, not yet committed

**One flag for the lab (D22):** `OAUTH_MODE` = `secure` (default) | `vulnerable`, read by identity,
workshop and community. `secure` runs OAuth by the book; `vulnerable` opens all the OAuth
weaknesses at once. This replaced the per-weakness toggles after the user asked for a single
vuln/secure switch, and absorbed phase 4's scope flag (D18 superseded): in secure mode both
resource servers enforce scope, in vulnerable mode neither does.

### Weaknesses wired to the flag so far

- **Loose redirect_uri validation (OWASP API8):** `OAuthServiceImpl.isRegisteredRedirectUri` — secure: exact match; vulnerable: prefix match (`startsWith`), so `<registered>.attacker.example` or `<registered>/../evil` is accepted and the authorization code goes there.
- **Scope not enforced (OWASP API1/API5):** vulnerable mode skips the `write:orders` check in Workshop (`scope_required`) and Community (`SetMiddlewareScope`), so a delegated token is over-privileged.

### Wiring

- Identity `app.oauth.mode=${OAUTH_MODE:secure}`; Workshop `OAUTH_SCOPE_ENFORCED = OAUTH_MODE != vulnerable`; Community `scopeEnforcementEnabled = OAUTH_MODE != vulnerable`. Compose passes `OAUTH_MODE=${OAUTH_MODE:-secure}` to all three; `.env` documents it (one commented line).

### Tests

- Unit: `vulnerableModeAcceptsLookalikeRedirectUris` (OAuth tests pass).
- `tests/oauth/weaknesses.py` (via `run.sh --weaknesses`) recreates the services in `vulnerable` then `secure`, checking the weaknesses open and close; restores `secure`.
- `smoke_test.py` secure baseline updated: Community now also enforces scope in secure mode.

**Verified:** 24 OAuth unit tests; full regression 56 (smoke) + 30 (UI) + 17 (DealerConnect) + 7
(`--weaknesses`), all green. The `--weaknesses` run recreates the services in `vulnerable` then
`secure`, confirming the loose redirect and scope weaknesses open and close.

**Lab default (D23):** the user set `OAUTH_MODE=vulnerable` in `deploy/docker/.env`, so a fresh
deploy ships with the OAuth challenges live — matching crAPI's "vulnerable out of the box" ethos.
`run.sh` pins `OAUTH_MODE=secure` for its baseline tests (which assert secure behaviour) regardless
of `.env`, and restores the `.env` mode at the end — so the test suite passes whatever the default.

**E20:** committing `OAUTH_MODE=vulnerable` as the default made `run.sh`'s secure-baseline tests
fail against a fresh stack. Fixed by pinning secure in `run.sh` for smoke/ui/dealer and restoring
the `.env` mode afterwards.

**Still to do in phase 5:** more weaknesses on the same flag (CSRF/PKCE, unverified-claim trust,
weak client auth, consent binding, DealerConnect leak). Challenge write-ups are phase 6.

---

## Phase 4 — Scope enforcement at the resource servers

**Date:** 2026-10-30 · **State:** verified, committed to `develop`

### Built

- **Workshop** (enforces, default on):
  - `scope_required(scope)` decorator in `utils/jwt.py`, applied below `jwt_auth_required` on `OrderControlView.post`. Reads the token's `scope` claim; a token with no claim (first-party) passes; a delegated token missing `write:orders` gets 403; skipped entirely when `settings.OAUTH_SCOPE_ENFORCED` is off.
  - `OAUTH_SCOPE_ENFORCED` in `settings.py` (default true), `INSUFFICIENT_SCOPE` message.
- **Community** (capability present, default off — the gap):
  - `auth.ExtractScope(r)` reads the `scope` claim unverified; `middlewares.SetMiddlewareScope(next, scope)` enforces it only when `ENABLE_OAUTH_SCOPE_CHECK` is true (default false). Wired onto `new-coupon`.
- **Toggle** (as built in phase 4; **later replaced** by `OAUTH_MODE`, see D22): `ENABLE_OAUTH_SCOPE_CHECK` with per-service defaults encoding the inconsistency.
- **Compose**: `crapi-workshop` gains a `build:` context (D19).
- **Tests**: `smoke_test.py` grows a scope-enforcement section (52 → 56): a narrow `profile read:vehicles` token is refused an order by Workshop (403) but not a coupon by Community; a `write:orders` token and a first-party login token both order fine.

### Verification

| Check | Result |
|---|---|
| Community `go build`/`vet`/`test` (golang:1.21 image) | Clean, tests pass |
| Workshop `py_compile` | Clean |
| `docker compose config`, `pull --ignore-buildable` skips workshop | OK |
| Full regression (`run.sh --ui --dealer`) | _running_ |

### Errors & fixes

| # | Symptom | Cause | Fix |
|---|---|---|---|
| E18 | (Caught before build) Workshop scope check wouldn't run | `crapi-workshop` was pulled, no `build:` context | Added one (D19) |
| E19 | Smoke test: Workshop scope checks returned **502** | Test ran before `crapi-workshop` finished starting after the recreate; `run.sh` only waited on identity and web | `run.sh` now also waits for `crapi-workshop` and `crapi-community` to be healthy |

### Notes

- Only the `scope` claim distinguishes a delegated token from a first-party one; `generateOAuthToken` adds it, `generateJwtToken`/`generateApiKey` do not. That is what makes D17 clean.
- The scope gating `write:orders` on Community's coupon creation is a small semantic stretch (coupons aren't orders), chosen so the same narrow token demonstrates the Workshop/Community inconsistency without adding a new scope to the seeded set.
- DealerConnect requests `write:orders`, so its ordering still works — no regression.

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
# Identity (unit + full suite; local JDK 19 can't run spotless, use the build image)
docker run --rm -v "$PWD/services/identity":/app -w /app gradle:8.1-jdk17-focal \
  gradle spotlessCheck test --no-daemon

# Community (Go)
docker run --rm -v "$PWD/services/community":/app -w /app -e GOFLAGS=-mod=vendor golang:1.21-alpine \
  sh -c 'go build ./... && go vet ./... && go test ./...'

# DealerConnect (Go)
docker run --rm -v "$PWD/services/dealer-app":/app -w /app golang:1.21-bookworm \
  sh -c 'gofmt -l . && go vet ./... && go build -o /tmp/dealer .'

# Web
cd services/web && npm run lint && npm run build

# End-to-end against the running lab (resets OAuth grants before each test)
tests/oauth/run.sh                       # API smoke test, 56 checks (incl. scope enforcement)
tests/oauth/run.sh --ui                  # + crAPI web UI check, 30 checks (Playwright)
tests/oauth/run.sh --dealer              # + DealerConnect end-to-end, 17 checks (Playwright)
tests/oauth/run.sh --weaknesses          # flip each weakness toggle, verify, restore
tests/oauth/run.sh --build --ui --dealer # rebuild the modified services first
```

Scope enforcement specifically: a delegated token with only `profile read:vehicles` is refused an
order by Workshop (403) but is **not** refused a coupon by Community; a `write:orders` token and a
first-party login token both order fine. In `secure` mode (the default) Community enforces too;
`OAUTH_MODE=vulnerable` turns enforcement off in both services. See `weaknesses.py`.

See [tests/oauth/README.md](../tests/oauth/README.md). The scripts are linted in CI (shellcheck,
bash 3.2 syntax, `py_compile`); the tests themselves need the live stack, so they are not CI jobs.

---

## Open items

| # | Item | Phase |
|---|---|---|
| ~~T1~~ | ~~Move the OAuth smoke test into the repo~~ — done: `tests/oauth/` | — |
| ~~T2~~ | ~~Commit phase 1~~ — done | — |
| ~~T3~~ | ~~Root discovery in both nginx templates~~ — done (phase 3) | — |
| ~~T4~~ | ~~Confirm DealerConnect port / scheme~~ — 8900, HTTP (D13) | — |
| T5 | Revisit D3 (`ENABLE_OAUTH` default on) before the weaknesses land | 5 |
| T6 | Refresh-token rotation (phase 1 reuses the same refresh token) | decide in 5 |
| T7 | crAPI consent page shows a fallback icon for the missing `images/dealer-connect.png` | 5? |
| ~~T8~~ | ~~Commit phase 2~~ — done | — |
| ~~T9~~ | ~~Commit phase 3~~ — done | — |
| ~~T10~~ | ~~Scope not enforced anywhere~~ — done (phase 4): Workshop enforces, Community is the gap | — |
| ~~T11~~ | ~~Commit phase 4~~ — done | — |
| T12 | Finish the remaining phase-5 weaknesses (CSRF/PKCE, claim trust, client auth, consent binding, DealerConnect leak) | 5 |
| ~~T13~~ | ~~Commit phase 5 (OAUTH_MODE flag + 2 weaknesses) to `develop` and mirror~~ — done 2026-10-30 | — |

---

## Next: Phase 5 plan

The teaching weaknesses, each behind an `ENABLE_OAUTH_*` toggle (default = the safe behaviour built
in phases 1–4, so nothing regresses until a toggle is flipped). Candidates, kept at the level of
weakness class + OWASP category (step-by-step goes in `challenges.md`):

- Loose `redirect_uri` validation (API8) — a toggle that relaxes the exact-match rule in `OAuthServiceImpl.isRegisteredRedirectUri`.
- Missing CSRF/PKCE protection on the flow (API2).
- Scope enforcement is folded into the `OAUTH_MODE` flag (D22): `vulnerable` disables it, the over-privileged-token lesson.
- Over-trust of unverified token claims (API2) — reuses the existing verify-vs-decode indirection.
- Weak client authentication / secret handling (API2/API8).
- Token/code leakage via the client app (API2) — DealerConnect grows an optional leak behind a toggle.
- Consent that doesn't bind the grant (API1).

Also revisit T5/T6 here (default-on, refresh rotation) and add challenge docs/Postman/chatbot-spec
entries in phase 6.
