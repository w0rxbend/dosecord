#!/usr/bin/env bash
# vendor-symbol gate (ROADMAP M0.4, DESIGN.md section 3): core/ and
# contracts/ must stay vendor-neutral. Greps both trees for the forbidden
# vendor/library symbols and exits 1 on any hit. Matching is fixed-string and
# case-sensitive: these are JVM package prefixes, so case variants are not
# valid references and would not compile anyway.
set -uo pipefail
cd "$(dirname "$0")/.."

symbols=("org.telegram" "sttp")
dirs=("core" "contracts")

grep_args=()
for sym in "${symbols[@]}"; do grep_args+=(-e "$sym"); done

hits=0
for dir in "${dirs[@]}"; do
  out=$(grep -RnF "${grep_args[@]}" -- "$dir" || true)
  if [ -n "$out" ]; then
    printf '%s\n' "$out"
    hits=$((hits + $(printf '%s\n' "$out" | wc -l)))
  fi
done

if [ "$hits" -gt 0 ]; then
  echo "vendor-symbols: $hits forbidden symbol reference(s) under core/ or contracts/" >&2
  exit 1
fi

# Retired names (docs/dosegram/ROADMAP.md section 1.4): outside the documents,
# no tracked file names a retired vendor or the old product name. This script
# holds the patterns, so it is excluded from its own search.
retired_files() {
  local flags=$1 pattern=$2 status
  shift 2
  git grep "$flags" "$pattern" -- . ':!docs' ':!AGENTS.md' ':!README.md' ':!QUICKSTART.md' \
    ':!CHANGELOG.md' ':!scripts/check-vendor-symbols.sh' "$@"
  status=$?
  # git grep exits 1 for "no match"; anything higher is a git error.
  [ "$status" -le 1 ] || { echo "vendor-symbols: git grep failed ($status)" >&2; return 2; }
}

# scripts/check-bot-ux-plan.py keeps `max_discord_rows` until G0.2 archives it.
retired=$(
  retired_files -l 'net\.dv8tion\|[Dd]iscord\|[Zz]ulip\|Trixnity' ':!scripts/check-bot-ux-plan.py' &&
    retired_files -il 'dosecord'
) || exit 2
if [ -n "$retired" ]; then
  printf '%s\n' "$retired"
  echo "vendor-symbols: retired vendor or product name in tracked file(s) outside the documents" >&2
  exit 1
fi

echo "vendor-symbols: clean (core/, contracts/ free of: ${symbols[*]}; no retired name outside the documents)"
