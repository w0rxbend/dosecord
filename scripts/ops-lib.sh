# shellcheck shell=bash
# Shared helpers of the operations scripts (backup.sh, restore-drill.sh,
# live-smoke.sh). Sourced, never run.
#
# Database access, in this order:
#   PG_CONTAINER   run the client inside that running PostgreSQL container
#                  (docker exec); PGUSER and PGDATABASE name the role and database
#   otherwise      the client on PATH with the libpq variables PGHOST, PGPORT,
#                  PGUSER, PGPASSWORD (or PGPASSFILE), PGDATABASE
#   no client      the same variables through a throwaway postgres:18-alpine
#                  client container on the host network
# No helper prints a password, a connection string or a row of user data.

ops_pg_image="postgres:18-alpine"

# pg_client TOOL ARGS...: runs pg_dump, pg_restore or psql against the configured database.
pg_client() {
  local tool="$1"
  shift
  if [ -n "${PG_CONTAINER:-}" ]; then
    docker exec -i "$PG_CONTAINER" "$tool" -U "${PGUSER:-dosegram}" -d "${PGDATABASE:-dosegram}" "$@"
  elif command -v "$tool" >/dev/null 2>&1; then
    "$tool" "$@"
  else
    docker run --rm -i --network host \
      -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE \
      "$ops_pg_image" "$tool" "$@"
  fi
}

# psql_at SQL: one query, unaligned, tuples only, fields separated by '|'.
psql_at() {
  pg_client psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -c "$1"
}

# fresh_gnupg_home: an empty GnuPG home that leaves the operator's keyring untouched.
fresh_gnupg_home() {
  local home
  home="$(mktemp -d)"
  chmod 700 "$home"
  printf '%s\n' "$home"
}

# free_port: an unused TCP port on the loopback interface.
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

die() {
  echo "$(basename "$0"): $*" >&2
  exit 1
}
