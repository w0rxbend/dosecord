#!/usr/bin/env bash
# The local rehearsal of docs/DEPLOY_MINIAPP.md, with nothing public and no Telegram: the production assembly
# (ENVIRONMENT=production, the console adapter in place of the token) on a throwaway PostgreSQL, switched on by
# scripts/miniapp-env.sh exactly as the live start script does, behind each proxy of docker/proxy/ in turn (Caddy with
# its local authority, nginx with a self-signed certificate, both on free loopback ports), each checked by
# scripts/miniapp-preflight.sh. Then the rollback: a start without MINI_APP_URL opens no public listener. The
# browser half is `pnpm --dir miniapp run e2e:https`.
#
# Usage: scripts/miniapp-rehearsal.sh   (after ./mill app.assembly and pnpm --dir miniapp run build)
# Needs Docker, curl, openssl and python3. Never touches a container, volume or database it did not start.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/ops-lib.sh disable=SC1091
. "$repo/scripts/ops-lib.sh"

jar="$repo/out/app/assembly.dest/out.jar"
[ -f "$jar" ] || die "no $jar: run ./mill app.assembly first"
[ -f "$repo/miniapp/dist/index.html" ] || die "no miniapp/dist: run pnpm --dir miniapp run build first"

work="$(mktemp -d)"
pg="dosegram-rehearsal-pg-$$"
project="dosegram-rehearsal-$$"
compose_file="$repo/docker/proxy/rehearsal.compose.yml"
pg_port="$(free_port)"
app_port="$(free_port)"
health_port="$(free_port)"
https_port="$(free_port)"
redirect_port="$(free_port)"
app_pid=""
export MINIAPP_UPSTREAM="127.0.0.1:$app_port" REHEARSAL_HTTPS_PORT="$https_port" REHEARSAL_HTTP_PORT="$redirect_port"
export REHEARSAL_NGINX_TEMPLATE="$work/nginx.conf.template" REHEARSAL_CERT_DIR="$work/cert"

proxy() { docker compose -p "$project" -f "$compose_file" --profile caddy --profile nginx "$@"; }
stop_app() {
  [ -n "$app_pid" ] || return 0
  kill -TERM "$app_pid" 2>/dev/null || true
  wait "$app_pid" 2>/dev/null || true
  app_pid=""
}
cleanup() {
  stop_app
  proxy down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -f "$pg" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }
wait_for() {
  local what="$1"
  shift
  for _ in $(seq 1 90); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  fail "$what"
  return 1
}

docker run -d --rm --name "$pg" -e POSTGRES_USER=dosegram -e POSTGRES_PASSWORD=dosegram -e POSTGRES_DB=dosegram \
  --tmpfs /var/lib/postgresql:rw -p "127.0.0.1:$pg_port:5432" "$ops_pg_image" >/dev/null
wait_for "postgres is ready" docker exec "$pg" pg_isready -h 127.0.0.1 -U dosegram -d dosegram
sleep 1

# The settings of the live start script, with throwaway keys; the token and its adapter are replaced by the console.
umask 077
printf 'rehearsal:%s\n' "$(openssl rand -base64 32)" >"$work/kek"
umask 022
export ENVIRONMENT=production ENABLED_ADAPTERS=console CONSOLE_USER_ID=owner LOG_FORMAT=plain
export DATABASE_URL="jdbc:postgresql://127.0.0.1:$pg_port/dosegram?user=dosegram&password=dosegram"
callback_key="$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=')"
export CALLBACK_KEYS="0:$callback_key" KEK_FILE="$work/kek"
export PURGE_LIST_PATH="$work/purge-list" HEALTH_ADDRESS=127.0.0.1 HEALTH_PORT="$health_port"
export TELEGRAM_BOT_ID=7000000001
unset MINI_APP_URL MINIAPP_ENABLED MINIAPP_STATIC_DIR HTTP_PORT HTTP_ADDRESS MINIAPP_INITDATA_PUBLIC_KEY
# The console adapter reads standard input; a pipe nobody writes to keeps it open.
mkfifo "$work/stdin"
exec 3<>"$work/stdin"

# start_app LOG [MINI_APP_URL]: starts the assembly as the live start script does, through scripts/miniapp-env.sh.
start_app() {
  local log="$1" url="${2:-}"
  (
    if [ -n "$url" ]; then export MINI_APP_URL="$url" HTTP_PORT="$app_port"; fi
    # shellcheck source=scripts/miniapp-env.sh
    . "$repo/scripts/miniapp-env.sh" || exit 1
    exec java -jar "$jar" run
  ) <"$work/stdin" >"$log" 2>&1 &
  app_pid=$!
}

# ---------- 1. on ----------
url="https://localhost:$https_port/app/"
start_app "$work/on.log" "$url"
wait_for "the application answers /readyz" curl -fsS "http://127.0.0.1:$health_port/readyz" || { cat "$work/on.log"; exit 1; }
if grep -q "miniapp: on at $url, public listener 127.0.0.1:$app_port" "$work/on.log"; then
  pass "scripts/miniapp-env.sh turned the Mini App on"
else
  fail "scripts/miniapp-env.sh turned the Mini App on"
fi
if grep -q "Mini App and API listening on 127.0.0.1:$app_port" "$work/on.log"; then pass "the public listener is open"; else
  fail "the public listener is open"
fi

# ---------- 2. Caddy ----------
proxy up -d caddy >/dev/null 2>&1
wait_for "Caddy wrote its local authority" proxy exec -T caddy test -s /data/caddy/pki/authorities/local/root.crt
proxy exec -T caddy cat /data/caddy/pki/authorities/local/root.crt >"$work/caddy-root.crt"
wait_for "Caddy answers on https" curl -fsS --cacert "$work/caddy-root.crt" "$url"
echo "--- preflight through Caddy"
"$repo/scripts/miniapp-preflight.sh" "$url" --cacert "$work/caddy-root.crt" \
  --http "http://localhost:$redirect_port/app/" --health "http://127.0.0.1:$health_port" || fail "preflight through Caddy"
echo "info HEAD $url answers $(curl -s -o /dev/null -I -w '%{http_code}' --cacert "$work/caddy-root.crt" "$url")"
proxy rm -sf caddy >/dev/null 2>&1

# ---------- 3. nginx ----------
mkdir -p "$work/cert"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost -addext subjectAltName=DNS:localhost \
  -keyout "$work/cert/privkey.pem" -out "$work/cert/fullchain.pem" 2>/dev/null
chmod 644 "$work/cert/privkey.pem"
# The template as committed, with its listen lines moved to the rehearsal's loopback ports.
sed -e "s/listen 80;/listen 127.0.0.1:$redirect_port;/" -e "s/listen 443 ssl;/listen 127.0.0.1:$https_port ssl;/" \
  -e '/listen \[::\]/d' "$repo/docker/proxy/nginx.conf.template" >"$work/nginx.conf.template"
proxy up -d nginx >/dev/null 2>&1
wait_for "nginx answers on https" curl -fsS --cacert "$work/cert/fullchain.pem" "$url"
echo "--- preflight through nginx"
"$repo/scripts/miniapp-preflight.sh" "$url" --cacert "$work/cert/fullchain.pem" \
  --http "http://localhost:$redirect_port/app/" --health "http://127.0.0.1:$health_port" || fail "preflight through nginx"
proxy rm -sf nginx >/dev/null 2>&1

# ---------- 3b. the preflight catches a proxy that adds its own headers ----------
sed -e 's/access_log off;/access_log off; add_header Content-Security-Policy "default-src *"; add_header X-Frame-Options DENY;/' \
  -i "$work/nginx.conf.template"
proxy up -d nginx >/dev/null 2>&1
wait_for "nginx answers on https" curl -fsS --cacert "$work/cert/fullchain.pem" "$url"
"$repo/scripts/miniapp-preflight.sh" "$url" --cacert "$work/cert/fullchain.pem" --http none --health none \
  >"$work/caught.txt" || true
if grep -q '^FAIL one Content-Security-Policy' "$work/caught.txt" && grep -q '^FAIL no X-Frame-Options' "$work/caught.txt" &&
  grep -q 'check(s) failed$' "$work/caught.txt"; then
  pass "the preflight fails a proxy that adds a second policy and X-Frame-Options"
else
  fail "the preflight fails a proxy that adds a second policy and X-Frame-Options"
fi
proxy rm -sf nginx >/dev/null 2>&1

# ---------- 4. the log ----------
if grep -qE 'auth_date|initData|/api/v1|signature=' "$work/on.log"; then fail "the log holds no launch data or request path"; else
  pass "the log holds no launch data or request path"
fi

# ---------- 5. rollback ----------
stop_app
if grep -q "shutdown: pool closed" "$work/on.log"; then pass "SIGTERM stopped it cleanly"; else fail "SIGTERM stopped it cleanly"; fi
start_app "$work/off.log"
wait_for "the rollback start answers /readyz" curl -fsS "http://127.0.0.1:$health_port/readyz" || { cat "$work/off.log"; exit 1; }
if grep -q "miniapp: off" "$work/off.log" && ! grep -q "Mini App and API listening" "$work/off.log"; then
  pass "a start without MINI_APP_URL opens no public listener"
else
  fail "a start without MINI_APP_URL opens no public listener"
fi
if curl -s --max-time 5 -o /dev/null "http://127.0.0.1:$app_port/app/"; then fail "port $app_port refuses connections"; else
  pass "port $app_port refuses connections"
fi
stop_app

# ---------- 6. a wrong address stops the start ----------
start_app "$work/wrong.log" "http://localhost:$https_port/app/"
wait "$app_pid" 2>/dev/null && status=0 || status=$?
app_pid=""
if [ "$status" -ne 0 ] && grep -q "MINI_APP_URL must be https://<host>/app/ exactly" "$work/wrong.log"; then
  pass "an http MINI_APP_URL stops the start before the application runs"
else
  fail "an http MINI_APP_URL stops the start before the application runs"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures rehearsal check(s) failed"
  exit 1
fi
echo "rehearsal passed"
