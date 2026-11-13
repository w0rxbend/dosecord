#!/usr/bin/env bash
# doc-lint — documentation lint driven by docs/doc-lint.txt.
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
#   path#section-N         the section numbered N of the file, at any heading
#                          level (`## 1. ...`, `### 3.2 ...`), from its heading
#                          line up to the next heading of the same or a higher
#                          level. A file may list several sections.
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
        allow_sections[${line%%#section-*}]+="${line##*#section-} "
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

# Print the line numbers of the sections of file $2 whose numbers are listed,
# space separated, in $1 (heading line included, up to the next heading of the
# same or a higher level). A `#` line inside a code fence is not a heading.
section_lines() {
  awk -v numbers="$1" '
    BEGIN {
      count = split(numbers, wanted, " ")
      for (i = 1; i <= count; i++) gsub(/\./, "\\.", wanted[i])
    }
    /^```/ { fenced = !fenced }
    !fenced && /^#+ / {
      level = length($1)
      if (inside && level <= inside) inside = 0
      if (!inside)
        for (i = 1; i <= count; i++)
          if ($0 ~ "^#+ " wanted[i] "\\.? ") inside = level
    }
    inside { print NR }
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
