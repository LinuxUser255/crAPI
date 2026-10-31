# DealerConnect

A standalone third-party web app for the crAPI OAuth 2.0 lab. DealerConnect is a
pretend car-dealership portal that signs users in with **crAPI as its identity
provider** — it is crAPI's OAuth *client*, not part of crAPI itself.

"Log in with crAPI" runs the authorization-code flow: the browser goes to crAPI's
consent page, and on approval DealerConnect exchanges the code for an access token
and calls crAPI's APIs on the user's behalf — profile, vehicles, the parts shop,
and placing an order. The user's crAPI password never reaches DealerConnect, and
the tokens stay on the server; the browser holds only a session cookie.

Runs at `http://localhost:8900` (container `dealer-connect`, Go, plain HTTP).

## Layout

| File | Role |
|---|---|
| `main.go` | config from env, server, `-healthcheck` probe |
| `oauth.go` | the OAuth client: authorize URL, code exchange, refresh, bearer API calls |
| `crapi.go` | the crAPI response shapes it reads, and `PlaceOrder` |
| `session.go` | in-memory server-side sessions (cookie holds only a random id) |
| `handlers.go` | the pages: home, `/login`, `/oauth/callback`, `/dashboard`, `/order`, `/logout`, `/healthz` |
| `templates/` | embedded HTML |

## Configuration (environment)

| Var | Default | Meaning |
|---|---|---|
| `CRAPI_PUBLIC_URL` | `http://localhost:8889` | crAPI as the **browser** reaches it (authorize redirect) |
| `CRAPI_INTERNAL_URL` | `http://crapi-web` | crAPI as **this server** reaches it (token + API calls) |
| `OAUTH_CLIENT_ID` | `dealer-connect` | the client registered in crAPI |
| `OAUTH_CLIENT_SECRET` | `DealerConnect!Secret123` | the matching secret (lab credential) |
| `OAUTH_REDIRECT_URI` | `http://localhost:8900/oauth/callback` | must match the client's registration |
| `OAUTH_SCOPE` | `profile read:vehicles write:orders` | scopes requested |

See `deploy/docker/docker-compose.yml` for how these are wired, `docs/oauth-plan.md`
for the lab plan, and `tests/oauth/dealer_check.py` for the end-to-end test.
