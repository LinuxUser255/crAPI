---
name: crapi-dev-loop
description: >-
  Mandatory procedure for any hands-on change in this crAPI fork (a deliberately vulnerable OWASP
  API lab: Java/Spring identity, Go community/dealer-app/gateway, Python/Django workshop/chatbot,
  React web). Load it BEFORE editing, building, testing, rebuilding, running, or shipping any
  service here — without it, changes silently don't run (compose pulls the upstream image instead
  of your source), the host JDK crashes the Java formatter, and the OAuth test harness overrides the
  mode flag. Covers per-language build/test (including the JDK-17 Docker image), the "modified
  service needs a build: context" rule, running the OAuth / Playwright / DealerConnect tests,
  switching the OAuth secure/vulnerable mode, adding or confirming an OAuth weakness, continuing a
  numbered OAuth phase (docs/oauth-plan.md, log docs/oauth-devlog.md), and committing to develop +
  mirroring to GitHub. Terse asks count: "test it", "rebuild identity", "commit and push", "continue
  phase N". Skip it for pure explanations, writing, or one-off lookups with no code change, and for
  other repos.
---

# crAPI fork dev loop

This is an intentionally vulnerable OWASP training app — **vulnerabilities are features**. Never
"harden" code unless explicitly asked; removing a flaw breaks a documented challenge. Work on
`develop`; `main` is protected (merge requests only). See `CLAUDE.md` and `AGENTS.md` for the
stack-level facts this skill builds on.

## The method (the part that transfers)

The durable idea, independent of which service or project you're in:

1. **Change** the source.
2. **Verify each language with its own toolchain, in the toolchain's own Docker image** when the
   host version is wrong or breaks the formatter. The CI image is the source of truth for versions.
3. **Make the change actually run** before claiming it works: for a containerized stack that means
   the container must be *built from your source*, not pulled — then rebuilt, recreated, and hit.
4. **Document decisions, errors, and open items as you go**, newest-first, in a running log that is
   the memory across sessions.
5. **Commit to the working branch and mirror** to every remote; never hardcode a rotating value
   (like a commit co-author line) — read it from the session each time.
6. **Evidence over claims.** Don't report a build or test as green without its output; background
   the slow ones so the user can interject, and report when they land.

Everything below is how those principles land in *this* repo. Adapting the skill to a new service
here, or seeding a sibling multi-service Docker project, is mostly swapping the concrete image
names, ports, and service list while keeping the method and the rules (build-context, toolchain
image, document-as-you-go, mirror) intact.

**Trust the method, verify the specifics.** The concrete names below — the service list, the OAuth
mode/toggle variable, the exact test scripts (`tests/oauth/run.sh`, `weaknesses.py`), file paths —
are the convention *at the time of writing* and **drift between branches and phases**. Before you
rely on one, confirm it against the checked-out repo (`git grep`, read the file, `ls`). If the repo
disagrees with this skill, the repo wins; follow its actual convention and, if you can, note the
drift so the skill gets updated. A checkout of `main` can be a phase behind `develop`.

## This repo: the loop for any change

1. **Change** the source.
2. **Verify** per-language (below), in Docker where the toolchain demands it.
3. **Run the change for real** — rebuild the image, recreate the container, hit it.
4. **Document** — update `docs/oauth-devlog.md` (for OAuth work) and `CHANGELOG.md` in the same turn.
5. **Commit & mirror** — `develop`, then push to GitLab and GitHub.

Do each step with evidence. Never report a build/test as passing without the command output in hand;
if something failed, say so with the output. Background the slow builds (they take minutes) so the
user can interject, and report when they finish.

## The build-context rule (read this first)

**Any service whose source this fork modifies must have a `build:` context in
`deploy/docker/docker-compose.yml`, or the change silently won't run** — compose would pull the
upstream image instead. Services with a build context today: `crapi-identity`, `crapi-community`,
`crapi-web`, `crapi-workshop`, `crapi-chatbot`, `dealer-connect`. Before building a service, confirm
it has one; if you just started modifying a service that doesn't, add it:

```yaml
  crapi-<svc>:
    image: crapi/crapi-<svc>:${VERSION:-latest}
    build:
      context: ../../services/<svc>
```

`quickstart.sh` and `crapi-dev.sh` run `docker compose pull --ignore-buildable` then
`docker compose build`, so a plain `docker compose pull` must never be reintroduced — it would
overwrite the locally built images. Keep CLAUDE.md's build-context list current when you add one.

## Verify per language

Run from the repo root. The scratchpad directory (see the environment preamble) holds logs and
temp scripts — keep them out of the repo.

**Java (identity)** — local JDK 19 crashes spotless (google-java-format 1.7), so always use the
Dockerfile's toolchain image. `spotlessApply` reformats; copy the formatted sources back before
building the image:

```bash
S="$HOME/.cache/crapi-oauth-build"; rm -rf "$S"; mkdir -p "$S"
rsync -a --exclude build --exclude .gradle services/identity/ "$S"/
docker run --rm -v "$S":/app -w /app gradle:8.1-jdk17-focal \
  gradle spotlessApply test --no-daemon --console=plain    # or spotlessCheck for a read-only gate
rsync -a "$S"/src/ services/identity/src/                  # copy spotless-formatted sources back
```

Run a single class with `--tests "com.crapi.service.Impl.OAuthServiceImplTest"`. For `@Value` fields
in Mockito unit tests, set them with `ReflectionTestUtils.setField(...)` in `@Before`.

**Go (community)** — vendored; tests pass but `go test` on the host aborts with a dyld `LC_UUID`
error, so run in the build image:

```bash
docker run --rm -v "$PWD/services/community":/app -w /app -e GOFLAGS=-mod=vendor golang:1.21-alpine \
  sh -c 'go build ./... && go vet ./... && go test ./...'
```

`gofmt -l` lists several community files that were already unformatted at HEAD — check your *edited*
files are clean with `gofmt -d <file>`, don't try to reformat the whole package.

**Go (dealer-app, gateway-service)** — not vendored:

```bash
docker run --rm -v "$PWD/services/dealer-app":/app -w /app golang:1.21-bookworm \
  sh -c 'gofmt -l . && go vet ./... && go build -o /tmp/out .'
```

**Python (workshop, chatbot)** — `python3 -m py_compile <files>` for a quick gate (then delete the
`__pycache__`). The full Django suite needs a live DB + Cairo (`docker compose -f
services/docker-database.yml up -d --wait`, then `source venv/bin/activate && source .env &&
IS_TESTING=True python3 manage.py test`) — usually the integration harness below is the better
check. The chatbot's `crapi-openapi-spec.json` is validated by
`services/chatbot/validate_openapi_spec.py` (every response needs a `description` or `$ref`, or
FastMCP crashes the MCP server).

**Web (React)** — `cd services/web && npm run lint && npm run build`. `npm run lint` is prettier;
run `npx prettier --write <files>` on what you changed. `npm run build` with `CI` set treats
warnings as errors — the existing warnings are pre-existing unused imports in `Bot.tsx`,
`profile.tsx`, etc.; don't be alarmed, but make sure you didn't add new ones. There are no unit
tests; the browser checks under `tests/oauth/` (Playwright) cover the UI.

## Run the change for real

Rebuild and recreate only what changed (the full stack takes long):

```bash
cd deploy/docker
docker compose build crapi-<svc>
docker compose up -d --no-build --force-recreate crapi-<svc>
# wait for health before testing
until [ "$(docker inspect -f '{{.State.Health.Status}}' crapi-<svc>)" = healthy ]; do sleep 3; done
```

Ports: web `8889` (HTTP) / `8443` (HTTPS, self-signed → use HTTP for curl/Playwright), identity
`8090`, mailhog `8025`, dealer-connect `8900`. Seeded users incl. `adam007@example.com` /
`adam007!123` and `admin@example.com` / `Admin!123`.

## OAuth work specifically

One flag drives all the OAuth weaknesses: **`OAUTH_MODE`** in `deploy/docker/.env`, read by
identity, workshop and community. `secure` runs OAuth by the book; `vulnerable` opens the
weaknesses (loose `redirect_uri` prefix match, scope not enforced). The lab ships `vulnerable`.

`tests/oauth/run.sh` is the integration harness. It **pins `OAUTH_MODE=secure`** for its baseline
checks (they assert secure behaviour) regardless of `.env`, resets OAuth grants + tops up shop
credit before each sub-test, and restores the `.env` mode at the end:

```bash
tests/oauth/run.sh                        # API smoke (secure baseline)
tests/oauth/run.sh --ui --dealer          # + crAPI web UI + DealerConnect end-to-end (Playwright)
tests/oauth/run.sh --weaknesses           # recreates services in vulnerable then secure, verifies
tests/oauth/run.sh --build --ui --dealer  # rebuild the modified services first
```

New weaknesses hang off the same `OAUTH_MODE` flag (in `OAuthServiceImpl` via
`app.oauth.mode`, in the resource servers via the scope checks), each defaulting to the secure
behaviour, with a `weaknesses.py` check that flips the mode and confirms it opens and closes.

## Document in the same turn

For OAuth work, keep `docs/oauth-devlog.md` current **as you go**, not at the end: the status table,
a numbered **decision (Dn)** for anything non-obvious, an **error (En)** for every bug with
symptom/cause/fix, and **open items (Tn)**. Newest entries first. Update `CHANGELOG.md` under
`[Unreleased]`. This log is the memory across sessions — a `memory/` note (`oauth-devlog`) reminds
future sessions to maintain it.

## Commit & mirror

Pre-commit: `grep -nE 'sk-|xai-' deploy/docker/docker-compose.yml` must be empty (the compose file
is tracked and secret-free; real keys live only in `deploy/docker/llm.env.local`). Mark the devlog
phase/items as committed before committing, so the log doesn't land saying "not yet committed".

```bash
git add -A
git commit -q -F - <<'EOF'
<type>(<scope>): <summary>

<body: what and why; note verification results>

<ATTRIBUTION LINE>
EOF
git push origin develop
git fetch origin && git push github       # mirror; see memory github-push-only-mirror
```

**Attribution line:** copy the exact `Co-Authored-By:` line from the current session's attribution
system-reminder — it names a specific model and **rotates between sessions**, so never hardcode or
copy an older one. PR descriptions end with the `🤖 Generated with ...` line from that same reminder.

Never push `main` directly (protected, MR-only). The `github` remote is a non-forced push-only
mirror of origin's `main`/`develop`/tags; a GitLab-side merge reaches GitHub on the next
`git fetch origin && git push github`. After pushing, confirm both remotes match:

```bash
for b in main develop; do printf '%s gitlab=%s github=%s\n' $b \
  $(git rev-parse --short origin/$b) $(git ls-remote github refs/heads/$b | cut -c1-7); done
```

## Common gotchas

- **Service pulled instead of built** → the change doesn't run. Give it a `build:` context (the rule above).
- **Spotless crashes locally** → use `gradle:8.1-jdk17-focal`, not the host JDK.
- **An order/shop check returns 400 not 403** → insufficient shop credit, not a scope failure; `run.sh`'s reset tops credit to 100.
- **A test hit a service mid-recreate → 502** → wait for health first; `run.sh` waits for identity/web/workshop/community.
- **Stuck login spinner in the browser** → stale tab state from a service that was restarting; a hard refresh clears it (the login saga has no fetch timeout).
- **Editing a file changed on disk since you read it** → re-read first; spotlessApply and prettier rewrite files out from under you.
