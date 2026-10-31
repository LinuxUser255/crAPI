# Changelog

Releases of this crAPI lab fork. Each `## [vX.Y.Z]` section becomes the notes of the GitLab release for that tag (see `.gitlab-ci.yml`). Add new versions at the top.

## [Unreleased]

### OAuth 2.0 (phase 3 of 6)

- **DealerConnect** (`services/dealer-app`): a standalone third-party car-dealership portal that
  signs in with crAPI over OAuth. "Log in with crAPI" runs the authorization-code flow, then the app
  shows the user's vehicles and the parts shop and can place an order — all with a delegated token,
  never the user's password. Published at `http://localhost:8900`.
- crAPI's root `/.well-known/oauth-authorization-server` now resolves (proxied to identity).
- `tests/oauth/run.sh --dealer` adds a real-browser end-to-end check of the whole flow.

### OAuth 2.0 (phase 2 of 6)

- **Consent page** at `/oauth-authorize`: shows which app is asking, who is signed in, what each
  requested scope allows and where the user will be sent, with Allow and Deny. Skipped when the
  user already authorized those scopes. A request with an unknown app or an unregistered
  `redirect_uri` shows an error and is never redirected.
- **Connected Apps** on the profile page lists authorized apps and their scopes, with Disconnect.
  New endpoint `DELETE /identity/api/oauth/consents/{clientId}` removes the consent and revokes the
  app's tokens.
- **Login continuation**: a protected page opened while logged out, such as an OAuth link, is
  reopened after login instead of the dashboard.
- Discovery's `authorization_endpoint` now points at the consent page.
- `tests/oauth/run.sh --ui` adds a headless-browser check of these pages (needs Playwright).

### OAuth 2.0 (phase 1 of 6)

- `crapi-identity` is now an OAuth 2.0 Authorization Server, under `/identity/api/oauth`:
  `authorize` (GET describes a pending request for the consent screen, POST records the decision and
  issues a code), `token` (`authorization_code` and `refresh_token` grants), `userinfo`, `revoke`,
  `consents`, and RFC 8414 discovery metadata. Access tokens are RS256 JWTs signed by the existing
  key and carry `scope`, `client_id` and `aud`, so the other services accept them unchanged.
- The **DealerConnect** client is seeded at startup; there is no self-service registration. The
  client app itself arrives in phase 3.
- Controlled by `ENABLE_OAUTH` in `deploy/docker/.env` (default on). This phase implements the flow
  the way the RFCs require — the teaching weaknesses get their own toggles in phase 5. See
  `docs/oauth-plan.md`.

### Forum

- Posts can be liked and disliked, from the forum list and the post page. Each is a toggle per user: reacting again removes it, and liking a disliked post (or the reverse) switches it. New Community endpoints: `POST /community/api/v2/community/posts/{id}/like` and `/dislike`; posts now carry `likes`, `dislikes` and `user_reaction`. The chatbot's OpenAPI spec knows the new routes.
- Forum posts and comments show the author's current profile picture. Before, the picture was copied into the post when it was written, so one uploaded afterwards never appeared.

### Chatbot

- The chat window can be resized by dragging its top edge, left edge or top-left corner. The size is remembered in the browser.

### Docs

- Header comments on the main files of every backend service describing what each does and what it connects to, and `docs/crAPI_architecture_detailed.md`, a drill-down of the architecture overview that separates frontend from backend.

## [v1.0.0] - 2026-10-30

First release of the fork as a ready-to-run lab, based on [OWASP/crAPI](https://github.com/OWASP/crAPI) `develop`.

### One-command setup

- `./quickstart.sh` deploys the whole lab on macOS or Linux. It detects the platform and gets a Docker engine running first: Colima, Docker Desktop or OrbStack on macOS (offering a Homebrew install of Colima if none exists), and the system daemon on Linux. Pass `--yes` for runs without a terminal.
- `docker-compose.yml` is tracked again and contains no secrets, so a fresh clone deploys as is.
- `crapi-dev.sh` offers the same operations as an interactive menu.

### Chatbot works with any LLM provider

- The chatbot works with OpenAI, Anthropic, and any OpenAI-compatible API (xAI, Gemini, Groq, Mistral, DeepSeek, OpenRouter, Ollama), chosen with `LLM_PROVIDER`, `LLM_BASE_URL` and `DEFAULT_MODEL`.
- All LLM settings and the key live in `deploy/docker/llm.env.local`, which is gitignored and optional. Copy `deploy/docker/llm.env.example` to create it.
- Chat-history embeddings can run locally (`all-MiniLM-L6-v2`, built into the image) for providers without an embeddings API.

### Fixes over upstream

- The chatbot is built from local source with a 1 GiB memory limit, and its Postgres driver is pinned to `psycopg2`.
- The web UI is published on 8889 instead of 8888, and host port 8080 is left free for Burp Suite.
- A fallback nginx config lets the web UI start when the chatbot can't run.

### Docs and CI

- The README has a linked table of contents and step-by-step chatbot setup. A flowchart of how the API key is handled is in `docs/chatbot-api-key-flow.md`.
- The GitLab CI pipeline runs shellcheck, a bash 3.2 syntax check, compose validation, a Python syntax check, secret detection, and a chatbot image build and smoke test. A `vX.Y.Z` tag on `main` creates a release.
