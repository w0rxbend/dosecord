#!/usr/bin/env bash
# doc-lint — documentation lint driven by docs/doc-lint.txt (ROADMAP M0.4).
#
# Reads the manifest's [scope], [forbidden] and [allowlist] sections, scans
# every in-scope file for the forbidden terms, and exits 1 when a term appears
# outside the allowlist. Matching is fixed-string and case-insensitive (a
# documented choice: case variants of retired names, e.g. "Kafka_" vs
# "KAFKA_", must not slip through).
#
# Allowlist entries:
#   path/**                the whole subtree
#   path                   exactly this file
#   path#section-N         the `## N. ...` section of the file (from its
#                          heading line up to the next `## ` heading)
#
# Usage: scripts/doc-lint.sh [manifest]   (default docs/doc-lint.txt)
set -euo pipefail
cd "$(dirname "$0")/.."

manifest=${1:-docs/doc-lint.txt}
[ -f "$manifest" ] || { echo "doc-lint: manifest $manifest not found" >&2; exit 2; }

scope=()
forbidden=()
allow_paths=()
allow_prefixes=()
declare -A allow_sections=()

section=""
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%$'\r'}
  case "$line" in
    ''|\#*) continue ;;            # blank lines and full-line comments
    \[*\]) section=${line#[}; section=${section%]}; continue ;;
  esac
  case "$section" in
    scope)     scope+=("$line") ;;
    forbidden) forbidden+=("$line") ;;
    allowlist)
      if [[ $line == *#section-* ]]; then
        allow_sections[${line%%#section-*}]=${line##*#section-}
      elif [[ $line == *"/**" ]]; then
        allow_prefixes+=("${line%/**}/")
      elif [[ $line == */ ]]; then
        allow_prefixes+=("$line")
      else
        allow_paths+=("$line")
      fi
      ;;
  esac
done < "$manifest"

[ ${#forbidden[@]} -gt 0 ] || { echo "doc-lint: no [forbidden] terms in $manifest" >&2; exit 2; }

shopt -s globstar nullglob
files=()
for pattern in "${scope[@]}"; do
  for f in $pattern; do
    [ -f "$f" ] && files+=("$f")
  done
done

is_allowlisted_file() {
  local f=$1 p
  for p in ${allow_paths[@]+"${allow_paths[@]}"}; do [ "$f" = "$p" ] && return 0; done
  for p in ${allow_prefixes[@]+"${allow_prefixes[@]}"}; do [[ $f == "$p"* ]] && return 0; done
  return 1
}

# Print the line numbers of the `## N.` section of file $2 (heading line
# included, up to the next `## ` heading).
section_lines() {
  awk -v n="$1" '
    /^## / {
      if (insec) insec = 0
      if ($0 ~ "^## " n "\\. ") insec = 1
    }
    insec { print NR }
  ' "$2"
}

grep_args=()
for term in "${forbidden[@]}"; do grep_args+=(-e "$term"); done

violations=0
scanned=0
for f in ${files[@]+"${files[@]}"}; do
  if is_allowlisted_file "$f"; then continue; fi
  scanned=$((scanned + 1))
  hits=$(grep -niF "${grep_args[@]}" -- "$f" || true)
  [ -n "$hits" ] || continue
  if [ ${#allow_sections[@]} -gt 0 ] && [ -n "${allow_sections[$f]:-}" ]; then
    excluded=$(section_lines "${allow_sections[$f]}" "$f")
    hits=$(printf '%s\n' "$hits" | awk -v ex="$excluded" '
      BEGIN { n = split(ex, a, "\n"); for (i = 1; i <= n; i++) skip[a[i]] = 1 }
      { split($0, b, ":"); if (!((b[1]) in skip)) print }
    ')
  fi
  [ -n "$hits" ] || continue
  while IFS= read -r hit; do
    lineno=${hit%%:*}
    text=${hit#*:}
    term_found=""
    for term in "${forbidden[@]}"; do
      if grep -qiF -- "$term" <<< "$text"; then term_found=$term; break; fi
    done
    printf 'doc-lint: %s:%s: forbidden term "%s"\n' "$f" "$lineno" "$term_found"
    violations=$((violations + 1))
  done <<< "$hits"
done

if [ "$violations" -gt 0 ]; then
  echo "doc-lint: $violations violation(s)" >&2
  exit 1
fi
echo "doc-lint: clean ($scanned files scanned, ${#forbidden[@]} forbidden terms)"
