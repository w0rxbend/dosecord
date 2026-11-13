# Dosegram quick start

What runs today is the engine with the console adapter. There is no Telegram
adapter yet and no bot token is needed. State and plan:
[README.md](README.md), [docs/ROADMAP.md](docs/ROADMAP.md).

## With Docker

```sh
docker compose -f docker/compose.yml up -d --build --wait   # PostgreSQL 18 and the app
curl -fsS http://localhost:28080/healthz                     # prints ok
docker compose -f docker/compose.yml down -v
```

Compose publishes PostgreSQL on host port 5432 and the app on 28080.

## From source

Needs JDK 25 and a reachable PostgreSQL 18. `./mill` downloads the build tool.

1. Build the executable jar:

   ```sh
   ./mill app.assembly
   ```

2. Set the configuration. [.env.example](.env.example) lists every variable;
   the process reads its environment and does not load a `.env` file itself.
   The minimum:

   ```sh
   export ENABLED_ADAPTERS=console CONSOLE_USER_ID=owner
   export DATABASE_URL='jdbc:postgresql://localhost:5432/dosegram?user=dosegram&password=dosegram'
   export CALLBACK_KEYS='0:MDEyMzQ1Njc4OWFiY2RlZg'   # the example key; never a real one
   ```

3. Apply the schema, then start:

   ```sh
   java -jar out/app/assembly.dest/out.jar migrate
   java -jar out/app/assembly.dest/out.jar run          # or: run --role gateway|worker|all
   ```

   `run` starts the materialiser, the reminder loop, the outbox dispatcher and
   the session sweeper, reads console commands from standard input, and serves
   `/healthz` on `HEALTH_PORT` (default 8080).

## Mini App in a browser

Needs Node 22 or newer and pnpm. No Telegram client and no backend are needed;
the page uses a fake `TelegramPort` and synthetic data.

```sh
pnpm --dir miniapp install --frozen-lockfile
pnpm --dir miniapp run dev     # http://localhost:5173/app/
```

## Before you change anything

Read [AGENTS.md](AGENTS.md), then
[docs/AGENTIC_DEVELOPMENT.md](docs/AGENTIC_DEVELOPMENT.md), then the slice you
take in [docs/ROADMAP.md](docs/ROADMAP.md). The gate commands are in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
