# OAuth 2.0 for crAPI — Implementation Plan

**Status:** in progress — see [oauth-devlog.md](oauth-devlog.md) for progress, errors and decisions. **Direction chosen:** crAPI (Identity) becomes the
OAuth 2.0 **Authorization Server**; a new standalone **DealerConnect** app is the third-party
**client**; Workshop and Community stay as **resource servers**.

This is a lab feature, so — like the rest of crAPI — it ships **safe by default** and the
teaching weaknesses are turned on with `ENABLE_*` toggles. Weaknesses are described here as
*design decisions and learning objectives* mapped to the OWASP API Security Top 10, not as
attack recipes; the step-by-step exploitation goes in `challenges.md` / `challengeSolutions.md`
alongside the existing ones.

---

## 1. Why this shape

Three facts in the current code decide it:

- **Identity is already the sole token issuer** — it owns the RSA keypair and the JWKS
  (`config/JwtProvider.java`), and every other service defers to it. An Authorization Server
  belongs there and reuses that signing machinery.
- **`/identity/api/auth/verify` only answers valid/invalid.** Workshop (`utils/jwt.py`) and
  Community (`api/auth/token.go`) then decode the token *themselves, unverified*, to read `sub`.
  Any new claim we add (`scope`, `client_id`, `aud`) is therefore consumed by resource servers
  that never cryptographically check it — authorization built on an untrusted read.
- **Vuln toggles are an established pattern:** `deploy/docker/.env` → `application.properties`
  → `@Value("${app.enable_*}")` (see `ENABLE_SHELL_INJECTION`, `ENABLE_LOG4J`). OAuth
  weaknesses hang off the same switch.

Making crAPI the AS (rather than a client of some external IdP) means **scopes bite on real
data** — `read:vehicles`, `write:orders`, `mechanic`, `admin` map onto APIs that already exist —
and keeps the OWASP *API* Top 10 framing: the weaknesses live in the API, not in a browser SSO dance.

---

## 2. Components at a glance

| Where | What is added |
|---|---|
| **Identity** (Java/Spring) | `OAuthController`, `OAuthService(+Impl)`, 4 new JPA entities + repositories, new JWT claims, discovery endpoint, seeded clients |
| **New `services/dealer-app`** (Go) | "DealerConnect" third-party portal: *Login with crAPI*, callback, calls crAPI APIs with the delegated token. Own container + host port. |
| **Web** (React) | OAuth consent screen, "Connected apps" panel on the profile page, login continuation back to `/authorize` |
| **Workshop / Community** | read `scope` from the token and enforce it **inconsistently by design** (toggle-gated) |
| **Proxy** | one new `location` (the discovery document) in both nginx templates; `/identity/api/oauth/*` is already covered by the existing `/identity/` block |
| **Deploy** | compose block + host port for dealer-app; new `.env` toggles |
| **Docs / tooling** | `challenges.md` + solutions, Postman requests, chatbot OpenAPI spec entries |

---

## 3. Identity — the Authorization Server

### 3.1 Data model (new, Postgres via JPA, mirrors existing `entity/` + `repository/`)

- **`OAuthClient`** — `clientId`, `clientSecret`, `name`, `redirectUris`, `allowedScopes`,
  `clientType` (confidential | public), `logoUrl`. Seeded; not user-registerable in v1.
- **`AuthorizationCode`** — `code`, `clientId`, `userId`, `redirectUri`, `scope`, `expiresAt`,
  `used`, optional PKCE challenge fields.
- **`OAuthToken`** / **`RefreshToken`** — `token`, `refreshToken`, `clientId`, `userId`,
  `scope`, `expiresAt`.
- **`OAuthConsent`** — `userId`, `clientId`, `scope`, `grantedAt` (drives the "already
  authorized, skip the prompt" path and the Connected-apps panel).

### 3.2 `OAuthController` — base path `/identity/api/oauth`

| Endpoint | Purpose |
|---|---|
| `GET /authorize` | validate `client_id` + `redirect_uri` + `scope`; if consent is needed, redirect to the web consent screen; on approval, issue an authorization code and redirect back |
| `POST /token` | grant exchanges: `authorization_code`, `refresh_token`, and (for machine clients) `client_credentials` → access token |
| `GET /userinfo` | profile for a presented access token (OIDC-style) |
| `POST /revoke` | revoke a token / disconnect an app |
| `GET /.well-known/oauth-authorization-server` | discovery metadata |

`/identity/api/oauth/**` is added to the `permitAll()` set in `WebSecurityConfig` (the flow
runs before a crAPI session JWT exists); `/userinfo` and `/revoke` require a token.

### 3.3 Tokens

Keep **RS256 JWTs** signed by the existing `JwtProvider` keypair (consistent with crAPI today,
and it lets the "unverified claim" lessons reuse the existing verify indirection). Add a
`generateOAuthToken(user, client, scope)` that includes `scope`, `client_id`, and `aud` claims
alongside the current `sub` and `role`.

### 3.4 Seeding

Extend `InitialDataConfig.setup()` with `addOAuthClients()`: seed **DealerConnect** (confidential,
redirect URI pointing at the dealer-app host) and optionally a **public mobile client**. Follows
the same "insert if the table is empty" guard the user/vehicle seeding already uses.

---

## 4. DealerConnect — the third-party client (`services/dealer-app`)

A small Go HTTPS app modeled on `services/gateway-service` (194 lines, self-signed cert, tiny
compose footprint). It is the honest model of an external client and the natural home for the
**client-side** token-handling lessons.

- Serves a minimal portal: a landing page with a **Login with crAPI** button.
- Starts the authorization-code flow against `crapi-web`'s public origin.
- Callback endpoint exchanges the code for a token and calls crAPI APIs (list the user's
  vehicles, book a service) with the **delegated** token — demonstrating real cross-app
  delegated access.
- **Published on its own host port** (a distinct origin — this is what makes the redirect flow
  and the client-side lessons realistic). Candidate port **8900** (8080 = Burp, 8888 = taken,
  8889/8443/30443 = web, 8090 = identity, 8025 = mailhog are all in use).
- Added to `docker-compose.yml` with a `build:` context (like chatbot/community/web, so the
  fork's source is what runs) and a healthcheck.

---

## 5. Web — consent & connected apps

- **Consent screen** (`components/oauth/` + `containers/oauth/` + a saga): "DealerConnect wants
  to: see your vehicles, book services…", Allow / Deny, reads the requested scopes from the
  `/authorize` redirect. Styled like the existing forum/post pages.
- **Connected apps panel** on the profile page: list authorized clients and granted scopes,
  with a Disconnect (revoke) button.
- **Login continuation:** the login page learns to resume an `/authorize` request after sign-in
  (a `redirect`/continuation param), so an unauthenticated user who starts at DealerConnect lands
  back in the flow after logging into crAPI.

---

## 6. Resource servers — scope handling

Add scope reading to Workshop (`utils/jwt.py`) and Community (`api/auth/token.go`) and enforce it
on a few write endpoints — **deliberately inconsistently** (one service enforces, the other
effectively ignores it). This is the intended teaching inconsistency and is toggle-gated so a
"secure mode" demo enforces everywhere.

---

## 7. Teaching weaknesses (design objectives, OWASP-mapped)

Each is a *toggle*; defaults keep the lab safe. Detailed walkthroughs live in the challenge docs.

| Theme (weakness class) | OWASP API | What it teaches |
|---|---|---|
| Loose `redirect_uri` validation | API8 Misconfiguration | why redirect targets must be matched exactly |
| Missing CSRF / PKCE protection on the flow | API2 Broken Auth | why the flow needs `state` and PKCE |
| Scope not enforced at the resource server | API1 BOLA / API5 BFLA | authorization must be checked where the data lives |
| Over-trust of unverified token claims | API2 Broken Auth | resource servers must validate, not just decode |
| Weak client authentication / secret handling | API2 / API8 | confidential-client secrets and their exposure |
| Token/code leakage via the client app | API2 Broken Auth | client-side handling of delegated credentials |
| Consent that does not bind the grant | API1 BOLA | granted scope must constrain the issued token |

Target ~7 new challenges (numbered 19+), matching the density of the current set. The chatbot
gets a new LLM challenge for free once the flow is in its OpenAPI spec (it can be talked into
driving the authorization).

---

## 8. Toggles (`deploy/docker/.env`)

- `ENABLE_OAUTH` — master switch for the feature.
- One `ENABLE_OAUTH_*` flag per weakness theme above (all default to the safe behavior).

Wired through `application.properties` → `@Value` in Identity, and through env vars in
dealer-app / the resource servers, exactly like `ENABLE_SHELL_INJECTION` today.

---

## 9. Build sequence (one reviewable PR to `develop` per phase)

1. **AS core** — entities, repositories, `/authorize` + `/token`, new claims, seeded clients.
   Backend-only, Postman-testable. No weaknesses yet (safe mode).
2. **Consent + web flow** — consent screen, connected-apps panel, login continuation.
3. **DealerConnect** — the new service, its port, compose + proxy wiring, end-to-end delegated call.
4. **Resource-server scope handling** — reading + (inconsistent) enforcement.
5. **Weaknesses + toggles** — each theme behind its `ENABLE_OAUTH_*` flag, with a secure default.
6. **Docs / challenges / Postman / chatbot spec.**

Each phase keeps `main`/`develop` deployable; the lab is fully functional and safe after phase 3,
and becomes a teaching surface in phase 5.

---

## 10. Decisions to confirm before phase 1

1. **Token format** — RS256 JWT (recommended; consistent, reuses verify indirection) vs opaque tokens.
2. **DealerConnect host port** — 8900 proposed.
3. **Default state for a release** — ship with `ENABLE_OAUTH` on and weaknesses off, or keep the
   whole feature behind the master toggle until the challenge docs land.
4. **Client registration** — seeded-only in v1 (recommended) vs a dynamic registration endpoint
   (itself a classic weakness surface) in a later phase.
5. **Scope vocabulary** — confirm the initial set: `read:vehicles`, `write:orders`, `mechanic`, `admin`.
