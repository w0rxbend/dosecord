#!/usr/bin/env bash
# Exercises the operations scripts against throwaway databases only (G2.19 and
# the ops track): migrates a fresh PostgreSQL, writes rows that carry a canary
# string, then
#   - backup.sh writes an encrypted dump that does not contain the canary in
#     the clear, and removes a backup past the retention;
#   - restore-drill.sh restores it into its own container and passes; with a
#     wrong checksum it fails;
#   - live-smoke.sh reports the dead row as red and prints no canary.
# Needs Docker and GnuPG. Never touches a database it did not start.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/ops-lib.sh
. "$repo/scripts/ops-lib.sh"

canary="CANARY-ops-$$-payload"
work="$(mktemp -d)"
source_db="dosegram-ops-test-$$"
port="$(free_port)"
cleanup() {
  docker rm -f "$source_db" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

docker run -d --rm --name "$source_db" \
  -e POSTGRES_USER=dosegram -e POSTGRES_PASSWORD=dosegram -e POSTGRES_DB=dosegram \
  --tmpfs /var/lib/postgresql:rw -p "127.0.0.1:$port:5432" "$ops_pg_image" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$source_db" pg_isready -h 127.0.0.1 -U dosegram -d dosegram >/dev/null 2>&1 && break
  sleep 1
done
sleep 1
PGHOST=127.0.0.1 PGPORT="$port" "$repo/scripts/flyway-validate.sh" >"$work/flyway.log" 2>&1 ||
  { cat "$work/flyway.log" >&2; exit 1; }

export PGHOST=127.0.0.1 PGPORT="$port" PGUSER=dosegram PGPASSWORD=dosegram PGDATABASE=dosegram
psql_at "
  INSERT INTO worker_heartbeat (instance, role, last_tick_at) VALUES ('ops-test', 'all', now());
  INSERT INTO outbox_messages (id, send_key, op, kind, vendor, importance, payload, status, next_attempt_at,
                               attempted_at, sent_at, last_error)
  VALUES (gen_random_uuid(), 'k-sent', 'send', 'reminder', 'telegram', 'reminder',
          '{\"text\": \"$canary\"}', 'sent', now(), now(), now(), NULL),
         (gen_random_uuid(), 'k-dead', 'send', 'prompt', 'telegram', 'info',
          '{\"text\": \"$canary\"}', 'dead', now(), now(), NULL, E'line one\nline two $canary');" >/dev/null

# ---------- backup ----------
export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-gen-key 'restore drill <drill@example.invalid>' default default never
gpg --batch --quiet --armor --export >"$work/backup.pub.asc"
gpg --batch --quiet --armor --export-secret-keys >"$work/backup.key.asc"
unset GNUPGHOME

mkdir -p "$work/backups"
touch -d '40 days ago' "$work/backups/dosegram-20000101T000000Z.dump.gpg"
if BACKUP_DIR="$work/backups" BACKUP_GPG_RECIPIENT_FILE="$work/backup.pub.asc" \
  "$repo/scripts/backup.sh" >"$work/backup.log" 2>&1; then
  pass "backup.sh writes an encrypted dump"
else
  fail "backup.sh failed: $(cat "$work/backup.log")"
fi
mapfile -t dumps < <(find "$work/backups" -name 'dosegram-2*.dump.gpg' ! -name 'dosegram-2000*')
if [ "${#dumps[@]}" -eq 1 ] && [ -s "${dumps[0]}" ] && [ -f "${dumps[0]}.sha256" ]; then
  pass "one backup and its checksum exist"
else
  fail "expected one backup with a checksum, found ${#dumps[@]}"
fi
dump="${dumps[0]:-missing}"
if [ -e "$work/backups/dosegram-20000101T000000Z.dump.gpg" ]; then
  fail "a backup past the retention was kept"
else
  pass "a backup past the retention is removed"
fi
if grep -q -a "$canary" "$dump"; then fail "the backup holds the canary in the clear"; else pass "the backup holds no clear text"; fi
# The dump is compressed as well, so the canary test alone proves little: the
# file must be an OpenPGP message encrypted to a public key.
# Packet tag 1 first: a public-key encrypted session key (RFC 9580 5.1).
# gpg exits non-zero here (no secret key), so its output is read after it ends.
mkdir -m 700 "$work/empty-gnupg"
packets="$(GNUPGHOME="$work/empty-gnupg" gpg --batch --list-packets "$dump" 2>&1 || true)"
if grep -q '^# off=0 .* tag=1 ' <<<"$packets"; then
  pass "the backup is encrypted to the backup public key"
else
  fail "the backup is not a public-key encrypted OpenPGP message"
fi

# ---------- restore drill ----------
if BACKUP_GPG_PRIVATE_KEY_FILE="$work/backup.key.asc" \
  "$repo/scripts/restore-drill.sh" "$dump" >"$work/drill.log" 2>&1 &&
  grep -q 'restore-drill: PASS' "$work/drill.log"; then
  pass "restore-drill.sh restores the backup: $(grep 'row counts match' "$work/drill.log")"
else
  fail "restore-drill.sh failed: $(cat "$work/drill.log")"
fi
if grep -q "$canary" "$work/drill.log"; then fail "the drill printed user data"; else pass "the drill printed no user data"; fi
cp "$dump" "$work/tampered.dump.gpg"
echo "0000000000000000000000000000000000000000000000000000000000000000  tampered.dump.gpg" >"$work/tampered.dump.gpg.sha256"
if BACKUP_GPG_PRIVATE_KEY_FILE="$work/backup.key.asc" \
  "$repo/scripts/restore-drill.sh" "$work/tampered.dump.gpg" >"$work/tampered.log" 2>&1; then
  fail "a backup with a wrong checksum passed the drill"
else
  pass "a backup with a wrong checksum fails the drill"
fi
if docker ps -a --format '{{.Names}}' | grep -q '^dosegram-restore-drill-'; then
  fail "a drill container was left behind"
else
  pass "no drill container is left behind"
fi

# ---------- live smoke ----------
status=0
"$repo/scripts/live-smoke.sh" >"$work/smoke.log" 2>&1 || status=$?
if [ "$status" -eq 1 ] && grep -q 'dead in the last 24 h: 1' "$work/smoke.log" &&
  grep -q 'ops-test (all): last tick' "$work/smoke.log" && grep -q 'last reminder sent: ' "$work/smoke.log"; then
  pass "live-smoke.sh reports the dead row as red"
else
  fail "live-smoke.sh (exit $status): $(cat "$work/smoke.log")"
fi
if grep -q "$canary\|line two" "$work/smoke.log"; then fail "live-smoke printed user data"; else pass "live-smoke printed no user data"; fi

if [ "$failures" -gt 0 ]; then
  echo "test-ops-scripts: $failures check(s) failed" >&2
  exit 1
fi
echo "test-ops-scripts: all checks passed"
