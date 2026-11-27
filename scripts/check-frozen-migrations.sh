#!/usr/bin/env bash
# Frozen migrations gate (ADR-002 addendum of 2026-11-13, ARCHITECTURE section
# 10.17). The owner's live database holds real data from 2026-11-13, so a
# committed Flyway migration is never edited: Flyway would refuse the changed
# checksum at the next start. V1__baseline.sql is frozen at the schema that
# database was created with (Flyway checksum 1193628809, local tag
# schema-baseline-v1). Every schema change is a new versioned migration, V2,
# V3 and so on.
#
# Fails when a migration listed in scripts/frozen-migrations.sha256 changed or
# is missing, or when a migration file is not listed. A new migration is
# listed once it is final, in the commit that adds it:
#   sha256sum infra/src/main/resources/db/migration/V<n>__<name>.sql >> scripts/frozen-migrations.sha256
set -euo pipefail
cd "$(dirname "$0")/.."

manifest=scripts/frozen-migrations.sha256
migrations=infra/src/main/resources/db/migration
failed=0

if ! sha256sum --check --strict --quiet "$manifest"; then
  echo "check-frozen-migrations: a committed migration changed. Restore it and put the change in a new versioned migration." >&2
  failed=1
fi

while IFS= read -r -d '' file; do
  if ! grep -qF "  $file" "$manifest"; then
    echo "check-frozen-migrations: $file is not listed in $manifest" >&2
    failed=1
  fi
done < <(find "$migrations" -type f -print0 | sort -z)

if [[ $failed -ne 0 ]]; then
  exit 1
fi
# The count is of the migration files, each listed and unchanged, with their versions, so that the line can be
# compared with the docs ("V1 to V19, V22 and V23").
versions=$(find "$migrations" -type f -name 'V*__*.sql' -printf '%f\n' | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n)
count=$(printf '%s\n' "$versions" | grep -c .)
ranges=$(printf '%s\n' "$versions" | awk '
  function run() { return start == last ? "V" start : (last == start + 1 ? "V" start ", V" last : "V" start " to V" last) }
  NR == 1 { start = $1; last = $1; next }
  $1 == last + 1 { last = $1; next }
  { out = out sep run(); sep = ", "; start = $1; last = $1 }
  END { print out sep run() }')
echo "check-frozen-migrations: $count migrations unchanged ($ranges)"
