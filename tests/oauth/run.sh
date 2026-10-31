#!/usr/bin/env bash
# Run the OAuth end-to-end smoke test against the running lab.
#
#   tests/oauth/run.sh            test the crapi-identity that is running now
#   tests/oauth/run.sh --build    rebuild crapi-identity from source first
#
# Before the test it deletes every OAuth consent, token and authorization code
# in the lab database, so the test starts from "never authorized". Registered
# clients are kept. Exits with the smoke test's status (0 = all checks passed).
#
# Must stay bash 3.2 compatible (macOS /bin/bash).

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../../deploy/docker" && pwd)"

if ! command -v python3 > /dev/null 2>&1; then
    echo "python3 is required" >&2
    exit 1
fi

cd "$COMPOSE_DIR" || exit 1

if [ "${1:-}" = "--build" ]; then
    echo "Building crapi-identity from source..."
    build_log="$(mktemp)"
    if ! docker compose build crapi-identity > "$build_log" 2>&1; then
        tail -20 "$build_log"
        rm -f "$build_log"
        echo "Build failed" >&2
        exit 1
    fi
    rm -f "$build_log"
    docker compose up -d --force-recreate --no-build crapi-identity > /dev/null 2>&1
fi

echo "Waiting for crapi-identity to be healthy..."
attempts=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' crapi-identity 2> /dev/null)" = "healthy" ]; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 60 ]; then
        echo "crapi-identity did not become healthy in 3 minutes" >&2
        exit 1
    fi
    sleep 3
done

echo "Resetting OAuth grants..."
if ! docker compose exec -T postgresdb psql -U admin -d crapi -q -c \
    "DELETE FROM oauth_consent; DELETE FROM oauth_access_token; DELETE FROM oauth_authorization_code;"; then
    echo "Could not reset the OAuth tables (is the stack up, with OAuth enabled?)" >&2
    exit 1
fi

python3 -I "$SCRIPT_DIR/smoke_test.py"
