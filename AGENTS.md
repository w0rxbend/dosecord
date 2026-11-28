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

State of `main` on 2026-11-28 (`d870c74` and the docs after it), after
twelve rounds of parallel tracks were merged and wired together (the
ninth: the new-user review and trends with the share builder, no
migration; the tenth: chat analytics, V23, and image round 4; the
eleventh: image round 5 and the Mini App go-live rehearsal, no
migration; the twelfth: analytics round 6, V26, and the animated mood
blob, V25), plus the fixes of the verifications of 2026-11-14 (V13),
2026-11-15 (V18) and 2026-11-27, and V22 (sealed file ids and export
parameters), and V24 (the order of an answer's messages, 2026-11-28).
The migrations are V1 to V19 and V22 to V26, 24 files
(`scripts/check-frozen-migrations.sh` prints `24 migrations unchanged
(V1 to V19, V22 to V26)`); V20 and V21 must never be added, and the
next is V27. The live database is at V23 (`b1c73da`):
`docs/OPERATIONS.md` section 21 is the owner's deploy of the head, with
V24, V25 and V26 in one start (sections 19 and 20 explain V24 and V26;
sections 8 to 18 the earlier parts). The Mini App
is off on live; `docs/DEPLOY_MINIAPP.md` is the owner's switch to turn
it on.
Appendix B and its status notes are the record; this is the short form.

- Foundation: G0.3 (`d6a033d`), G0.4 (`b6530a3`) and G0.5 (`fb6b317`) are
  `green`. G0.2 is `building`. G0.1 is not performed: `docs/traceability.csv`
  and `scripts/traceability-check.sh` do not exist.
- Telegram (`adapter-telegram`, ADR-019): a hand-written Bot API client behind
  a transport seam, a fake wire, long polling, flood control. `app` selects it
  from the settings. Long polling and the webhook endpoint
  `POST /tg/webhook/{path_secret}` (`TELEGRAM_UPDATES_MODE=webhook`) both
  store each update in `inbound_inbox` (V3, ADR-017) before acknowledging it;
  `InboxProcessor` applies each chat's updates in order (G1.7).
- Images (`report-renderer`, `report-renderer.resvg`, ADR-022): cards are drawn
  from a scene tree through SVG and a resvg child process, each with a text
  equivalent. `app` draws the mood, water and daily summary cards and keeps
  their bytes in `render_assets` for at most one hour and their file ids in
  `telegram_file_cache`; it needs `RESVG_PATH` and `RENDER_FONTS_DIR`, and
  without them every image is sent as its text. The container image has
  resvg 0.49.0 (built from its pinned source) and the font pack, checked by
  `scripts/image-render-check.sh`. Chat images follow the image theme and discreet
  mode set in the Mini App; a discreet personal card goes behind a spoiler.
  The sleep regularity card (IMG-12) is sent from `/sleep`. The blob is
  revision `dg-blob-2`. "Show as image" on the seven-day `/stats` sends the
  week at a glance (IMG-19), on `/today` the daily summary. The seven-day
  `/stats` also sends IMG-20 to IMG-22 (Water, 7 days; Sleep, 7 nights; Mood
  by weekday). With "Animated mood" on (Mini App settings, V25, off by
  default) a mood check-in is answered with the blob moving for 3 s, H.264
  in MP4 encoded in the JVM (`report-renderer.motion`, JCodec), sent with
  `sendAnimation`, the still card as its fallback.
- Tracking and statistics (`core`, `infra`, ADR-024): hydration, mood, sleep
  (episodes, check-ins, goals; no rollup, read from the episodes), daily
  rollups with a worker that drains their queue, the `Stat` type, text
  statistics, one comparison rule, the sleep regularity figures, and the
  consent ledger (`consent_events`, `consent_state`). The tracking repositories and the ledger are groups of
  the one transaction handle (`Tx.tracking`, `Tx.consents`).
- Nutrition (G8.1 to G8.4, ADR-026 Proposed, V4 `meals` and `meal_plans`):
  the `nutrition` consent, a meal journal by type with optional words (plain
  text until G2.4b), the regular-meal view by the user's plan, a nutrition
  rollup of counts, the food day card (IMG-09) and meal nudges. No numbers,
  photos or estimates.
- Month and insights (analytics track, V5): the month's figures with the
  14-day gate, food included; insight rules IR-01, IR-02, IR-06 behind the
  cross-domain consent, IR-07 and IR-11; month pages, mood calendar and
  timeline; weekly and monthly reports sent by the prompt loop, the weekly
  card with a food row. `/stats year` per area and month with the one-year
  card (IMG-30), IR-05 (mood on days every planned meal was logged), and
  the "What's new" note after a release (`/whatsnew`, V26).
- Export (G3.10, G10.4, V6 `export_jobs`, `export_download_tickets`): one ZIP
  of every account-owned table (`ExportCoverage` decides each table), download
  tickets, the file into the chat after two taps, and the record summary PDF
  (DOC-01) with a food section of counts.
- Medication depth (G9.8 to G9.10, G9.12, V8 `medication_inventory`,
  `inventory_events`, `medication_schedules.resume_on`): every N days and
  cycles with presets in the chat and the Mini App builder, a count per
  medication deducted by taken doses, the refill notice with its card
  (IMG-15), "Pause for..." days, and as-needed limits stated as the user's
  own facts.
- Language (G2.22): every catalogue entry, card, API sentence and Mini App
  string is an English and Ukrainian pair, chosen by the account's stored
  locale, else the client's `language_code`, else English; `/language` and
  the Mini App settings switch it. The Ukrainian text has had no
  native-speaker review.
- Encryption at rest (G2.4, G2.4b in part, V10, V11): every Class A column
  that held a value (medication name, alias, instructions, dose snapshots,
  dose notes, meal words, export files, render bytes, stored API answers) is
  sealed with AES-256-GCM under a per-account data key wrapped by `KEK`
  (`infra/crypto`, `core/ports/KeyService`); `SchemaUpgrade` seals old values
  at start. V13 seals outbox payloads and stored replies too (account key, or
  the `transport` service key before the account exists) and the start seals
  and empties the old ones, then vacuums. V19 does the same for the inbox,
  sessions, form runs, callback slots, domain events, reminder titles and
  dose units, and keeps display names empty. Still plaintext: file ids
  and step-up parameters.
- Audit and deletion (G2.16, G2.17, G5.7, V12): `audit_log` is append-only,
  codes only, written on the transaction of each consent, export, step-up,
  session, settings, deletion and key event. `/privacy` deletes one domain
  or the account (`ErasureCoverage` lists every table); account deletion
  destroys the data keys first, writes the purge list (`PURGE_LIST_PATH`)
  and keeps the audit rows under a tombstone. It also deletes the onboarding
  rows of the account's chat (`chat_key`, V13); a domain deletion deletes the
  export files. Every start replays the purge list (`PurgeReplay`).
- Row-level security (G2.3, V14, V17, ADR-021): every account-owned table
  has forced row-level security with four policies; `RlsSuite` fails for a
  new table that neither calls `dosegram_protect_account_table` in its
  migration nor is listed with a reason. `UnitOfWork.transactionAs(account)`
  and `Tx.actAs` run as `dosegram_app` with the claim; `transaction` runs as
  `dosegram_worker` for the cross-account work. Every Mini App route binds to
  the session's account; a chat update binds once its sender is known.
- Imports (G10.5, G10.7, V15): the Dosegram CSV template and the Apple
  Health export (sleep and water, desktop only), read on the device, with a
  preview, duplicates found by a keyed hash, commit and undo, behind the
  `import` consent; the hidden `/import` points to the Mini App. Imported
  entries are labelled in the API and Mini App lists.
- Sleep, food and report depth (G7.2, G9.2, G9.4, V16): bedtime mode, the
  touchpoint cards, "Adjust times", bedtime chips and nap check-ins; meals
  counted the same way everywhere, food on the month pages, the Mini App
  month and the history calendar; the report offer, the adaptive pause, the
  weekly report on Monday 09:00, the monthly pages as one album; the "Counts
  in reports" switch; "Share a card" from the seven-day `/stats` (IMG-17).
- Operations (G0.8, G2.18 and G2.19 in part): `/readyz` and `/metrics` on the
  health listener, one owner alert per hour for dead outbox rows and
  quarantined updates, `scripts/backup.sh`, `restore-drill.sh`,
  `live-smoke.sh`, gitleaks secret scanning and actions pinned by commit.
  `ProductionPathSuite` starts from `/start` on the fake wire and scans
  every column and a `pg_dump` for the canaries it typed.
  `docs/PERFORMANCE.md` has the probes and the measured hot paths.
- Trends and new-user review (2026-11-27): `/stats trends` and "Trends" on
  the 30-day `/stats` (action id 411) with 30 and 90 days and the card
  IMG-23, one source (`StatsQueryService.trends`) for the chat, the card
  and `GET /insights/overview`; the Mini App share builder
  (`GET /report-cards/{kind}/preview`). `NewUserWalkSuite` walks a new
  user's first day on the production assembly in Ukrainian and English;
  `docs/verification/new-user-review-2026-11-27.md` lists the findings,
  fixed and open.
- Chat analytics and image round 4 (2026-11-27): the morning summary
  (`report_daily`, V23, off by default, from `/reminders`), `/stats meds`
  with the per-medication strip (IMG-07, action ids 442, 443), the share
  preview limits, the colour-vision check of every card, generic captions
  under discreet images, and the month in one image (IMG-24) after the
  monthly pages.
- Image round 5 and the Mini App go-live rehearsal (2026-11-27): the dark
  Rye and Beet that keep every domain hue at 3:1 for every reader (R5),
  the week highlights as the second photo of the weekly report (IMG-25),
  `/mood history` with its blob gallery (IMG-26, action id 474);
  `scripts/miniapp-env.sh`, `miniapp-preflight.sh` and
  `miniapp-rehearsal.sh`, the Caddy and nginx files in `docker/proxy/`,
  and `pnpm --dir miniapp run e2e:https`, the browser suite over TLS.
- Consent: `/start` asks the two-stage consent for the one domain the user
  chose before the account exists. Without the `medication` consent no
  medication command, button or API route records or shows anything, and
  withdrawing it pauses every schedule and cancels every open dose in the
  same transaction. `Application.handler` is the one handler composition.
- Chat: besides the medication flows, `/water`, `/mood`, `/sleep`, `/food`,
  `/stats` (with `/stats month`) and `/privacy` with the two-stage consent at
  first use, the hidden `/insights` and `/export`, quick-capture text
  ("250 ml", "mood 4", "slept 23:30 7:10", "lunch: soup"), the mood image as the answer to a
  check-in, and the vessel, daily summary and sleep images on request
  (`core/application/capture`). Typed times, lengths, days, doses and zones
  are read leniently (`core/domain/input`, BOT_UX 15.6).
- API and Mini App (`api`, `miniapp`, ADR-016, ADR-020): session exchange,
  Today read, the medication and schedule registry, water, mood, sleep and
  meal routes, the history calendar, settings, consents, exports and download
  tickets, `GET /insights/overview`, `/insights/month` and
  `/insights/correlations` behind `MINIAPP_ENABLED`, which production accepts only with an https
  `MINI_APP_URL` and an explicit `HTTP_PORT` (ADR-016 addendum).
  Sessions and idempotency records are rows of `miniapp_sessions` and
  `idempotency_keys`. Screens: Today, medications, Log, water, mood, sleep,
  food, Insights (a 7, 30 and 90-day dashboard with the previous period in
  words, month and patterns), reports and export, history and day detail,
  settings with the day profile page, and set-up (S-19). The app records and withdraws
  no consent. Today answers a dose (`POST /occurrences/{id}/actions`, the
  chat's `DoseCommands`, vendor `miniapp`) and Insights shares a card
  through the report queue (`/report-cards`). The container image
  serves the built Mini App, `docs/DEPLOY_MINIAPP.md` is the deploy guide,
  and `pnpm --dir miniapp run e2e` is the browser suite with an axe scan of
  every screen (G3.12).

Every slice these touch is `building`, none is `green`. The owner runs the
bot on real data since 2026-11-15 (`dba2825`, V17). Agents build and test
against the fake wire and hand-written fixtures and make no live sends. The
spikes G1.1 to G1.3, every live smoke and every owner action from OA-2 on
are open.

Useful next, in this order:

1. Owner: a staging bot (OA-4). Then G1.1 (replace the hand-written fixtures
   and error strings with recordings), live smoke LS-1 for G1.6, and G1.3.
2. The image gate with rendering on, now that the Dockerfile has the resvg
   stage, the font pack and the built Mini App (G1.2, G1.9).
3. What G1.7 left open: live smoke LS-2 after G1.8 and OA-6, OA-7; TG-05
   under load; rotation with two header secrets.
4. What the seventh round left open: of G2.3 the `SECURITY DEFINER` claim
   functions for the loops, a fail-closed `transaction`, the chat-keyed
   tables and the unbound idempotency and session stores; of G9.2 the 60-day
   simulation and pre-rendering; of G10.7 keeping a device night next to a
   self-reported one, the device name and G10.6 (OA-12); checkpoints CP2-a, CP10-a, CP10-b; live sends of the album, the
   share card and the report offer.
5. What the fourth round left open: the step-up, `/privacy` button and e2e
   flow of the export (G3.10).
6. What the fifth round left open: the native-speaker review of the
   Ukrainian copy; every N weeks, interval from a fixed start and several day
   groups (G9.8, G9.9); the refill notice against the nudge budget, the
   course-finished message and pause with a date in the Mini App (G9.10,
   G9.12).
7. What the consent and store work of 2026-11-13 left open: the rest of
   onboarding (adult attestation, region, discreet choice, G2.8), the
   `StorageError` port of G2.1, and of G2.4b the file ids, step-up
   parameters, the WAL half of the dump test and the scheduled rotation of
   the inbox key, each schema change as a new versioned migration. ARCHITECTURE section 10.17 lists the
   schema areas and the rules for adding a table; a new table also needs an
   `ErasureCoverage` and an `ExportCoverage` entry.
8. What the sixth round left open: the CI run of G0.8 on a pushed branch;
   the purge-list replay and canary decryption of the restore drill (G2.19);
   the 7-day deletion delay, menu-button reset and `deleteMessage` of
   BUX-DEL-04, the 13-month audit retention (G11.8) and the hash chain
   (G11.6b); the rest of G2.18; dropping the emptied plaintext columns; the
   image gate after the compose change. G2.6 (English catalogue) is eligible
   by dependency and untouched.
9. What the eighth round left open: the contact-sheet review by another
   reviewer and the greyscale check; the unfixed hot spots of
   PERFORMANCE.md; live checks of the dose answer (MA-TODAY-4), the share
   card and the images. On main since the ultracode merge: IMG-20 to IMG-22
   buttons, the refill golden, a medication-only weekly report, the Today
   occurrence sheet for the actions the API already accepts (no Taken-at
   field), the as-needed sheet, one inline mood check-in, insight blocks
   that load near the viewport, and a week-chart tap that opens the day.
10. What the ninth round left open: live sends of the trends message,
    IMG-23 and a builder card; the IMG-23 contact-sheet review and greyscale
    check; a PERFORMANCE.md probe for the share preview; the open findings
    of the new-user review (two image consent cards from two taps, two
    keyboards at once for the goal card and water-plan offer, re-edits of
    every earlier card of a dose, one Ukrainian word for Undo and Cancel, a
    Back with nowhere to go, no default zone, terse `/stats`, no export
    button on `/privacy`, repeat cards not replacing the old one); the old
    plan names in `miniapp/src/mock/reminders.ts`.
11. What the tenth round left open: live sends of the morning summary,
    `/stats meds`, the poster and a discreet caption; a typed time for the
    summary and pre-rendering (VA-PS-08); CI attaching the vision sheets
    (G4.7).
12. What the eleventh round left open: the owner's go-live of the Mini
    App (public host, certificate, BotFather, OA-8) and live check LS-6;
    the share builder offered without `RESVG_PATH` (its preview answers
    404, DEPLOY_MINIAPP.md); live sends of the weekly album with
    highlights and the mood gallery; their contact-sheet review by another
    reviewer; the Mini App palette, which R5 did not change.
13. What the twelfth round left open: live sends of `/stats year`, the
    one-year card (IMG-30), the "What's new" note and the animated mood
    (`sendAnimation` playback on every client); a chat switch for
    "Animated mood" (BOT_UX section 17); the TGS sticker (spike S5); the
    year in the Mini App; an IR-05 pattern card; contact-sheet reviews of
    IMG-30 and the motion by another reviewer; the load flakes of
    `NewUserWalkSuite`, `TelegramLaunchSuite`, `freePort()` and
    `SessionApiSuite` (ROADMAP Appendix B, integration of 2026-11-28),
    and from the verification of 2026-11-28: the medication names in
    plaintext in `rendered_messages.choice_map` (the `/meds` button
    labels; G2.4b, OPERATIONS section 21), the morning summary's text
    counting a late-missed dose on the day it was marked, and the load
    flakes of `WorkerLoopCancelPgSuite` and `ResvgRasteriserSuite`.

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
