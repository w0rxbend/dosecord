#!/usr/bin/env bash
# Encrypted nightly backup of the database (ROADMAP G2.19, ARCHITECTURE 17.3).
#
# pg_dump in the custom format is piped straight into GnuPG and encrypted to
# the backup public key, so no plain dump touches the disk. The result is
#   $BACKUP_DIR/dosegram-<UTC stamp>.dump.gpg  and its .sha256
# and backups older than the retention are deleted after a good backup.
# The database holds no key-encryption key (ARCHITECTURE 17.3: keys are backed
# up separately, offline), so the dump carries none.
#
# Settings (environment):
#   BACKUP_DIR                  where the encrypted files go; off-host storage,
#                               or copied there after this script (docs/OPERATIONS.md)
#   BACKUP_GPG_RECIPIENT_FILE   the backup public key, armoured; its private half
#                               stays offline and is needed only to restore
#   BACKUP_RETENTION_DAYS       default 35 (ARCHITECTURE 17.3)
#   database access             see scripts/ops-lib.sh (PG_CONTAINER or PG* variables)
#
# Recovery point: up to 24 hours with one run a night; this is stated to the
# owner (ROADMAP G2.19). Prints file names, sizes and counts only.
set -euo pipefail
# shellcheck source=scripts/ops-lib.sh
. "$(dirname "$0")/ops-lib.sh"

: "${BACKUP_DIR:?BACKUP_DIR is required}"
: "${BACKUP_GPG_RECIPIENT_FILE:?BACKUP_GPG_RECIPIENT_FILE is required}"
retention="${BACKUP_RETENTION_DAYS:-35}"
[[ "$retention" =~ ^[1-9][0-9]*$ ]] || die "BACKUP_RETENTION_DAYS must be a positive number of days"
[ -r "$BACKUP_GPG_RECIPIENT_FILE" ] || die "cannot read the backup public key"
mkdir -p "$BACKUP_DIR"

umask 077
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/dosegram-$stamp.dump.gpg"
partial="$final.partial"
GNUPGHOME="$(fresh_gnupg_home)"
export GNUPGHOME
trap 'rm -rf "$GNUPGHOME" "$partial"' EXIT

pg_client pg_dump --format=custom --compress=6 |
  gpg --batch --quiet --yes --trust-model always \
    --recipient-file "$BACKUP_GPG_RECIPIENT_FILE" --encrypt --output "$partial"

# An empty or truncated file must never count as a backup.
[ -s "$partial" ] || die "the encrypted dump is empty"
mv "$partial" "$final"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$final")" >"$(basename "$final").sha256")
echo "backup: wrote $(basename "$final") ($(stat -c %s "$final") bytes)"

# Retention: only after a good backup, never the one just written.
expired=0
while IFS= read -r -d '' old; do
  [ "$old" = "$final" ] && continue
  rm -f "$old" "$old.sha256"
  expired=$((expired + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -name 'dosegram-*.dump.gpg' -mtime "+$((retention - 1))" -print0)
echo "backup: removed $expired backup(s) older than $retention days"
