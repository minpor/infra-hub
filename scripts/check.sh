#!/usr/bin/env bash
# Uses an isolated temporary PostgreSQL instance; never truncates the development database.
set -euo pipefail
if [[ "${1:-}" != "" && "${1:-}" != "--backend-only" ]]; then
  echo "Usage: scripts/check.sh [--backend-only]" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
PG_BIN="${PG_BIN:-$(pg_config --bindir)}"
CHECK_DIR="$(mktemp -d /tmp/infra-hub-check.XXXXXX)"
export HUB_DATABASE_URL="jdbc:postgresql://127.0.0.1:${HUB_TEST_DB_PORT:-55439}/infra_hub_test"
export HUB_TEST_DATABASE_URL="$HUB_DATABASE_URL"
export HUB_DATABASE_USER=infra_hub
export HUB_DATABASE_PASSWORD=isolated-test-only
cleanup() {
  "$PG_BIN/pg_ctl" -D "$CHECK_DIR/data" -m immediate stop >/dev/null 2>&1 || true
  rm -rf "$CHECK_DIR"
}
trap cleanup EXIT
printf '%s\n' "$HUB_DATABASE_PASSWORD" > "$CHECK_DIR/password"
"$PG_BIN/initdb" -D "$CHECK_DIR/data" -U "$HUB_DATABASE_USER" --pwfile="$CHECK_DIR/password" --auth-host=scram-sha-256 --auth-local=trust > "$CHECK_DIR/init.log"
"$PG_BIN/pg_ctl" -D "$CHECK_DIR/data" -l "$CHECK_DIR/postgres.log" -o "-h 127.0.0.1 -p ${HUB_TEST_DB_PORT:-55439} -k $CHECK_DIR" -w start
"$PG_BIN/createdb" -h "$CHECK_DIR" -p "${HUB_TEST_DB_PORT:-55439}" -U "$HUB_DATABASE_USER" infra_hub_test
./gradlew :backend:test :backend:bootJar --no-daemon
if [[ "${1:-}" == "--backend-only" ]]; then exit 0; fi
npm --prefix frontend run build
python3 scripts/smoke.py
