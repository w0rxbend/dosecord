#!/usr/bin/env bash
# Idle RSS gate (ROADMAP M0.5 acceptance): the app container must idle at
# <= 200 MB with the console adapter enabled. Reads VmRSS of PID 1 inside the
# container (`docker stats` includes page cache and overstates RSS). The
# measured value is printed for the CI artefact record.
#
# Usage: scripts/rss-check.sh [limit-mb]   (expects `docker compose up` already running)
set -euo pipefail
cd "$(dirname "$0")/.."

limit_mb="${1:-200}"
cid="$(docker compose -f docker/compose.yml ps -q app)"
[ -n "$cid" ] || { echo "rss-check: app container not running" >&2; exit 2; }

rss_kb="$(docker exec "$cid" awk '/^VmRSS:/ {print $2}' /proc/1/status)"
[ -n "$rss_kb" ] || { echo "rss-check: VmRSS unreadable in $cid" >&2; exit 2; }

rss_mb=$(( (rss_kb + 512) / 1024 ))
echo "rss-check: idle RSS ${rss_mb} MB (${rss_kb} kB), limit ${limit_mb} MB"
if [ "$rss_mb" -gt "$limit_mb" ]; then
  echo "rss-check: FAIL — idle RSS above ${limit_mb} MB" >&2
  exit 1
fi
echo "rss-check: PASS"
