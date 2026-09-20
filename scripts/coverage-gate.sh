#!/usr/bin/env bash
# coverage-gate — SCAFFOLD, not enforced until M1 exit (ROADMAP M0.4).
#
# Reads the scoverage XML report for the `core` module and computes statement
# coverage of the packages the gate will cover: dosecord.core.domain and
# dosecord.core.chat. The threshold is 85% (ROADMAP section 6, Quality gates).
#
# The gate is armed by setting COVERAGE_GATE_ARMED=true. Unarmed (the default
# until M1 exit) it only reports the measured rates and always exits 0.
#
# Usage: scripts/coverage-gate.sh [scoverage.xml]
#   default report path: out/core/scoverage/xmlReport.dest/scoverage.xml
set -uo pipefail
cd "$(dirname "$0")/.."

xml=${1:-out/core/scoverage/xmlReport.dest/scoverage.xml}
threshold=85
armed=${COVERAGE_GATE_ARMED:-false}

if [ ! -f "$xml" ]; then
  echo "coverage-gate: report $xml not found — run:" >&2
  echo "  ./mill core.test && ./mill core.scoverage.xmlReport" >&2
  exit 2
fi

# Sum statement-count / statements-invoked over every <package> whose name is
# or is nested under the given package, then print the statement rate.
rate_of() {
  awk -v target="$1" '
    match($0, /<package name="[^"]*"/) {
      name = substr($0, RSTART + 15, RLENGTH - 16)
      if (name == target || index(name, target ".") == 1) {
        if (match($0, /statement-count="[0-9]+"/))
          count += substr($0, RSTART + 17, RLENGTH - 18)
        if (match($0, /statements-invoked="[0-9]+"/))
          invoked += substr($0, RSTART + 20, RLENGTH - 21)
      }
    }
    END {
      if (count == 0) { print "NA"; exit }
      printf "%.2f", 100.0 * invoked / count
    }
  ' "$xml"
}

failed=0
for pkg in dosecord.core.domain dosecord.core.chat; do
  rate=$(rate_of "$pkg")
  if [ "$rate" = "NA" ]; then
    echo "coverage-gate: $pkg: no statements measured"
    failed=1
    continue
  fi
  status=ok
  awk -v r="$rate" -v t="$threshold" 'BEGIN { exit !(r + 0 < t + 0) }' && status="below $threshold%" || true
  echo "coverage-gate: $pkg: ${rate}% statement coverage (threshold ${threshold}%) ${status}"
  [ "$status" = ok ] || failed=1
done

if [ "$armed" != true ]; then
  echo "coverage-gate: SCAFFOLD — not armed until M1 exit (set COVERAGE_GATE_ARMED=true to enforce)"
  exit 0
fi
[ "$failed" -eq 0 ] || { echo "coverage-gate: FAILED (armed)" >&2; exit 1; }
echo "coverage-gate: passed (armed)"
