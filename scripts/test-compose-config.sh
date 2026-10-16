#!/usr/bin/env bash
#
# test-compose-config.sh — `docker compose config` checks for deploy/docker-compose.yml.
#
# Proves the stack file still parses when the variables that must not be hard
# requirements are missing, still refuses when a real secret is missing, and publishes
# Traefik on the interface TROVE_BIND_ADDR names. Needs Docker with the compose plugin
# (no daemon connection, no images pulled). Run from anywhere:
#   scripts/test-compose-config.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/deploy/docker-compose.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose v2 is required for this test" >&2
  exit 2
fi

FAILURES=0
CASES=0
pass() { CASES=$((CASES + 1)); printf '  ok    %s\n' "$1"; }
fail() { CASES=$((CASES + 1)); FAILURES=$((FAILURES + 1)); printf '  FAIL  %s\n' "$1"; [ $# -lt 2 ] || printf '        %s\n' "$2"; }

# Every variable compose requires, with throwaway values.
base_env() {
  cat <<'ENV'
TROVE_DOMAIN=trove.test
TROVE_ADMIN_EMAIL=me@trove.test
POSTGRES_PASSWORD=pg-secret
TROVE_JWT_SECRET=jwt-secret-jwt-secret-jwt-secret-1234
TROVE_ENCRYPTION_KEY=abababababababababababababababababababababababababababababababab
ENV
}

# compose_config ENVFILE [config args...] : sets OUT (stdout+stderr) and RC
compose_config() {
  local envfile="$1"; shift
  # env -i: nothing from the caller's shell can satisfy or break a variable.
  OUT=$(env -i PATH="$PATH" HOME="${HOME:-/tmp}" docker compose -f "$COMPOSE_FILE" --env-file "$envfile" config "$@" 2>&1)
  RC=$?
}

echo "required variables"
base_env > "${WORK}/ok.env"
compose_config "${WORK}/ok.env" -q
[ "$RC" -eq 0 ] && pass "complete env parses" || fail "complete env parses" "$OUT"

echo "TROVE_ADMIN_PASSWORD empty"
{ base_env; echo "TROVE_ADMIN_PASSWORD="; } > "${WORK}/admin.env"
compose_config "${WORK}/admin.env" -q
[ "$RC" -eq 0 ] && pass "config -q passes with an empty TROVE_ADMIN_PASSWORD" || fail "config -q passes with an empty TROVE_ADMIN_PASSWORD" "$OUT"

echo "GRAFANA_ADMIN_PASSWORD unset, monitoring profile off"
{ base_env; echo "GRAFANA_ADMIN_PASSWORD="; echo "COMPOSE_PROFILES="; } > "${WORK}/grafana.env"
compose_config "${WORK}/grafana.env" -q
[ "$RC" -eq 0 ] && pass "config -q passes with an empty GRAFANA_ADMIN_PASSWORD" || fail "config -q passes with an empty GRAFANA_ADMIN_PASSWORD" "$OUT"

echo "secrets that stay required"
for k in POSTGRES_PASSWORD TROVE_JWT_SECRET TROVE_ENCRYPTION_KEY TROVE_DOMAIN TROVE_ADMIN_EMAIL; do
  base_env | grep -v "^${k}=" > "${WORK}/missing.env"
  compose_config "${WORK}/missing.env" -q
  if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -q "$k"; then pass "missing $k is refused, naming it"; else fail "missing $k is refused, naming it" "rc=$RC $OUT"; fi
done

echo "TROVE_BIND_ADDR"
compose_config "${WORK}/ok.env"
# No host IP at all: Docker then publishes on every interface, IPv4 and IPv6, like a bare "80:80".
# An explicit 0.0.0.0 would publish IPv4 only.
printf '%s\n' "$OUT" | grep -q 'host_ip' && fail "unset: no host_ip (IPv4 and IPv6)" "$OUT" || pass "unset: no host_ip (IPv4 and IPv6)"
[ "$(printf '%s\n' "$OUT" | grep -c 'published: "\(80\|443\)"')" -eq 2 ] && pass "unset: traefik still publishes 80 and 443" || fail "unset: traefik still publishes 80 and 443" "$OUT"
{ base_env; echo "TROVE_BIND_ADDR=127.0.0.1"; } > "${WORK}/bind.env"
compose_config "${WORK}/bind.env"
n=$(printf '%s\n' "$OUT" | grep -c 'host_ip: 127.0.0.1')
# traefik: 80 and 443; monitoring services are profile-gated and absent here.
[ "$n" -eq 2 ] && pass "TROVE_BIND_ADDR=127.0.0.1: both traefik ports on loopback" || fail "TROVE_BIND_ADDR=127.0.0.1: both traefik ports on loopback" "host_ip 127.0.0.1 appeared $n times"
printf '%s\n' "$OUT" | grep -q 'host_ip: 0.0.0.0' && fail "TROVE_BIND_ADDR=127.0.0.1: nothing left on 0.0.0.0" || pass "TROVE_BIND_ADDR=127.0.0.1: nothing left on 0.0.0.0"

echo "web service port"
compose_config "${WORK}/ok.env"
printf '%s\n' "$OUT" | grep -q 'trove-web.loadbalancer.server.port: "8080"' && pass "Traefik sends the SPA router to port 8080 (unprivileged nginx)" || fail "Traefik sends the SPA router to port 8080 (unprivileged nginx)" "$OUT"

echo "pass-throughs for trove-core settings"
printf '%s\n' "$OUT" | grep -q 'TROVE_HOUSEHOLD_NAME: Household' && pass "TROVE_HOUSEHOLD_NAME defaults to application.yml's value" || fail "TROVE_HOUSEHOLD_NAME defaults to application.yml's value"
printf '%s\n' "$OUT" | grep -q 'TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON: 0 17 9 \* \* \*' && pass "scheduled-sync cron defaults to application.yml's value" || fail "scheduled-sync cron defaults to application.yml's value"
{ base_env; echo "TROVE_HOUSEHOLD_NAME=The Smiths"; echo "TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON='0 5 6 * * *'"; } > "${WORK}/pass.env"
compose_config "${WORK}/pass.env"
printf '%s\n' "$OUT" | grep -q 'TROVE_HOUSEHOLD_NAME: The Smiths' && pass "TROVE_HOUSEHOLD_NAME from .env reaches trove-core" || fail "TROVE_HOUSEHOLD_NAME from .env reaches trove-core" "$OUT"
printf '%s\n' "$OUT" | grep -q 'TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON: 0 5 6 \* \* \*' && pass "scheduled-sync cron from .env reaches trove-core" || fail "scheduled-sync cron from .env reaches trove-core"

echo
echo "${CASES} checks, ${FAILURES} failed"
[ "$FAILURES" -eq 0 ]
