#!/usr/bin/env bash
#
# check-env-example.sh — core/.env.example must be sourceable by a shell (the documented way to
# load it: `set -a; . ./.env; set +a`), and must come out intact and dev-safe.
#
#   scripts/check-env-example.sh [path-to-env-file]      # default: core/.env.example
#
# Sources the file in a clean child shell with `set -e -u` and compares the values that have bitten
# before: the cron string, the JVM options, the trusted-proxy regex and the quoted display name.
# It also fails if the file sets a production-only value (TROVE_DATA_DIR, TROVE_ENCRYPTION_KEY,
# TROVE_EGRESS_ALLOW_PRIVATE) that would override the dev profile's defaults when run from source.
# Run from the repository root; CI runs it in the Backend job.

set -euo pipefail

file="${1:-core/.env.example}"
[ -f "$file" ] || { echo "FAIL: $file not found" >&2; exit 2; }

fail=0
check() { # name expected actual
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 is [$3], expected [$2]" >&2
    fail=1
  else
    echo "ok:   $1"
  fi
}

# One NAME=value line per variable, read back through `env -0` so a value is never split or glob-expanded.
loaded="$(env -i PATH="$PATH" HOME="$HOME" bash -c 'set -e -u; set -a; . "$1"; set +a; env -0' _ "$file" | tr '\0' '\n')" \
  || { echo "FAIL: sourcing $file with 'set -a; . file; set +a' failed" >&2; exit 1; }

get() { printf '%s\n' "$loaded" | sed -n "s/^$1=//p" | head -n 1; }

check SPRING_PROFILES_ACTIVE dev "$(get SPRING_PROFILES_ACTIVE)"
check JAVA_TOOL_OPTIONS '-Xmx1536m -XX:+UseZGC' "$(get JAVA_TOOL_OPTIONS)"
check TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON '0 17 9 * * *' "$(get TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON)"
check TROVE_OIDC_DISPLAY_NAME 'Single sign-on' "$(get TROVE_OIDC_DISPLAY_NAME)"
check TROVE_TRUSTED_PROXIES \
  '127\..*|10\..*|192\.168\..*|172\.1[6-9]\..*|172\.2[0-9]\..*|172\.3[01]\..*|::1|0:0:0:0:0:0:0:1' \
  "$(get TROVE_TRUSTED_PROXIES)"

# Production-only values must stay commented out, or they override the dev defaults.
for name in TROVE_DATA_DIR TROVE_ENCRYPTION_KEY TROVE_EGRESS_ALLOW_PRIVATE; do
  check "$name unset" "" "$(get "$name")"
done

exit "$fail"
