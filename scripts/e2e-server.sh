#!/usr/bin/env bash
# Start a throwaway Trove core server for the Detox end-to-end suites (CI and local).
#
# Needs a reachable, empty Postgres (TROVE_DB_URL / TROVE_DB_USER / TROVE_DB_PASSWORD,
# default jdbc:postgresql://localhost:5432/trove, trove/trove). Builds the jar if it is
# missing, generates fresh secrets for this run, bootstraps an admin and leaves
# registration OPEN (e2e/onboarding.e2e.ts needs the sign-up link; the invite field it
# also checks renders regardless). Listens on :8080, which the app reaches as localhost
# (iOS simulator) or 10.0.2.2 (Android emulator). Logs to core.log in the repo root.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$root/core/target/trove-core-1.0.0-exec.jar"
[ -f "$jar" ] || (cd "$root" && ./mvnw -B -q -pl core -am -DskipTests clean package)

export TROVE_DB_URL="${TROVE_DB_URL:-jdbc:postgresql://localhost:5432/trove}"
export TROVE_DB_USER="${TROVE_DB_USER:-trove}"
export TROVE_DB_PASSWORD="${TROVE_DB_PASSWORD:-trove}"
export TROVE_JWT_SECRET="$(openssl rand -hex 32)"
export TROVE_ENCRYPTION_KEY="$(openssl rand -hex 32)"
export TROVE_DATA_DIR="$(mktemp -d)"
export TROVE_REGISTRATION_OPEN=true
export TROVE_ADMIN_EMAIL=e2e-admin@example.com
export TROVE_ADMIN_PASSWORD="$(openssl rand -hex 16)"
export SERVER_PORT=8080

nohup java -jar "$jar" > "$root/core.log" 2>&1 &

for _ in $(seq 1 90); do
  if curl -fs http://localhost:8080/api/v1/status > /dev/null; then
    echo "Trove e2e server is up"
    exit 0
  fi
  sleep 2
done
echo "Trove e2e server did not come up; core.log:" >&2
tail -n 100 "$root/core.log" >&2
exit 1
