#!/usr/bin/env bash
# Self-test of scripts/doc-lint.sh against a throwaway fixture: a forbidden
# term is rejected in guidance, accepted under an allowlisted subtree, and
# accepted only inside the allowlisted sections of a partly allowlisted file.
set -euo pipefail
lint="$(cd "$(dirname "$0")" && pwd)/doc-lint.sh"
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/archive"

manifest() {
  printf '%s\n' '[scope]' "$dir/**/*.md" '[forbidden]' 'RETIRED_TERM' '[allowlist]' "$dir/archive/**" "$@" > "$dir/manifest.txt"
}

failures=0
expect() {
  local want=$1 name=$2 got=0
  "$lint" "$dir/manifest.txt" > "$dir/out.log" 2>&1 || got=$?
  if [ "$got" -ne "$want" ]; then
    echo "test-doc-lint: FAIL $name: exit $got, expected $want"
    cat "$dir/out.log"
    failures=$((failures + 1))
  fi
}

cat > "$dir/roadmap.md" <<'EOF'
# Plan
## 1. Migration
RETIRED_TERM is history here.
### 1.2 Steps
RETIRED_TERM again.
## 2. Milestones
### 2.1 First
RETIRED_TERM in one slice.
```
# a shell comment inside a fence is not a heading
RETIRED_TERM
```
### 2.2 Second
Nothing retired.
## 10. Later
Nothing retired.
EOF
echo 'RETIRED_TERM' > "$dir/archive/old.md"

manifest
expect 1 "a term outside every allowlist is rejected"

manifest "$dir/roadmap.md#section-1"
expect 1 "one allowlisted section does not cover another section"

manifest "$dir/roadmap.md#section-1" "$dir/roadmap.md#section-2.1"
expect 0 "several sections of one file, a subsection and a fence"

echo 'RETIRED_TERM' >> "$dir/roadmap.md"
expect 1 "a term in a section that is not allowlisted is rejected"

manifest "$dir/roadmap.md#section-1" "$dir/roadmap.md#section-2.1" "$dir/roadmap.md#section-1.2"
sed -i '$d' "$dir/roadmap.md"
sed -i 's/^Nothing retired\.$/RETIRED_TERM/' "$dir/roadmap.md"
expect 1 "section 1 does not match section 10, nor 2.1 section 2.2"

if [ "$failures" -gt 0 ]; then
  echo "test-doc-lint: $failures failure(s)" >&2
  exit 1
fi
echo "test-doc-lint: 5 passed"
