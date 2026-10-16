#!/usr/bin/env bash
#
# test-trove-cli.sh — shell tests for the parts of deploy/trove that decide whether a
# first install works: `bootstrap` (fresh file, hand-copied .env.example, real secrets
# untouched), the preflight checks of `up` that replaced compose's hard requirements, and
# the TLS-mode-derived TROVE_BIND_ADDR.
#
# Needs bash, openssl and awk. Does NOT need Docker: the script under test is copied
# into a scratch directory, and a fake `docker` on PATH records what the CLI passes it.
# Run from anywhere:  scripts/test-trove-cli.sh
#
# Exits 0 when every case passes, 1 otherwise (each failure is printed).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
CASES=0
pass() { CASES=$((CASES + 1)); printf '  ok    %s\n' "$1"; }
fail() { CASES=$((CASES + 1)); FAILURES=$((FAILURES + 1)); printf '  FAIL  %s\n' "$1"; [ $# -lt 2 ] || printf '        %s\n' "$2"; }

# check "description" <command...> : passes when the command exits 0
check() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi; }

# A fresh install tree: deploy/trove + deploy/.env.example, plus a fake docker on PATH.
new_install() {
  local dir
  dir=$(mktemp -d "${WORK}/install.XXXXXX")
  mkdir -p "${dir}/deploy" "${dir}/bin"
  cp "${REPO_ROOT}/deploy/trove" "${REPO_ROOT}/deploy/.env.example" "${dir}/deploy/"
  cat > "${dir}/bin/docker" <<'DOCKER'
#!/bin/sh
# Fake docker: succeeds at everything, reports the bind address the CLI exported, and
# pretends the postgres volume is missing when FAKE_NO_VOLUME=1.
if [ "$1" = "volume" ] && [ "${FAKE_NO_VOLUME:-0}" = "1" ]; then exit 1; fi
echo "FAKE_DOCKER bind=${TROVE_BIND_ADDR-unset} args=$*"
exit 0
DOCKER
  chmod +x "${dir}/bin/docker"
  printf '%s' "$dir"
}

# run_trove DIR ARGS... : runs the copied CLI with no terminal on stdin; output in $OUT, status in $RC
run_trove() {
  local dir="$1"; shift
  OUT=$(cd "$dir/deploy" && PATH="${dir}/bin:${PATH}" NO_COLOR=1 ./trove "$@" </dev/null 2>&1)
  RC=$?
}

env_get() { grep -E "^$2=" "$1/deploy/.env" | tail -n 1 | cut -d= -f2-; }
is_set() { [ -n "$(env_get "$1" "$2")" ]; }

echo "bootstrap: no .env yet"
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test TROVE_ADMIN_COUNTRY=us \
  run_trove "$D" bootstrap
[ "$RC" -eq 0 ] && pass "exits 0" || fail "exits 0" "$OUT"
check ".env created with mode 0600" test "$(ls -l "$D/deploy/.env" | cut -c1-10)" = "-rw-------"
[ "$(env_get "$D" TROVE_DOMAIN)" = "trove.test" ] && pass "domain from environment" || fail "domain from environment"
[ "$(env_get "$D" TROVE_ADMIN_COUNTRY)" = "US" ] && pass "country upper-cased" || fail "country upper-cased"
for k in POSTGRES_PASSWORD TROVE_JWT_SECRET TROVE_ENCRYPTION_KEY TROVE_ADMIN_PASSWORD TROVE_BACKUP_PASSPHRASE GRAFANA_ADMIN_PASSWORD; do
  is_set "$D" "$k" && pass "$k generated" || fail "$k generated"
done
[ "$(env_get "$D" TROVE_ENCRYPTION_KEY | tr -d '\n' | wc -c | tr -d ' ')" = "64" ] && pass "encryption key is 64 hex chars" || fail "encryption key is 64 hex chars"
case "$OUT" in *"$(env_get "$D" TROVE_ADMIN_PASSWORD)"*) pass "admin password printed" ;; *) fail "admin password printed" ;; esac
check "bootstrap marker written" test -f "$D/deploy/.bootstrap-done"

echo "bootstrap: .env.example copied by hand (the old quick start)"
D=$(new_install)
cp "$D/deploy/.env.example" "$D/deploy/.env"
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
[ "$RC" -eq 0 ] && pass "exits 0" || fail "exits 0" "$OUT"
case "$OUT" in *"nothing to do"*) fail "does not report 'nothing to do'" ;; *) pass "does not report 'nothing to do'" ;; esac
for k in POSTGRES_PASSWORD TROVE_JWT_SECRET TROVE_ENCRYPTION_KEY TROVE_ADMIN_PASSWORD TROVE_BACKUP_PASSPHRASE GRAFANA_ADMIN_PASSWORD; do
  is_set "$D" "$k" && pass "$k filled in" || fail "$k filled in"
done
[ "$(env_get "$D" TROVE_DOMAIN)" = "trove.test" ] && pass "placeholder domain replaced" || fail "placeholder domain replaced"
[ "$(env_get "$D" TROVE_TLS_MODE)" = "acme-http" ] && pass "other example values kept" || fail "other example values kept"
run_trove "$D" up
case "$OUT" in *"secrets generated (missing"*) fail "up no longer reports missing secrets" "$OUT" ;; *) pass "up no longer reports missing secrets" ;; esac

echo "bootstrap: real secrets are never overwritten"
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
cp "$D/deploy/.env" "${WORK}/before.env"
run_trove "$D" bootstrap
[ "$RC" -eq 0 ] && pass "second run exits 0" || fail "second run exits 0" "$OUT"
check ".env byte-identical after second run" cmp -s "${WORK}/before.env" "$D/deploy/.env"
case "$OUT" in *"nothing to do"*) pass "second run says nothing to do" ;; *) fail "second run says nothing to do" "$OUT" ;; esac

echo "bootstrap: partly filled .env keeps the real values and fills the rest"
D=$(new_install)
cp "$D/deploy/.env.example" "$D/deploy/.env"
KEY=$(printf 'ab%.0s' $(seq 1 32))
{ printf 'POSTGRES_PASSWORD=my-real-db-password\n'; printf 'TROVE_ENCRYPTION_KEY=%s\n' "$KEY"; } >> "$D/deploy/.env"
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
[ "$(env_get "$D" POSTGRES_PASSWORD)" = "my-real-db-password" ] && pass "real database password kept" || fail "real database password kept"
[ "$(env_get "$D" TROVE_ENCRYPTION_KEY)" = "$KEY" ] && pass "real encryption key kept" || fail "real encryption key kept"
is_set "$D" TROVE_JWT_SECRET && pass "empty JWT secret filled" || fail "empty JWT secret filled"
case "$OUT" in *"TROVE_ENCRYPTION_KEY out of deploy/.env"*) fail "does not tell you to copy a key it did not generate" ;; *) pass "does not tell you to copy a key it did not generate" ;; esac

echo "bootstrap: an all-zero or change-me value counts as unset"
D=$(new_install)
cp "$D/deploy/.env.example" "$D/deploy/.env"
{ printf 'TROVE_ENCRYPTION_KEY=%s\n' "$(printf '0%.0s' $(seq 1 64))"; printf 'TROVE_JWT_SECRET=change-me-please\n'; } >> "$D/deploy/.env"
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
case "$(env_get "$D" TROVE_ENCRYPTION_KEY)" in *[!0]*) pass "all-zero key replaced" ;; *) fail "all-zero key replaced" ;; esac
case "$(env_get "$D" TROVE_JWT_SECRET)" in change-me*) fail "change-me JWT secret replaced" ;; *) pass "change-me JWT secret replaced" ;; esac

echo "bootstrap: after a first run the admin password is not regenerated"
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
grep -v '^TROVE_ADMIN_PASSWORD=' "$D/deploy/.env" > "$D/deploy/.env.tmp" && mv "$D/deploy/.env.tmp" "$D/deploy/.env"
run_trove "$D" bootstrap
is_set "$D" TROVE_ADMIN_PASSWORD && fail "deleted TROVE_ADMIN_PASSWORD stays deleted" || pass "deleted TROVE_ADMIN_PASSWORD stays deleted"
case "$OUT" in *"nothing to do"*) pass "reports nothing to do" ;; *) fail "reports nothing to do" "$OUT" ;; esac

echo "up: TROVE_ADMIN_PASSWORD is a first-install check, not a permanent requirement"
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
grep -v '^TROVE_ADMIN_PASSWORD=' "$D/deploy/.env" > "$D/deploy/.env.tmp" && mv "$D/deploy/.env.tmp" "$D/deploy/.env"
FAKE_NO_VOLUME=1 run_trove "$D" up
case "$OUT" in *"TROVE_ADMIN_PASSWORD set (first install)"*) pass "no database volume + no admin password fails preflight" ;; *) fail "no database volume + no admin password fails preflight" "$OUT" ;; esac
run_trove "$D" up
case "$OUT" in *"TROVE_ADMIN_PASSWORD set"*) fail "existing database + no admin password passes this check" "$OUT" ;; *) pass "existing database + no admin password passes this check" ;; esac

echo "up: monitoring profile needs a Grafana password"
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
grep -v '^GRAFANA_ADMIN_PASSWORD=\|^COMPOSE_PROFILES=' "$D/deploy/.env" > "$D/deploy/.env.tmp" && mv "$D/deploy/.env.tmp" "$D/deploy/.env"
run_trove "$D" up
case "$OUT" in *"GRAFANA_ADMIN_PASSWORD set"*) fail "monitoring off: no Grafana check" "$OUT" ;; *) pass "monitoring off: no Grafana check" ;; esac
printf 'COMPOSE_PROFILES=ai,monitoring\n' >> "$D/deploy/.env"
run_trove "$D" up
case "$OUT" in *"GRAFANA_ADMIN_PASSWORD set (monitoring profile is on)"*) pass "monitoring on + no password fails preflight" ;; *) fail "monitoring on + no password fails preflight" "$OUT" ;; esac
[ "$RC" -ne 0 ] && pass "up exits non-zero" || fail "up exits non-zero"

echo "TROVE_BIND_ADDR derived from TROVE_TLS_MODE"
bind_for() { # bind_for DIR : the TROVE_BIND_ADDR the CLI hands to docker compose
  run_trove "$1" down
  printf '%s' "$OUT" | grep -o 'bind=[^ ]*' | head -n 1
}
D=$(new_install)
TROVE_DOMAIN=trove.test ACME_EMAIL=ops@trove.test TROVE_ADMIN_EMAIL=me@trove.test run_trove "$D" bootstrap
[ "$(bind_for "$D")" = "bind=unset" ] && pass "acme-http: not set (compose default 0.0.0.0)" || fail "acme-http: not set (compose default 0.0.0.0)"
printf 'TROVE_TLS_MODE=off\n' >> "$D/deploy/.env"
[ "$(bind_for "$D")" = "bind=127.0.0.1" ] && pass "off: loopback" || fail "off: loopback"
printf 'TROVE_BIND_ADDR=0.0.0.0\n' >> "$D/deploy/.env"
[ "$(bind_for "$D")" = "bind=0.0.0.0" ] && pass "off + explicit TROVE_BIND_ADDR: operator wins" || fail "off + explicit TROVE_BIND_ADDR: operator wins"

echo
echo "${CASES} checks, ${FAILURES} failed"
[ "$FAILURES" -eq 0 ]
