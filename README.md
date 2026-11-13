# Dosegram

Dosegram is a Telegram-native personal health record keeper: a private-chat bot
for the daily loop (medication reminders answered in one tap, water, mood,
sleep, food) and a Telegram Mini App for configuration and exploration, with
server-rendered image cards and honest statistics. It records what the user
did and reminds the user of what the user configured. It never prescribes,
advises a dose, interprets a value or computes a health score.

The repository was Dosecord, a Discord medication-reminder bot;
[ADR-014](docs/adr/ADR-014.md) records the pivot.

## State of `main`

| Part | State |
|---|---|
| Engine (Scala 3, JDK 25, Mill, PostgreSQL 18) | Runs from the executable: materialiser, reminder loop, outbox dispatcher, session sweeper, `/healthz` |
| Console adapter | Works; it carries the simulation and local use |
| Telegram adapter | Not built. No bot token exists; all work runs against a fake wire and recorded fixtures |
| Mini App (`miniapp/`, Svelte 5) | Scaffold with first screens on a fake `TelegramPort` and a mock backend. No API behind it yet |
| Image cards, analytics, API | Specified, not built |

Slice status is in [docs/ROADMAP.md](docs/ROADMAP.md) Appendix B and nowhere
else.

## Run it

Needs Docker, or JDK 25 and a PostgreSQL 18. See [QUICKSTART.md](QUICKSTART.md).

```sh
docker compose -f docker/compose.yml up -d --build --wait
curl -fsS http://localhost:28080/healthz
docker compose -f docker/compose.yml down -v
```

## Documents

| Read | For |
|---|---|
| [AGENTS.md](AGENTS.md) | Rules for agents and contributors, precedence of the documents, current entry point |
| [docs/README.md](docs/README.md) | The plan in one file: what changes, reading order, open questions, owner actions |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Delivery order, slices, gates, status |
| [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md), [docs/BOT_UX.md](docs/BOT_UX.md), [docs/MINI_APP.md](docs/MINI_APP.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/VISUALS_AND_ANALYTICS.md](docs/VISUALS_AND_ANALYTICS.md) | The specification |
| [docs/adr/](docs/adr/README.md) | Decision records |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Build, test and gate commands |
| [docs/archive/](docs/archive/) | The Dosecord roadmap, design and UX plan. History only |
