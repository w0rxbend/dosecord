#!/usr/bin/env bash
# Seeded failures of the secret scan (ROADMAP G0.8 acceptance): a fake bot
# token in a test fixture, a spike note and a transcript each fail the scan of
# a directory and the pre-commit scan of staged changes; so do a webhook
# secret and callback key material. A tree with only the repository's public
# test values passes. The fake values are assembled at run time, so this file
# holds none.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
scan="$repo/scripts/secret-scan.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 123456789:AA followed by 33 characters: the shape of a Telegram bot token.
# The scanner ignores values of one repeated character or a plain alphabet
# run, so the webhook and key seeds below mix their characters.
token="123456789:AA$(printf 'k%.0s' $(seq 1 33))"
webhook="TELEGRAM_WEBHOOK_SECRET_TOKEN=$(printf %s {a..x})"
callback="CALLBACK_KEYS=1:$(printf %s {A..O}{7,x,Q})"

failures=0
check() { # expected-exit description command...
  local expected="$1" what="$2"
  shift 2
  local status=0
  "$@" >"$work/out.log" 2>&1 || status=$?
  if [ "$status" -eq "$expected" ]; then
    echo "ok   $what (exit $status)"
  else
    echo "FAIL $what: exit $status, expected $expected" >&2
    sed 's/^/     /' "$work/out.log" >&2
    failures=$((failures + 1))
  fi
}

# One fresh repository per case, holding only the scanner configuration.
fresh() {
  rm -rf "$work/tree"
  mkdir -p "$work/tree"
  git -C "$work/tree" init -q
  git -C "$work/tree" config user.email scan@example.invalid
  git -C "$work/tree" config user.name scan
  git -C "$work/tree" config commit.gpgsign false
}

seed() { # relative-path content
  mkdir -p "$work/tree/$(dirname "$1")"
  printf '%s\n' "$2" >"$work/tree/$1"
}

staged() { (cd "$work/tree" && git add -A && "$scan" --staged); }

cases=(
  "app/test/resources/fixture.json|{\"token\": \"$token\"}"
  "docs/spikes/G1.1-bot-api.md|The bot answered with $token in the URL."
  "docs/verification/LS-1-transcript.md|> TELEGRAM_TOKEN=$token"
  "docs/verification/LS-2-transcript.md|$webhook"
  "deploy/staging.env|$callback"
)

for entry in "${cases[@]}"; do
  path="${entry%%|*}"
  content="${entry#*|}"
  fresh
  seed "$path" "$content"
  check 1 "directory scan finds the seeded secret in $path" "$scan" --dir "$work/tree"
  check 1 "pre-commit scan finds the seeded secret in $path" staged
done

fresh
seed "app/test/resources/settings.env" "CALLBACK_KEYS=0:MDEyMzQ1Njc4OWFiY2RlZg"
seed "docs/example.md" "TELEGRAM_WEBHOOK_SECRET_TOKEN=change-me-header-secret"
seed "app/test/resources/fake.json" '{"token": "555000111:fake-wire-token"}'
check 0 "the public test values pass the directory scan" "$scan" --dir "$work/tree"
check 0 "the public test values pass the pre-commit scan" staged

if [ "$failures" -gt 0 ]; then
  echo "test-secret-scan: $failures case(s) failed" >&2
  exit 1
fi
echo "test-secret-scan: all cases passed"
