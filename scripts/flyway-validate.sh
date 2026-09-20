#!/usr/bin/env bash
# flyway validate gate (ROADMAP M0.4): runs `flyway migrate` then
# `flyway validate` against a Postgres 18 service using the Flyway CLI image
# pinned to the version in docs/DESIGN.md section 2. The migrations are the
# plain-SQL files of infra/src/main/resources/db/migration (ADR-002).
#
# Expects a reachable Postgres (CI provides a postgres:18-alpine service;
# locally: docker compose -f docker/compose.yml up -d). Connection settings
# are overridable via PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE.
set -euo pipefail
cd "$(dirname "$0")/.."

image=flyway/flyway:13.6.0-alpine
url="jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/${PGDATABASE:-dosecord}"

run_flyway() {
  docker run --rm --network host \
    -v "$PWD/infra/src/main/resources/db/migration:/flyway/sql:ro" \
    "$image" \
    -url="$url" \
    -user="${PGUSER:-dosecord}" \
    -password="${PGPASSWORD:-dosecord}" \
    -connectRetries=60 \
    "$@"
}

run_flyway migrate
run_flyway validate
echo "flyway-validate: migrate + validate green against $url"
