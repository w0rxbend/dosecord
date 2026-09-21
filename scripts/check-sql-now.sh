#!/usr/bin/env bash
# sql-now gate (ROADMAP M1.5, ADR-011): every query takes `now` as a bind
# parameter from the injected Clock; SQL now() is allowed only in DEFAULT
# clauses of the Flyway migrations. Scans (a) sql"""...""" interpolations in
# every Scala source of every module and (b) the migration SQL with comments
# stripped. Matching is case-insensitive (NOW() is the same function).
set -uo pipefail
cd "$(dirname "$0")/.."

hits=0

# (a) Scala sources: extract the contents of sql""" blocks (starting a block
# on a line containing sql""" and closing it at the next """), then look for
# now( inside them. Scala calls like clock.now() or def now() are outside
# these blocks and never match.
scala_hits=$(
  find core contracts infra adapter-console app tests -name '*.scala' -print0 2>/dev/null |
    xargs -0 awk '
      {
        if (inblk) {
          if ($0 ~ /(^|[^.A-Za-z0-9_])now[[:space:]]*\(/) printf "%s:%d:%s\n", FILENAME, FNR, $0
          if ($0 ~ /"""/) inblk = 0
        } else if ($0 ~ /sql"""/) {
          rest = $0
          sub(/.*sql"""/, "", rest)
          if (rest ~ /"""/) {
            if (rest ~ /(^|[^.A-Za-z0-9_])now[[:space:]]*\(/) printf "%s:%d:%s\n", FILENAME, FNR, $0
          } else {
            if (rest ~ /(^|[^.A-Za-z0-9_])now[[:space:]]*\(/) printf "%s:%d:%s\n", FILENAME, FNR, $0
            inblk = 1
          }
        }
      }' || true
)
if [ -n "$scala_hits" ]; then
  printf '%s\n' "$scala_hits"
  hits=$((hits + $(printf '%s\n' "$scala_hits" | wc -l)))
fi

# (b) Migration SQL: strip -- comments, then allow only DEFAULT now().
sql_hits=$(
  find infra/src/main/resources/db/migration -name '*.sql' -print0 |
    xargs -0 -I{} sh -c '
      sed "s/--.*//" "$1" |
        grep -nEi "(^|[^.A-Za-z0-9_])now[[:space:]]*\(" |
        grep -vEi "DEFAULT[[:space:]]+now[[:space:]]*\(" |
        sed "s|^|$1:|" || true
    ' _ {} || true
)
if [ -n "$sql_hits" ]; then
  printf '%s\n' "$sql_hits"
  hits=$((hits + $(printf '%s\n' "$sql_hits" | wc -l)))
fi

if [ "$hits" -gt 0 ]; then
  echo "sql-now: $hits SQL now() reference(s) outside DEFAULT clauses (queries must bind now from the Clock)" >&2
  exit 1
fi
echo "sql-now: clean (no SQL now() outside DEFAULT clauses)"
