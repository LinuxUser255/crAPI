#!/usr/bin/env bash
# Run the OAuth end-to-end smoke test against the running lab.
#
#   tests/oauth/run.sh            API smoke test against the running stack
#   tests/oauth/run.sh --ui       also the headless-browser UI check (needs Playwright,
#                                 see ui_check.py)
#   tests/oauth/run.sh --dealer   also the DealerConnect end-to-end check (needs Playwright
#                                 and the dealer-connect container; see dealer_check.py)
#   tests/oauth/run.sh --build    rebuild crapi-identity, crapi-web and dealer-connect first
#
# Before each test it deletes every OAuth consent, token and authorization code
# in the lab database, so the test starts from "never authorized". Registered
# clients are kept. Exits 0 only if every check passed.
#
# Must stay bash 3.2 compatible (macOS /bin/bash).

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../../deploy/docker" && pwd)"

build=0
ui=0
dealer=0
for arg in "$@"; do
    case "$arg" in
        --build) build=1 ;;
        --ui) ui=1 ;;
        --dealer) dealer=1 ;;
        *)
            echo "Unknown option: $arg (use --build, --ui and/or --dealer)" >&2
            exit 2
            ;;
    esac
done

if ! command -v python3 > /dev/null 2>&1; then
    echo "python3 is required" >&2
    exit 1
fi

cd "$COMPOSE_DIR" || exit 1

if [ "$build" -eq 1 ]; then
    echo "Building crapi-identity, crapi-web and dealer-connect from source..."
    build_log="$(mktemp)"
    if ! docker compose build crapi-identity crapi-web dealer-connect > "$build_log" 2>&1; then
        tail -20 "$build_log"
        rm -f "$build_log"
        echo "Build failed" >&2
        exit 1
    fi
    rm -f "$build_log"
    docker compose up -d --force-recreate --no-build crapi-identity crapi-web dealer-connect > /dev/null 2>&1
fi

health_services="crapi-identity crapi-web"
if [ "$dealer" -eq 1 ]; then
    docker compose up -d --no-build dealer-connect > /dev/null 2>&1
    health_services="$health_services dealer-connect"
fi

for service in $health_services; do
    echo "Waiting for $service to be healthy..."
    attempts=0
    until [ "$(docker inspect -f '{{.State.Health.Status}}' "$service" 2> /dev/null)" = "healthy" ]; do
        attempts=$((attempts + 1))
        if [ "$attempts" -ge 60 ]; then
            echo "$service did not become healthy in 3 minutes" >&2
            exit 1
        fi
        sleep 3
    done
done

reset_grants() {
    echo "Resetting OAuth grants..."
    if ! docker compose exec -T postgresdb psql -U admin -d crapi -q -c \
        "DELETE FROM oauth_consent; DELETE FROM oauth_access_token; DELETE FROM oauth_authorization_code;"; then
        echo "Could not reset the OAuth tables (is the stack up, with OAuth enabled?)" >&2
        exit 1
    fi
}

reset_grants
python3 -I "$SCRIPT_DIR/smoke_test.py"
status=$?

if [ "$ui" -eq 1 ]; then
    reset_grants
    echo
    echo "== UI check (headless browser) =="
    python3 -I "$SCRIPT_DIR/ui_check.py" || status=1
fi

if [ "$dealer" -eq 1 ]; then
    reset_grants
    echo
    echo "== DealerConnect end-to-end check (headless browser) =="
    python3 -I "$SCRIPT_DIR/dealer_check.py" || status=1
fi

exit "$status"
