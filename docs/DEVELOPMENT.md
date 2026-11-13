# Development

Dosegram is built in thin, verified slices. The process is in
[AGENTS.md](../AGENTS.md) and [AGENTIC_DEVELOPMENT.md](AGENTIC_DEVELOPMENT.md);
[ROADMAP.md](ROADMAP.md) is the only delivery order and status source, and its
section 0.6 owns the gate definitions that this page repeats. Running the
product is described in [QUICKSTART.md](../QUICKSTART.md).

## Toolchain

| Tool | Version | Used for |
|---|---|---|
| JDK | 25 | Everything under Mill |
| Mill | `.mill-version`, fetched by `./mill` | Build, format, lint, tests |
| Docker | any current | Testcontainers in `infra` and `app` tests, Flyway validation, the image gate |
| Node, pnpm | Node 22 or newer; pnpm with lockfile version 9 | `miniapp/` only |

## Standard gate

Run before every commit, each command separately. A multi-selector Mill
invocation once applied a filter and executed zero tests, so record the
executed test count per module and compare it with the previous run.

```sh
./mill __.checkFormat
./mill __.fix --check
./mill __.compile
./mill __.test
./mill app.test.runMain dosegram.app.envExampleCheck .env.example
scripts/flyway-validate.sh
scripts/check-vendor-symbols.sh
scripts/check-sql-now.sh
scripts/doc-lint.sh
scripts/test-doc-lint.sh
scripts/license-gate.sh
git diff --check
```

`scripts/flyway-validate.sh` needs a PostgreSQL 18 and migrates it. Use a
throwaway one on a free port, not a database you care about:

```sh
docker run -d --rm --name dosegram-flyway -e POSTGRES_USER=dosegram \
  -e POSTGRES_PASSWORD=dosegram -e POSTGRES_DB=dosegram -p 55432:5432 postgres:18-alpine
PGPORT=55432 scripts/flyway-validate.sh
docker rm -f dosegram-flyway
```

The suites in `infra` and `app` start their own PostgreSQL through
Testcontainers and are slow under parallel load; rerun before concluding that
a failure is real. `./mill app.test.runMain` fails now and then with
"Subprocess failed" after printing a correct result (issue #9); rerun it.

## Focused test

```sh
./mill --no-server -j 1 <module>.test
```

`<module>` is one of `contracts`, `core`, `infra`, `adapter-console`, `app`,
`tests.conformance`, `tests.simulation`.

## Frontend gate

For any change under `miniapp/`. CI job `miniapp` runs the same commands.

```sh
pnpm --dir miniapp install --frozen-lockfile
pnpm --dir miniapp run check   # svelte-check, then scripts/lint-source.mjs
pnpm --dir miniapp run test    # component tests against the fake TelegramPort
pnpm --dir miniapp run build   # vite build, then the bundle budget
```

`api:check` and `e2e` of ROADMAP section 0.6 do not exist yet; slices G3.1 and
G3.12 create them.

## Image gate

When a change touches the Dockerfile, the compose file, `.dockerignore`,
start-up, settings or memory use.

```sh
docker build -f docker/Dockerfile -t dosegram:ci .
docker compose -f docker/compose.yml up -d --build --wait
curl -fsS http://localhost:28080/healthz
scripts/rss-check.sh 200
docker compose -f docker/compose.yml down -v
```

## Generated files

`.env.example` is generated from the settings and diff-checked in CI:

```sh
./mill app.test.runMain dosegram.app.envExampleGenerate .env.example
```

## Rules that hold everywhere

- `core` and `contracts` stay vendor-neutral (`scripts/check-vendor-symbols.sh`).
- SQL `now()` appears only in `DEFAULT` clauses; queries bind the clock
  (`scripts/check-sql-now.sh`).
- No guidance document teaches a retired command, setting or module
  (`scripts/doc-lint.sh`, manifest `docs/doc-lint.txt`).
- No credentials, message bodies, medication names or notes in logs, fixtures
  or commit text.
