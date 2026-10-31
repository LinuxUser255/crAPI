# OAuth smoke test

An end-to-end check of crAPI's OAuth 2.0 authorization server (see `docs/oauth-plan.md` and
`docs/oauth-devlog.md`), run against the live lab through crapi-web.

```bash
tests/oauth/run.sh            # test the running stack
tests/oauth/run.sh --build    # rebuild crapi-identity from source first
```

`run.sh` waits for `crapi-identity` to be healthy, **deletes all OAuth consents, tokens and
authorization codes** in the lab database (registered clients are kept), then runs
`smoke_test.py`. It exits 0 only when every check passes.

What it checks: discovery is public; `/authorize` refuses requests without a user token, refuses
lookalike redirect URIs and unregistered scopes, and handles approve/deny with `state`; `/token`
authenticates the client, binds the code to its `redirect_uri`, issues a JWT with `scope` /
`client_id` / `aud`, rejects code replay and accepts HTTP Basic for refresh; `/userinfo` and
`/consents` work; consent is remembered; `/revoke` needs client credentials and revoked refresh
tokens are rejected.

Needs Python 3 (standard library only). Point it elsewhere with `CRAPI_URL`, e.g.
`CRAPI_URL=http://localhost:30080`. Plain HTTP only: the lab's HTTPS certificate is self-signed
and is rejected.

These checks describe the **secure** behaviour. Once the phase-5 teaching weaknesses exist, run it
with their `ENABLE_OAUTH_*` toggles off, which is the default.
