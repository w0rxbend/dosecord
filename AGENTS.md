# Dosegram agent instructions

## Mission and source of truth

Dosegram is a Telegram-native personal health record keeper: a private-chat
bot for the daily loop and a Telegram Mini App for configuration and
exploration, on one Scala 3 backend with PostgreSQL. It grew out of Dosecord, a
Discord medication-reminder bot; [ADR-014](docs/adr/ADR-014.md) records the
pivot. The delivery order and the only product-slice status table are in
[docs/ROADMAP.md](docs/ROADMAP.md) (Appendix B).

Read, in this order, before changing product code or product documentation:

1. This file.
2. The selected roadmap slice, its dependencies and its milestone gate
   (ROADMAP sections 0.5 and 3, Appendix B).
3. The requirement ids the slice names, in the document that owns them, and
   any ADR the slice names.
4. The source and tests that the slice changes.

Precedence (ROADMAP section 0.1):

| Topic | Winning document |
|---|---|
| Delivery order, slice status | [docs/ROADMAP.md](docs/ROADMAP.md) |
| Product behaviour, defaults, wave and tier of a capability | [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) |
| Module structure, data model, API conventions, security architecture | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Chat flows, copy keys, message operations | [docs/BOT_UX.md](docs/BOT_UX.md) |
| Mini App screens, frontend stack, client budgets | [docs/MINI_APP.md](docs/MINI_APP.md) |
| Visual identity, image catalogue, metric and insight definitions | [docs/VISUALS_AND_ANALYTICS.md](docs/VISUALS_AND_ANALYTICS.md) |
| A recorded decision | The ADR in [docs/adr/](docs/adr/README.md). An accepted ADR is amended by an addendum, never rewritten |

[docs/MEDICATION_REMINDER_UX.md](docs/MEDICATION_REMINDER_UX.md) remains the
detailed medication specification, subordinate to `PRODUCT_SPEC.md`. The
research briefs in `docs/research/` are evidence, not requirements. Everything
under `docs/archive/` is history: the Dosecord roadmap and design keep the
evidence of the inherited slices and the detailed reference for the kept
engine, and they govern nothing.

## Current entry point

State of `main` on 2026-11-13, after two rounds of parallel tracks were
merged and wired together (the second: input, sleep, images, Mini App).
Appendix B and its status notes are the record; this is the short form.

- Foundation: G0.3 (`d6a033d`), G0.4 (`b6530a3`) and G0.5 (`fb6b317`) are
  `green`. G0.2 is `building`. G0.1 is not performed: `docs/traceability.csv`
  and `scripts/traceability-check.sh` do not exist.
- Telegram (`adapter-telegram`, ADR-019): a hand-written Bot API client behind
  a transport seam, a fake wire, long polling, flood control. `app` selects it
  from the settings. No webhook endpoint and no durable inbox (G1.7).
- Images (`report-renderer`, `report-renderer.resvg`, ADR-022): cards are drawn
  from a scene tree through SVG and a resvg child process, each with a text
  equivalent. `app` draws the mood, water and daily summary cards and keeps
  their bytes in `render_assets` for at most one hour and their file ids in
  `telegram_file_cache`; it needs `RESVG_PATH` and `RENDER_FONTS_DIR`, and
  without them every image is sent as its text. The container image has
  resvg and the fonts (built from the pinned 0.49.0 source). Chat images follow the image theme and discreet
  mode set in the Mini App; a discreet personal card goes behind a spoiler.
  The sleep regularity card (IMG-12) is sent from `/sleep`.
- Tracking and statistics (`core`, `infra`, ADR-024): hydration, mood, sleep
  (episodes, check-ins, goals; no rollup, read from the episodes), daily
  rollups with a worker that drains their queue, the `Stat` type, text
  statistics, one comparison rule, the sleep regularity figures, and the
  consent ledger (`consent_events`, `consent_state`). The tracking repositories and the ledger are groups of
  the one transaction handle (`Tx.tracking`, `Tx.consents`).
- Consent: `/start` asks the two-stage consent for the one domain the user
  chose before the account exists. Without the `medication` consent no
  medication command, button or API route records or shows anything, and
  withdrawing it pauses every schedule and cancels every open dose in the
  same transaction. `Application.handler` is the one handler composition.
- Chat: besides the medication flows, `/water`, `/mood`, `/sleep`, `/stats`
  and `/privacy` with the two-stage consent at first use, quick-capture text
  ("250 ml", "mood 4", "slept 23:30 7:10"), the mood image as the answer to a
  check-in, and the vessel, daily summary and sleep images on request
  (`core/application/capture`). Typed times, lengths, days, doses and zones
  are read leniently (`core/domain/input`, BOT_UX 15.6).
- API and Mini App (`api`, `miniapp`, ADR-016, ADR-020): session exchange,
  Today read, the medication and schedule registry, water, mood and sleep
  routes, the history calendar, settings, consents and
  `GET /insights/overview` behind `MINIAPP_ENABLED`, which production accepts only with an https
  `MINI_APP_URL` and an explicit `HTTP_PORT` (ADR-016 addendum).
  Sessions and idempotency records are rows of `miniapp_sessions` and
  `idempotency_keys`. Screens: Today, medications, Log, water, mood, sleep,
  Insights, history and day detail, settings. The app records and withdraws
  no consent; dose actions over HTTP do not exist.

Every slice these touch is `building`, none is `green`. No Telegram bot token
exists: everything is built and tested against the fake wire and hand-written
fixtures, and there are no live sends. The spikes G1.1 to G1.3, every live
smoke and every owner action from OA-2 on are open.

Useful next, in this order:

1. Owner: a staging bot (OA-4). Then G1.1 (replace the hand-written fixtures
   and error strings with recordings), live smoke LS-1 for G1.6, and G1.3.
2. `docker/Dockerfile`: the resvg build stage, the font pack and the built
   Mini App, then the image gate with rendering on (G1.2, G1.9).
3. G1.7: the durable inbox and the webhook endpoint. It also closes the window
   in which a polled update is lost when the process dies.
4. Sleep: the bedtime and morning touchpoints (G7.2), "Adjust times", nap
   check-ins, and the weekly card (G9.1, G9.2), which sleep would join.
5. What the consent and store work of 2026-11-13 left open: the rest of
   onboarding (adult attestation, region, discreet choice, G2.8), deletion
   of a domain's data (G2.17), encryption of the stores of the edges
   (G2.4b), and the `StorageError` port of G2.1, each as a new versioned
   migration. ARCHITECTURE section 10.17 lists the schema areas and the rules
   for adding a table.
6. G0.8 (secret scanning and pinned CI actions) and G2.6 (English catalogue)
   are eligible by dependency and untouched.

## Agent workflow

Use the process in [docs/AGENTIC_DEVELOPMENT.md](docs/AGENTIC_DEVELOPMENT.md).
For every product slice:

1. Take exactly one roadmap slice. State its dependencies and acceptance
   criteria before editing.
2. Check the dependencies and the milestone gate in Appendix B. Where a slice
   needs a live check or an owner action, build everything up to it and record
   the remaining live step; never mark it passed.
3. Make the smallest vertical change that satisfies the slice. Do not bundle
   adjacent cleanup, later roadmap work, or a design change.
4. Write the test first for behaviour. Run the slice's stated verification and
   the standard gate of ROADMAP section 0.6, each command separately, and
   record the executed test count per module. Do not replace the stated
   acceptance criteria with narrower checks, and do not weaken or skip a test
   to get a green run.
5. Owner's standing instruction (2026-11-13): work is committed directly to
   `main`, in small conventional commits, each after a green standard gate. A
   change that cannot be made green is not committed; report the decisive
   output instead. Pushing, live sends, secret access and deployments need the
   owner's separate authorisation.
6. Update Appendix B only when the roadmap's definition of the status is met.

## Boundaries

- Keep `core` and `contracts` vendor-neutral. `scripts/check-vendor-symbols.sh`
  is a release gate, not an optional convention; it also keeps the retired
  vendor and product names out of code and configuration.
- The Python prototype, its broker-based compose stack, the Discord adapter
  and the Zulip and Matrix profiles are deleted and must not return. Treat
  them as git history, not as a pattern to extend.
- Never log credentials, the bot token, `initData`, request URLs, message
  bodies, medication names, photos, or notes of any kind (dose, food, mood,
  sleep). Do not collect passwords in chat. Do not read or print `.env` or any
  secret file.
- Keep all copy and insights non-clinical and non-causal. The product records
  what the user did and reminds the user of what the user configured; it must
  not prescribe, advise a dose, interpret a value or compute a health score.
- Every generated image has a text equivalent.
- The schema baseline is frozen (ADR-002 addendum of 2026-11-13): the owner's
  live database holds real data. Never edit a committed migration,
  `V1__baseline.sql` included, and never run `flyway repair`. Every schema
  change is a new versioned migration (`V2`, `V3` and so on), listed in
  `scripts/frozen-migrations.sha256` in the commit that adds it;
  `scripts/check-frozen-migrations.sh` enforces this in the standard gate.
  ARCHITECTURE section 10.17 has the rules. Never drop, truncate or recreate
  a database that holds the owner's data.
- Do not add a product slice absent from the roadmap. A user-directed
  repository-governance change may be separate, but must not alter the
  roadmap's product status.

## Handoff standard

A handoff must name the roadmap slice, files changed, verification performed,
and the next dependency-unblocked slice. Leave the worktree clean apart from
the intentional change. Do not claim completion without evidence for every
acceptance criterion.
