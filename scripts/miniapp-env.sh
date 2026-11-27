# shellcheck shell=bash
# The Mini App's switch for a start script (docs/DEPLOY_MINIAPP.md section 2). Sourced, never run:
#   . "$WT/scripts/miniapp-env.sh"
# With MINI_APP_URL set it turns the Mini App on: MINIAPP_ENABLED=true, an explicit HTTP_PORT (default 18082) and
# HTTP_ADDRESS (default 127.0.0.1), and MINIAPP_STATIC_DIR (default: miniapp/dist of the release this file belongs
# to, which must be built). Without MINI_APP_URL it turns the Mini App off, so a start without the variable is the
# rollback. The address must be https://<host>/app/; anything else returns 1 before the application starts.

miniapp_env() {
  local here dist
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  if [ -z "${MINI_APP_URL:-}" ]; then
    unset MINI_APP_URL MINIAPP_STATIC_DIR
    export MINIAPP_ENABLED=false
    echo "miniapp: off (MINI_APP_URL is not set)" >&2
    return 0
  fi
  if ! [[ "$MINI_APP_URL" =~ ^https://[^/]+/app/$ ]]; then
    echo "miniapp: MINI_APP_URL must be https://<host>/app/ exactly" >&2
    return 1
  fi
  dist="${MINIAPP_STATIC_DIR:-$here/miniapp/dist}"
  if [ ! -f "$dist/index.html" ]; then
    echo "miniapp: $dist/index.html is missing; build it with: pnpm --dir $here/miniapp install --frozen-lockfile && pnpm --dir $here/miniapp run build" >&2
    return 1
  fi
  export MINIAPP_ENABLED=true MINI_APP_URL MINIAPP_STATIC_DIR="$dist"
  export HTTP_PORT="${HTTP_PORT:-18082}" HTTP_ADDRESS="${HTTP_ADDRESS:-127.0.0.1}"
  echo "miniapp: on at $MINI_APP_URL, public listener $HTTP_ADDRESS:$HTTP_PORT" >&2
}

miniapp_env
