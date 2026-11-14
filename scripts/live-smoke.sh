#!/usr/bin/env bash
# Live health report from the database (ops track, 2026-11-14): outbox,
# inbox, worker heartbeats and the last reminder sent. It reads counts, ages,
# kinds and instance names only: never a payload, a message body, an error
# text or an account id. Read-only: every query is a SELECT.
#
# Usage: scripts/live-smoke.sh
# Database access: see scripts/ops-lib.sh (PG_CONTAINER or the PG* variables).
# Thresholds (environment, seconds): SMOKE_MAX_OUTBOX_AGE (300),
# SMOKE_MAX_HEARTBEAT_AGE (120), SMOKE_MAX_INBOX_AGE (300).
# Exit status: 0 when every check is green, 1 when one is red, 2 when the
# database cannot be read.
set -euo pipefail
# shellcheck source=scripts/ops-lib.sh
. "$(dirname "$0")/ops-lib.sh"

max_outbox_age="${SMOKE_MAX_OUTBOX_AGE:-300}"
max_heartbeat_age="${SMOKE_MAX_HEARTBEAT_AGE:-120}"
max_inbox_age="${SMOKE_MAX_INBOX_AGE:-300}"

query() { psql_at "$1" || { echo "live-smoke: cannot read the database" >&2; exit 2; }; }

psql_at "SELECT 1" >/dev/null 2>&1 || { echo "live-smoke: cannot read the database" >&2; exit 2; }

red=0
verdict() { # name ok?
  if [ "$2" = yes ]; then echo "  [green] $1"; else echo "  [red]   $1"; red=$((red + 1)); fi
}
le() { [ "$1" -le "$2" ] && echo yes || echo no; }

echo "outbox"
IFS='|' read -r pending oldest <<<"$(query "
  SELECT count(*), COALESCE(floor(extract(epoch FROM now() - min(next_attempt_at)))::bigint, 0)
  FROM outbox_messages
  WHERE status IN ('queued', 'failed_retry', 'sending') AND next_attempt_at <= now()")"
echo "  due and unsent: $pending, oldest due ${oldest}s ago"
verdict "oldest due row under ${max_outbox_age}s" "$(le "$oldest" "$max_outbox_age")"
dead="$(query "
  SELECT count(*) FROM outbox_messages
  WHERE status = 'dead' AND COALESCE(attempted_at, created_at) > now() - interval '24 hours'")"
echo "  dead in the last 24 h: $dead"
query "
  SELECT kind || ': ' || count(*) FROM outbox_messages
  WHERE status = 'dead' AND COALESCE(attempted_at, created_at) > now() - interval '24 hours'
  GROUP BY kind ORDER BY kind" | sed 's/^/    /'
verdict "no dead row in the last 24 h" "$(le "$dead" 0)"

echo "inbox"
IFS='|' read -r inbox_pending inbox_oldest <<<"$(query "
  SELECT count(*), COALESCE(floor(extract(epoch FROM now() - min(received_at)))::bigint, 0)
  FROM inbound_inbox WHERE status = 'pending'")"
quarantined="$(query "
  SELECT count(*) FROM inbound_inbox
  WHERE status = 'quarantined' AND finished_at > now() - interval '24 hours'")"
echo "  pending: $inbox_pending, oldest ${inbox_oldest}s ago; quarantined in the last 24 h: $quarantined"
verdict "oldest pending update under ${max_inbox_age}s" "$(le "$inbox_oldest" "$max_inbox_age")"
verdict "no quarantined update in the last 24 h" "$(le "$quarantined" 0)"

echo "workers"
heartbeats="$(query "
  SELECT instance || '|' || role || '|' || floor(extract(epoch FROM now() - last_tick_at))::bigint
  FROM worker_heartbeat ORDER BY instance")"
if [ -z "$heartbeats" ]; then
  verdict "a worker heartbeat exists" no
else
  freshest=""
  while IFS='|' read -r instance role age; do
    echo "  $instance ($role): last tick ${age}s ago"
    if [ -z "$freshest" ] || [ "$age" -lt "$freshest" ]; then freshest="$age"; fi
  done <<<"$heartbeats"
  verdict "a worker ticked within ${max_heartbeat_age}s" "$(le "$freshest" "$max_heartbeat_age")"
fi

echo "reminders"
last="$(query "
  SELECT COALESCE(floor(extract(epoch FROM now() - max(sent_at)))::bigint::text, 'never')
  FROM outbox_messages WHERE kind = 'reminder' AND status = 'sent'")"
if [ "$last" = never ]; then
  echo "  last reminder sent: never"
else
  echo "  last reminder sent: ${last}s ago"
fi

if [ "$red" -gt 0 ]; then
  echo "live-smoke: $red check(s) red"
  exit 1
fi
echo "live-smoke: all checks green"
