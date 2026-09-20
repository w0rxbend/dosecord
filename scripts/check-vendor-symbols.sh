#!/usr/bin/env bash
# vendor-symbol gate (ROADMAP M0.4, DESIGN.md section 3): core/ and
# contracts/ must stay vendor-neutral. Greps both trees for the forbidden
# vendor/library symbols and exits 1 on any hit. Matching is fixed-string and
# case-sensitive: these are JVM package prefixes, so case variants are not
# valid references and would not compile anyway.
set -uo pipefail
cd "$(dirname "$0")/.."

symbols=("net.dv8tion" "org.telegram" "de.connect2x" "kotlinx" "sttp")
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
echo "vendor-symbols: clean (core/, contracts/ free of: ${symbols[*]})"
