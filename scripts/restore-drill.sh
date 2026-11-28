#!/usr/bin/env bash
# Restore drill (ROADMAP G2.19, ARCHITECTURE 17.3): restores one encrypted
# backup into a throwaway PostgreSQL container and checks it.
#
#   1. checks the file against its .sha256 when that file exists;
#   2. decrypts it with the offline private key into a fresh GnuPG home;
#   3. restores it into a new postgres:18-alpine container on the loopback
#      interface, its data on tmpfs, with the roles the migrations create;
#   4. compares, table by table, the rows the dump holds with the rows the
#      restored database has;
#   5. runs `flyway validate` with the repository's migrations (a backup older
#      than the code may have pending migrations; those are allowed);
#   6. removes the container and the key material, whatever the outcome.
#
#   7. with PURGE_LIST_FILE, counts the accounts of the purge list that the
#      restored backup still holds: the replay at the first start of the
#      application (`dosegram migrate`) deletes them again before anything
#      serves (ARCHITECTURE SEC-16); PurgeReplayPgSuite tests that replay on a
#      backup restored the same way.
#
# Usage: scripts/restore-drill.sh BACKUP_FILE
# Settings (environment):
#   BACKUP_GPG_PRIVATE_KEY_FILE   the backup private key, armoured
#   BACKUP_GPG_PASSPHRASE_FILE    its passphrase, when it has one
#   PURGE_LIST_FILE               the live purge list (PURGE_LIST_PATH), optional
#
# It never connects to any database but the one it starts. Prints table names
# and counts only. Not yet part of it: the decryption of a canary account
# (G2.4b).
set -euo pipefail
# shellcheck source=scripts/ops-lib.sh
. "$(dirname "$0")/ops-lib.sh"
repo="$(cd "$(dirname "$0")/.." && pwd)"

backup="${1:?usage: scripts/restore-drill.sh BACKUP_FILE}"
[ -r "$backup" ] || die "cannot read $backup"
: "${BACKUP_GPG_PRIVATE_KEY_FILE:?BACKUP_GPG_PRIVATE_KEY_FILE is required}"

flyway_image="flyway/flyway:13.6.0-alpine"
db=drill
container="dosegram-restore-drill-$$-$RANDOM"
port="$(free_port)"
GNUPGHOME="$(fresh_gnupg_home)"
export GNUPGHOME
work="$(mktemp -d)"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$GNUPGHOME" "$work"
}
trap cleanup EXIT

if [ -f "$backup.sha256" ]; then
  (cd "$(dirname "$backup")" && sha256sum --quiet -c "$(basename "$backup").sha256") ||
    die "the checksum of $(basename "$backup") does not match"
  echo "restore-drill: checksum ok"
fi

passphrase=()
if [ -n "${BACKUP_GPG_PASSPHRASE_FILE:-}" ]; then
  passphrase=(--pinentry-mode loopback --passphrase-file "$BACKUP_GPG_PASSPHRASE_FILE")
fi
gpg --batch --quiet "${passphrase[@]}" --import "$BACKUP_GPG_PRIVATE_KEY_FILE"
decrypt() { gpg --batch --quiet "${passphrase[@]}" --decrypt "$backup"; }

docker run -d --rm --name "$container" \
  -e POSTGRES_USER="$db" -e POSTGRES_PASSWORD="$db" -e POSTGRES_DB="$db" \
  --tmpfs /var/lib/postgresql:rw \
  -p "127.0.0.1:$port:5432" "$ops_pg_image" >/dev/null
ready=no
for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready -h 127.0.0.1 -U "$db" -d "$db" >/dev/null 2>&1; then
    ready=yes
    break
  fi
  sleep 1
done
[ "$ready" = yes ] || die "the throwaway database did not start"
in_drill() { docker exec -i "$container" "$@"; }
# Without -i: a query inside a read loop must not swallow the loop's input.
drill_sql() { docker exec "$container" psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -U "$db" -d "$db" -c "$1"; }

# Roles are cluster objects that a database dump does not carry, but its
# grants and policies name them: the roles the migrations create (V1, V14)
# and the role that ran the migrations (the schema_owner policies of V14).
roles="$(decrypt | in_drill pg_restore --schema-only -f - |
  sed -n -E 's/^(CREATE POLICY|GRANT) .* TO ([a-z_][a-z0-9_]*)( USING| WITH|;).*/\2/p' | sort -u)"
for role in $roles; do
  [ -n "$(drill_sql "SELECT 1 FROM pg_roles WHERE rolname = '$role'")" ] ||
    drill_sql "CREATE ROLE $role NOLOGIN"
done
decrypt | in_drill pg_restore -U "$db" -d "$db" --no-owner --exit-on-error
echo "restore-drill: restored into a throwaway database"

# Rows per table inside the dump: the lines of each COPY block. COPY text
# escapes newlines inside values, so one line is one row.
decrypt | in_drill pg_restore --data-only -f - |
  awk '/^COPY [^ ]+ / { table = $2; rows = 0; inside = 1; next }
       inside && /^\\\.$/ { print table "|" rows; inside = 0; next }
       inside { rows++ }' | sort >"$work/dump.counts"
[ -s "$work/dump.counts" ] || die "the dump holds no table data"

: >"$work/restored.counts"
while IFS='|' read -r table _; do
  printf '%s|%s\n' "$table" "$(drill_sql "SELECT count(*) FROM $table")" >>"$work/restored.counts"
done <"$work/dump.counts"
sort -o "$work/restored.counts" "$work/restored.counts"
if ! diff "$work/dump.counts" "$work/restored.counts" >"$work/counts.diff"; then
  echo "restore-drill: row counts differ (dump < > restored):" >&2
  cat "$work/counts.diff" >&2
  exit 1
fi
tables="$(wc -l <"$work/dump.counts")"
rows="$(awk -F'|' '{ s += $2 } END { print s + 0 }' "$work/dump.counts")"
echo "restore-drill: row counts match: $tables tables, $rows rows"

docker run --rm --network host \
  -v "$repo/infra/src/main/resources/db/migration:/flyway/sql:ro" \
  "$flyway_image" \
  -url="jdbc:postgresql://127.0.0.1:$port/$db" -user="$db" -password="$db" \
  -ignoreMigrationPatterns='*:pending' -connectRetries=10 \
  validate >"$work/flyway.log" 2>&1 || {
  cat "$work/flyway.log" >&2
  die "flyway validate failed on the restored database"
}
version="$(drill_sql "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
echo "restore-drill: flyway validate ok at V$version"

if [ -n "${PURGE_LIST_FILE:-}" ]; then
  [ -r "$PURGE_LIST_FILE" ] || die "cannot read $PURGE_LIST_FILE"
  listed=0
  present=0
  while read -r _ account; do
    [ -n "${account:-}" ] || continue
    [[ "$account" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] ||
      die "the purge list holds a line that is not an instant and an account id"
    listed=$((listed + 1))
    present=$((present + $(drill_sql "SELECT count(*) FROM users WHERE id = '$account'")))
  done <"$PURGE_LIST_FILE"
  echo "restore-drill: purge list: $present of $listed listed account(s) in this backup; the first start replays the list and deletes them"
fi
echo "restore-drill: PASS"
