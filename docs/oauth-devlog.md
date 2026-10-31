# OAuth 2.0 — Development Log

Running record of the OAuth work described in [oauth-plan.md](oauth-plan.md): what's done, what
broke and why, decisions taken, and what's next. Newest entries first within each section.

---

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Authorization Server core in `crapi-identity` | ✅ Done, verified, committed to `develop` |
| 2 | Consent screen, Connected-apps panel, login continuation (web) | ⏳ Next |
| 3 | DealerConnect client app (`services/dealer-app`) + compose/proxy | ⏳ |
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

# End-to-end against the running lab (43 checks; resets OAuth grants first)
tests/oauth/run.sh            # or --build to rebuild crapi-identity from source first
```

See [tests/oauth/README.md](../tests/oauth/README.md). `run.sh` and `smoke_test.py` are linted in
CI (shellcheck, bash 3.2 syntax, `py_compile`); the test itself needs the live stack, so it is not
a CI job.

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

---

## Next: Phase 2 plan

- **Consent screen** — route `/oauth/authorize` in the React app: read the query params, call `GET /identity/api/oauth/authorize` with the user's token, render client name, logo and scopes, then `POST` approve/deny and `window.location = redirect_url`. If `consent_required` is false, approve automatically.
- **Login continuation** — an unauthenticated user landing on `/oauth/authorize` goes to `/login` and returns afterwards with the original query intact.
- **Connected apps** — a panel on the profile page backed by `GET /consents`, with a Disconnect button. **Needs a new user-facing revoke-consent endpoint**, since `/revoke` is client-authenticated (D7).
- Follow the existing pattern: actions → saga → reducer → container → component; lint with `npm run lint`.
