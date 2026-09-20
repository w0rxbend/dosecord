#!/usr/bin/env bash
# licence gate (ROADMAP M0.4, DESIGN.md section 11: "licence gate on the
# dependency tree").
#
# Resolves every module's dependency tree via Mill (`mill show
# __.resolvedMvnDeps`), reads each artifact's POM from the coursier cache
# (following <parent> POMs when the licence block is inherited), and fails
# unless every dependency declares at least one licence matching the
# permissive allow-regex below. Dual-licensed artifacts pass via their
# permissive option (e.g. JNA's LGPL-2.1-or-later + Apache-2.0).
# Dependencies with no declared licence fail closed.
set -euo pipefail
cd "$(dirname "$0")/.."

ALLOW_RE='apache|mit|bsd|eclipse public license|\bepl\b|cc0|public domain|isc|zlib|unicode'
CACHE="${COURSIER_CACHE:-$HOME/.cache/coursier}/v1/https/repo1.maven.org/maven2"

resolve_licenses() { # group.path artifact version [depth]
  local gp=$1 art=$2 ver=$3 depth=${4:-0}
  [ "$depth" -le 4 ] || return 0
  local pom="$CACHE/$gp/$art/$ver/$art-$ver.pom"
  [ -f "$pom" ] || return 0
  local names
  names=$(sed -n '/<licenses>/,/<\/licenses>/p' "$pom" \
    | grep -oE '<name>[^<]*</name>' | sed 's/<[^>]*>//g' | paste -sd';')
  if [ -n "$names" ]; then
    printf '%s' "$names"
    return 0
  fi
  local pg pa pv
  pg=$(sed -n '/<parent>/,/<\/parent>/p' "$pom" | grep -oE '<groupId>[^<]*</groupId>' | head -1 | sed 's/<[^>]*>//g')
  pa=$(sed -n '/<parent>/,/<\/parent>/p' "$pom" | grep -oE '<artifactId>[^<]*</artifactId>' | head -1 | sed 's/<[^>]*>//g')
  pv=$(sed -n '/<parent>/,/<\/parent>/p' "$pom" | grep -oE '<version>[^<]*</version>' | head -1 | sed 's/<[^>]*>//g')
  [ -n "$pg" ] && resolve_licenses "${pg//.//}" "$pa" "$pv" $((depth + 1))
  return 0
}

deps=$(./mill show __.resolvedMvnDeps 2>/dev/null \
  | grep -oE '/[^"]+/maven2/[^"]+\.jar' | sed 's/.*maven2\///' | sort -u)
[ -n "$deps" ] || { echo "license-gate: no dependencies resolved" >&2; exit 2; }

failures=0
count=0
while read -r rel; do
  dir=$(dirname "$rel")
  ver=$(basename "$dir")
  art=$(basename "$(dirname "$dir")")
  gp=$(dirname "$(dirname "$dir")")
  coord="$(printf '%s' "$gp" | tr / .):$art:$ver"
  names=$(resolve_licenses "$gp" "$art" "$ver")
  count=$((count + 1))
  if [ -z "$names" ]; then
    echo "license-gate: $coord :: NO LICENCE DECLARED"
    failures=$((failures + 1))
  elif ! tr ';' '\n' <<< "$names" | grep -qiE "$ALLOW_RE"; then
    echo "license-gate: $coord :: no permissive licence in: $names"
    failures=$((failures + 1))
  fi
done <<< "$deps"

if [ "$failures" -gt 0 ]; then
  echo "license-gate: $failures dependenc(ies) without a permissive licence" >&2
  exit 1
fi
echo "license-gate: clean ($count dependencies, all declare a permissive licence)"
