#!/usr/bin/env bash
# Checks a Mini App origin before BotFather gets its address (docs/DEPLOY_MINIAPP.md section 5): the shell and its
# security headers through the proxy, a built file, the API's refusal of forged launch data, the paths the proxy
# must not forward, the redirect from plain http, the proxy's body limit, and /readyz on the host.
#
# Usage: scripts/miniapp-preflight.sh <MINI_APP_URL> [options]
#   <MINI_APP_URL>       the address BotFather gets, https://<host>/app/
#   --health <url>       base URL of the health listener, http://HEALTH_ADDRESS:HEALTH_PORT (default
#                        http://127.0.0.1:18081, the live host's); "none" skips /readyz (another machine)
#   --http <url>         the plain http address that must redirect (default: http://<host>/app/); "none" skips it
#   --cacert <file>      trust this certificate authority as well (the local rehearsal's own)
#
# Prints one line per check, "ok" or "FAIL", and exits 1 when one fails. It sends no launch data that could open a
# session, prints no response body, and needs curl only.
set -euo pipefail

usage() {
  sed -n '6,12p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[ $# -ge 1 ] || usage
url="$1"
shift
health="http://127.0.0.1:18081"
plain=""
curl_tls=()
while [ $# -gt 0 ]; do
  case "$1" in
    --health) health="${2:?--health needs a URL or none}"; shift 2 ;;
    --http) plain="${2:?--http needs a URL or none}"; shift 2 ;;
    --cacert) curl_tls=(--cacert "${2:?--cacert needs a file}"); shift 2 ;;
    *) usage ;;
  esac
done

if ! [[ "$url" =~ ^https://([^/]+)/app/$ ]]; then
  echo "FAIL the address must be https://<host>/app/ exactly, as MINI_APP_URL and BotFather take it" >&2
  exit 1
fi
authority="${BASH_REMATCH[1]}"
origin="https://$authority"
[ -n "$plain" ] || plain="http://${authority%%:*}/app/"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }

# fetch NAME ARGS...: one request; headers in $work/NAME.h, body in $work/NAME.b, status printed.
fetch() {
  local name="$1"
  shift
  local code
  code="$(curl -sS --max-time 15 "${curl_tls[@]}" -D "$work/$name.h" -o "$work/$name.b" -w '%{http_code}' "$@" \
    2>"$work/$name.e")" || true
  touch "$work/$name.h" "$work/$name.b"
  echo "${code:-000}"
}

# header NAME FIELD: every value of one response header, one per line, without the carriage return.
header() {
  grep -i "^$2:" "$work/$1.h" | sed -e 's/^[^:]*: *//' -e 's/\r$//' || true
}

expect_header() {
  local name="$1" field="$2" wanted="$3" got
  got="$(header "$name" "$field")"
  if [ "$got" = "$wanted" ]; then pass "$field is \"$wanted\""; else fail "$field is \"$wanted\" (got \"${got//$'\n'/ | }\")"; fi
}

# ---------- the shell ----------
status="$(fetch shell "$url")"
if [ "$status" = 200 ]; then pass "GET $url answers 200"; else
  fail "GET $url answers 200 (got $status: $(head -c 200 "$work/shell.e" 2>/dev/null))"
fi
policy="$(header shell content-security-policy)"
if [ "$(printf '%s\n' "$policy" | grep -c .)" = 1 ]; then pass "one Content-Security-Policy"; else
  fail "one Content-Security-Policy (got $(printf '%s\n' "$policy" | grep -c .); the proxy must add none)"
fi
for directive in "default-src 'none'" "script-src 'self' https://telegram.org" "connect-src 'self'" \
  "frame-ancestors https://web.telegram.org"; do
  if [[ "; $policy;" == *"; $directive;"* ]]; then pass "the policy has $directive"; else fail "the policy has $directive"; fi
done
expect_header shell strict-transport-security "max-age=31536000"
expect_header shell x-content-type-options nosniff
expect_header shell referrer-policy no-referrer
expect_header shell cache-control no-store
if [ -z "$(header shell access-control-allow-origin)" ]; then pass "no Access-Control-Allow-Origin"; else
  fail "no Access-Control-Allow-Origin"
fi
if [ -z "$(header shell x-frame-options)" ]; then pass "no X-Frame-Options (Telegram Web frames the app)"; else
  fail "no X-Frame-Options (Telegram Web frames the app; frame-ancestors is the control)"
fi
if grep -q 'src="https://telegram.org/js/telegram-web-app.js"' "$work/shell.b" 2>/dev/null; then
  pass "the shell loads Telegram's script"
else
  fail "the shell loads Telegram's script (is MINIAPP_STATIC_DIR the built miniapp/dist?)"
fi

script="$(grep -o 'src="/app/assets/[^"]*\.js"' "$work/shell.b" 2>/dev/null | head -n 1 | sed -e 's/^src="//' -e 's/"$//')"
if [ -n "$script" ]; then
  status="$(fetch asset -H 'Accept-Encoding: gzip' "$origin$script")"
  if [ "$status" = 200 ]; then pass "a built file answers 200"; else fail "a built file answers 200 (got $status)"; fi
  expect_header asset cache-control "public, max-age=31536000, immutable"
else
  fail "the shell names a built script under /app/assets/"
fi

# ---------- the API ----------
now="$(date +%s)"
user='%7B%22id%22%3A1%2C%22first_name%22%3A%22Preflight%22%7D'
forged_signature="$(printf 'x%.0s' $(seq 1 86))"
session() {
  fetch "$1" -X POST -H 'Content-Type: application/json' --data "{\"initData\":\"$2\"}" "$origin/api/v1/session"
}
status="$(session forged "auth_date=$now&query_id=preflight&user=$user&signature=$forged_signature&hash=$(printf '0%.0s' $(seq 1 64))")"
if [ "$status" = 401 ] && grep -q '"code":"unauthenticated"' "$work/forged.b"; then
  pass "launch data with a forged signature is refused (401 unauthenticated)"
else
  fail "launch data with a forged signature is refused (got $status)"
fi
if header forged content-type | grep -q '^application/problem+json'; then pass "the refusal is application/problem+json"; else
  fail "the refusal is application/problem+json"
fi
status="$(session unsigned "auth_date=$now&query_id=preflight&user=$user&hash=$(printf '0%.0s' $(seq 1 64))")"
if [ "$status" = 401 ] && grep -q '"code":"client_too_old"' "$work/unsigned.b"; then
  pass "launch data without a signature is refused (the HMAC fallback is off)"
else
  fail "launch data without a signature is refused (got $status)"
fi
status="$(fetch today "$origin/api/v1/today")"
if [ "$status" = 401 ]; then pass "GET /api/v1/today without a session answers 401"; else
  fail "GET /api/v1/today without a session answers 401 (got $status)"
fi

# ---------- the proxy ----------
for path in / /healthz /readyz /metrics; do
  status="$(fetch outside "$origin$path")"
  if [ "$status" = 404 ]; then pass "GET $path at the proxy answers 404"; else fail "GET $path at the proxy answers 404 (got $status)"; fi
done
head -c 70000 /dev/zero | tr '\0' 'x' >"$work/large.json"
status="$(fetch large -X POST -H 'Content-Type: application/json' --data-binary "@$work/large.json" "$origin/api/v1/session")"
if [ "$status" = 413 ]; then pass "a body of 70 kB is refused by the proxy (413)"; else
  fail "a body of 70 kB is refused by the proxy (got $status; the limit is 64 kB)"
fi
if [ "$plain" != none ]; then
  status="$(fetch plain "$plain")"
  location="$(header plain location)"
  if [[ "$status" =~ ^30[178]$ && "$location" == https://* ]]; then pass "$plain redirects to https"; else
    fail "$plain redirects to https (got $status ${location:-without a location})"
  fi
fi

# ---------- the host ----------
if [ "$health" != none ]; then
  status="$(fetch readyz "$health/readyz")"
  if [ "$status" = 200 ]; then pass "GET $health/readyz answers 200"; else fail "GET $health/readyz answers 200 (got $status)"; fi
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "every check passed"
