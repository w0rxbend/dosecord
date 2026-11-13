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
./mill api.test.runMain dosegram.api.openApiCheck api/openapi.json
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

`<module>` is one of `contracts`, `core`, `infra`, `adapter-console`, `api`,
`app`, `tests.conformance`, `tests.simulation`.

## Frontend gate

For any change under `miniapp/`. CI job `miniapp` runs the same commands.

```sh
pnpm --dir miniapp install --frozen-lockfile
pnpm --dir miniapp run check   # svelte-check, then scripts/lint-source.mjs
pnpm --dir miniapp run test    # component tests against the fake TelegramPort
pnpm --dir miniapp run build   # vite build, then the bundle budget
pnpm --dir miniapp run api:check  # client types must equal those of api/openapi.json
```

`e2e` of ROADMAP section 0.6 does not exist yet; slice G3.12 creates it.

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

`api/openapi.json` is generated from the Tapir definitions of the `api` module,
and `miniapp/src/api/schema.d.ts` from that document
([ADR-020](adr/ADR-020.md)). After a change to a route or a document type, run
both, in this order:

```sh
./mill api.test.runMain dosegram.api.openApiGenerate api/openapi.json
pnpm --dir miniapp run api:generate
```

## Mini App and API on a developer machine

The Mini App surface is off by default and start-up refuses it in production
(ROADMAP G1.12). To serve the built app and the API from the application, build
the app with `pnpm --dir miniapp run build` and add these variables to the ones
of [QUICKSTART.md](../QUICKSTART.md):

| Variable | Value |
|---|---|
| `ENVIRONMENT` | `development` |
| `MINIAPP_ENABLED` | `true` |
| `MINIAPP_STATIC_DIR` | `miniapp/dist` |
| `MINIAPP_HMAC_FALLBACK` | `true` |
| `TELEGRAM_TOKEN` | A development value, never the token of a real bot |

`/app/` and `/api/v1/` are then on `HTTP_PORT` (default 8081). Outside Telegram
the app shows the gate "Dosegram runs inside Telegram"; `miniapp/README.md`
describes how the development server signs its own launch data instead.

## Status images on a developer machine

The chat sends the mood, water and daily summary cards as photos only when the
application can draw them (ADR-022). It needs the resvg binary, version 0.49.0,
and the pinned font pack of this repository:

| Variable | Value |
|---|---|
| `RESVG_PATH` | The resvg binary, for example the output of `command -v resvg` |
| `RENDER_FONTS_DIR` | `report-renderer/fonts` |

Without the two variables every image is sent as its text equivalent, which is
also what the container image does today: it has neither resvg nor the fonts.
`./mill report-renderer.resvg.test.contactSheet` writes every card as a PNG to
`out/report-renderer/preview`.

## Rules that hold everywhere

- `core` and `contracts` stay vendor-neutral (`scripts/check-vendor-symbols.sh`).
- SQL `now()` appears only in `DEFAULT` clauses; queries bind the clock
  (`scripts/check-sql-now.sh`).
- No guidance document teaches a retired command, setting or module
  (`scripts/doc-lint.sh`, manifest `docs/doc-lint.txt`).
- No credentials, message bodies, medication names or notes in logs, fixtures
  or commit text.
