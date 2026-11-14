#!/usr/bin/env bash
# Secret scan (ROADMAP G0.8) with the rules of .gitleaks.toml.
#
# Usage:
#   scripts/secret-scan.sh              the whole git history against .gitleaks-baseline.json (CI)
#   scripts/secret-scan.sh --staged     the staged changes of the repository in the current directory
#                                       (the pre-commit hook; see .pre-commit-config.yaml)
#   scripts/secret-scan.sh --dir PATH   a directory tree, without git
#
# The baseline holds the redacted findings of the history before G0.8: test
# values of the webhook and Mini App settings suites. A new finding fails.
# GITLEAKS names the binary; the default is `gitleaks` on PATH (CI installs
# the pinned 8.30.1). Findings are printed redacted.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
gitleaks="${GITLEAKS:-gitleaks}"
common=(--config "$repo/.gitleaks.toml" --redact --no-banner --exit-code 1)

case "${1:-}" in
  "")
    exec "$gitleaks" git "${common[@]}" --baseline-path "$repo/.gitleaks-baseline.json" "$repo"
    ;;
  --staged)
    exec "$gitleaks" git "${common[@]}" --pre-commit --staged .
    ;;
  --dir)
    exec "$gitleaks" dir "${common[@]}" "${2:?--dir needs a path}"
    ;;
  *)
    echo "usage: scripts/secret-scan.sh [--staged | --dir PATH]" >&2
    exit 2
    ;;
esac
