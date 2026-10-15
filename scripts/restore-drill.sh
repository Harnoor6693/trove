#!/usr/bin/env bash
#
# restore-drill.sh — plan §9 risk 19 ("backups untested"), run by hand against a live compose
# stack WITHOUT touching it (collapse step 23).
#
#   scripts/restore-drill.sh            # the load stack's postgres (load-stack.sh project trove-load)
#   SOURCE_CONTAINER=<name> scripts/restore-drill.sh   # any other postgres container, read-only
#
# It takes the database half of `deploy/trove backup` with the same commands that script uses
# (pg_dump -Fc --no-owner --no-acl, then openssl aes-256-cbc/pbkdf2/600000 with a passphrase),
# decrypts it again, pg_restores it into a THROWAWAY postgres container of the same image, and
# compares the exact row count of every table in the public schema, source against restore.
# Exit 0 only if every count matches and the Flyway history is identical.
#
# What it does not do: restore the /data volume (a tar round trip, no logic of its own), or run
# `deploy/trove restore` itself — that command replaces the live database after an interactive
# hostname prompt, which is the one thing a drill must not do to the stack it is measuring.

set -euo pipefail

src="${SOURCE_CONTAINER:-${TROVE_LOAD_PROJECT:-trove-load}-postgres-1}"
image=$(docker inspect -f '{{.Config.Image}}' "$src")
user=$(docker exec "$src" printenv POSTGRES_USER)
db=$(docker exec "$src" printenv POSTGRES_DB)
scratch="trove-restore-drill-$$"
dir=$(mktemp -d "${TMPDIR:-/tmp}/trove-drill.XXXXXX")
export DRILL_PASSPHRASE; DRILL_PASSPHRASE=$(openssl rand -base64 32)
trap 'docker rm -f "$scratch" >/dev/null 2>&1 || true; rm -rf "$dir"' EXIT

counts() { # container -> "table count" lines, exact counts, sorted
  local tables
  tables=$(docker exec "$1" psql -U "$user" -d "$db" -tAc \
    "select tablename from pg_tables where schemaname = 'public' order by 1")
  for t in $tables; do
    printf '%s %s\n' "$t" "$(docker exec "$1" psql -U "$user" -d "$db" -tAc "select count(*) from \"$t\"")"
  done
}
history() { docker exec "$1" psql -U "$user" -d "$db" -tAc \
  "select installed_rank, version, checksum, success from flyway_schema_history order by 1"; }

echo "1/5 dump ${src} (pg_dump -Fc --no-owner --no-acl, as deploy/trove backup)"
before=$(counts "$src"); before_history=$(history "$src")
docker exec "$src" pg_dump -U "$user" -d "$db" -Fc --no-owner --no-acl > "$dir/database.dump"
after=$(counts "$src")
[ "$before" = "$after" ] || echo "   note: the source changed during the dump; comparing against the post-dump counts"

echo "2/5 encrypt + decrypt (openssl aes-256-cbc -md sha512 -pbkdf2 -iter 600000, as deploy/trove)"
openssl enc -aes-256-cbc -md sha512 -pbkdf2 -iter 600000 -salt -pass env:DRILL_PASSPHRASE \
  -in "$dir/database.dump" -out "$dir/database.dump.enc"
openssl enc -d -aes-256-cbc -md sha512 -pbkdf2 -iter 600000 -pass env:DRILL_PASSPHRASE \
  -in "$dir/database.dump.enc" -out "$dir/restored.dump"
cmp -s "$dir/database.dump" "$dir/restored.dump"
echo "   $(du -h "$dir/database.dump" | cut -f1) dump, $(du -h "$dir/database.dump.enc" | cut -f1) encrypted, byte-identical after decryption"

echo "3/5 scratch ${image} container ${scratch}"
docker run -d --name "$scratch" -e POSTGRES_USER="$user" -e POSTGRES_DB="$db" \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" "$image" >/dev/null
until docker exec "$scratch" pg_isready -U "$user" -d "$db" -h 127.0.0.1 >/dev/null 2>&1; do sleep 1; done

echo "4/5 pg_restore --no-owner --no-acl (as deploy/trove restore)"
docker exec -i "$scratch" pg_restore -U "$user" -d "$db" --no-owner --no-acl < "$dir/restored.dump"

echo "5/5 compare"
restored=$(counts "$scratch"); restored_history=$(history "$scratch")
if diff <(printf '%s\n' "$after") <(printf '%s\n' "$restored") >/dev/null \
   && [ "$before_history" = "$restored_history" ]; then
  echo "PASS  $(printf '%s\n' "$restored" | wc -l | tr -d ' ') tables, $(printf '%s\n' "$restored" | awk '{s+=$2} END {print s}') rows, $(printf '%s\n' "$restored_history" | wc -l | tr -d ' ') Flyway rows — identical"
  printf '%s\n' "$restored" | awk '$2 > 0' | sed 's/^/      /'
else
  echo "FAIL  source and restore differ:"
  diff <(printf '%s\n' "$after") <(printf '%s\n' "$restored") || true
  [ "$before_history" = "$restored_history" ] || echo "      and the Flyway history differs"
  exit 1
fi
