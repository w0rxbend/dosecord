# Research brief 01: repository reuse inventory for the dosegram pivot

Status: research input, not a decision record. Prepared 2026-11-12.
Scope: the repository `w0rxbend/dosecord` at commit `88772f0` (branch `docs/dosegram-revamp-plan`, identical to `main` at the time of reading).
Audience: the agents that design the dosegram architecture, roadmap and ADRs.

## 0. How this brief was produced

1. Read `AGENTS.md`, `docs/DESIGN.md` (634 lines), `docs/ROADMAP.md` (620 lines), all thirteen ADRs and `docs/adr/README.md`, the five spike notes, `docs/verification/m2.2-implementation.md`, the headings and key sections of the two design tracks, `docs/BOT_UX_PLAN.md`, `docs/MEDICATION_REMINDER_UX.md`, `docs/AGENTIC_DEVELOPMENT.md`, `build.mill`, the CI workflow, the Dockerfile, the compose file, every gate script and the Flyway baseline.
2. Read the full `contracts` module, the Discord adapter, the composition root, and the load-bearing `core` files (mediator, capability profiles, action registry, ack policy, markup, token buckets, flow model, ports). The remaining source files were classified from their headers, signatures and line counts.
3. Counted names with `git grep` over tracked files only. All counts are case-insensitive occurrence counts unless stated otherwise.
4. Checked eleven Telegram platform statements against the public Bot API reference and the Bots FAQ on 2026-11-12 (section 12). Every other platform statement in this brief is marked ASSUMPTION.

Not done: no Mill, Docker or test command was executed in this run (the run may write only under `docs/dosegram/`, and a build writes `out/`). Test counts and coverage figures are quoted from evidence files checked into the repository. The `architecture-review` skill was not applicable because the Ontoly CLI is not installed. The `worxbend-repository-context` profile was consulted; it classifies the repository as `scala-wellbeing-system` (snapshot `cc14d7bf1cd4`) and warns that the README is stale against the real code, which this brief confirms.

## 1. Bottom line

1. The repository is a working Scala 3.8.4 / Mill 1.1.9 / Ox 1.0.6 / PostgreSQL 18 code base of 256 Scala files and 35,374 lines (16,046 main, 19,328 test) with 1,074 executed tests recorded on 2026-10-15. About 85 percent of the main code is independent of Discord at the symbol level. The pivot does not justify a rewrite.
2. The medication engine is the most valuable asset and is reusable unchanged: the pure occurrence state machine, the DST-safe materialiser, schedule revisions, the reminder loop, the outbox dispatcher, catch-up and digest, adherence mathematics, and the 30-day simulation.
3. `core` and `contracts` are vendor-neutral only in the sense that they import no vendor SDK. Conceptually they encode Discord: the interaction handle models Discord's defer and modal lifecycle, form limits are Discord modal caps, command specifications are Discord slash options, and the capability model has no media, no web-app button, no reply keyboard and no deep-link payload. A Telegram-native product needs a contract revision, not a new adapter alone.
4. `adapter-discord` (1,132 main lines, 1,763 test lines) is retired as code. Seven of its design ideas transfer directly to a Telegram adapter (section 5).
5. The executable does not start the reminder loop, materialiser, outbox dispatcher or session sweeper. `app/src/dosecord/app/Main.scala` starts only `/healthz` and the adapters. Roadmap slice M2.2b (status `todo`) owns this gap. It blocks any product, on any chat platform, and should be the first engineering slice after the rename.
6. The schema already contains tables for mood, habits, habit check-ins and generic reminders. Only `mood_checkins` is written by code. There are no tables for water, food, sleep, media assets, report requests or Mini App sessions.
7. Image generation is already designed on paper (DESIGN section 15, ADR-005 and ADR-009 addenda, roadmap slices M3.4a to M3.4d) but nothing is built: no attachment model, no renderer module, no asset store.
8. Seven of the thirteen accepted ADRs are contradicted in whole or in part by a Telegram-only product with a Mini App (section 10). ADR-010 (Matrix) is contradicted entirely.
9. The rename touches 1,677 occurrences of `dosecord` in 290 files and 946 occurrences of `discord` in 84 files (section 9). 553 of the Discord occurrences disappear by deleting one module.
10. No Git tag exists and the roadmap rule says the Flyway baseline `V1` is edited in place until tag `0.1.0`. The schema can therefore be reshaped and renamed without a migration chain.

## 2. Repository state

| Fact | Value | Source |
|---|---|---|
| Remote | `git@github.com:w0rxbend/dosecord.git` | `git remote -v` |
| Commits | 100, from 2026-05-24 to 2026-10-15 | `git rev-list --count HEAD` |
| Tags | none | `git tag` |
| Tracked files | 380 | `git ls-files` |
| Scala files | 256 files, 35,374 lines | `git ls-files '*.scala'` |
| Last merged slice work | `88772f0` M2.2a recovery (#12); `1f4ee9c` M2.2 checkpoint | `git log` |
| Slice status | M0.0 to M2.1 and UX-00 `green`; M2.2 and M2.2a `in-progress`; everything else `todo` | ROADMAP Appendix B |
| Recorded test run | 86 suites, 1,074 tests, 0 failed, 0 ignored | `docs/spikes/m2-2a-recovery.md` |
| Recorded coverage | `core.domain` 97.06 percent, `core.chat` 79.31 percent (gate threshold 85 percent, gate not armed) | same file |
| Recorded idle RSS | 128,688 kB to 143,252 kB against a 200 MB gate | same file and `docs/verification/m2.2-implementation.md` |
| Local `.env` | not present in this checkout | `ls -la .env` |

Inconsistencies found between documents and code:

1. `AGENTS.md` "Current entry point" says the next eligible slice is M2.2. Appendix B and the Git log show M2.2 and M2.2a already integrated as technical checkpoints.
2. `README.md`, `QUICKSTART.md` and `docs/DEVELOPMENT.md` say there is no supported application command. `app` has a working `run` and `migrate` command, a Docker image and a compose file.
3. The two design-track files have swapped contents: `docs/design-tracks/unified-chat-api.md` contains "Track B: Scheduling, Reminder Delivery and Intake Recording" and `docs/design-tracks/reminder-reliability.md` contains "Track A: Unified Chat API and Mediator". Each file's first line describes the other file.
4. DESIGN section 2 lists sttp, TelegramBots, Trixnity, Bouncy Castle and the OpenTelemetry agent. None of them is a dependency in `build.mill`.
5. `.gitignore` still carries about 40 lines of Python and Poetry patterns from the deleted prototype.

## 3. Module map

Module dependencies are the Mill module graph in `build.mill`. All modules share `-Werror -Wunused:all -deprecation`, Scala 3.8.4 and `jvmVersion = "temurin:25"`.

| Module | Depends on | External libraries | Main files / lines | Test files / lines | Recorded executed tests |
|---|---|---|---|---|---|
| `contracts` | none | upickle 4.4.3 | 11 / 921 | 5 / 769 | 71 |
| `core` | `contracts` | Ox 1.0.6, scoverage 2.5.2 | 81 / 10,051 | 50 / 7,982 plus 45 resource files | 756 |
| `infra` | `core`, `contracts` | pgjdbc 42.7.13, HikariCP 7.1.0, Flyway 13.6.0, scribe 3.19.0, micrometer 1.17.1 | 30 Scala / 2,227 plus 1 SQL / 225 | 28 / 5,612 | 123 |
| `adapter-console` | `core`, `contracts` | none | 1 / 133 | 7 / 1,573 plus 2 goldens | 15 |
| `adapter-discord` | `core`, `contracts` | JDA 6.6.0 | 8 / 1,132 | 9 / 1,763 | 64 |
| `app` | all of the above | Tapir netty-server-sync 1.13.31 | 5 / 286 | 6 / 179 | 9 |
| `tests.conformance` | `core`, `contracts` | none | 6 / 1,296 plus 14 JSON scenarios | 3 / 303 | 5 |
| `tests.simulation` | `core`, `contracts`, `infra`, `adapter-console` | testcontainers-scala 0.44.1 | 0 | 6 / 1,147 plus 2 goldens | 1 |

The executed counts are the per-task results recorded in `docs/verification/m2.2-implementation.md` (total 1,044). The later M2.2a run recorded 1,074 in 86 suites without a per-module split. Golden-driven suites generate many test cases from one definition, so static `test(...)` counts (574 in total) understate the executed numbers.

### 3.1 `contracts` (921 lines)

Pure data and traits with upickle `ReadWriter` derivation. No I/O.

| File | Lines | Content |
|---|---|---|
| `Chat.scala` | 304 | `PlatformIdentity`, `ChatRef`, `MessageHandle`, `Principal`, `Inbound` (8 cases), `InboundEvent`, `InteractionHandle`, `RichText` AST (`Inline`, `Node`), `Choice`, `ChoiceSet`, `Field`, `Form`, `Notice`, `Block`, `OutboundMessage`, `CapabilityProfile` (21 fields), `Reply` |
| `Adapter.scala` | 183 | `ChatError` hierarchy (6 cases, `PermanentReason`), `RenderedMessage`, `RenderedControls`, `RenderedForm`, `ChoiceMapEntry`, `RenderReport`, `VendorOp` (9 cases), `CommandSpec`, `CommandArg`, `InboundSink`, `ChatAdapter` (11 methods), `ChatPort` (8 methods) |
| `Commands.scala` | 153 | `Handle`, `HhMm`, `MoodLevel` (1 to 10), `Weekday`, payload defaults, `Command` enum (6 cases) |
| `Events.scala` | 79 | `Event` enum (7 cases) and `dosecord.<domain>.<verb>.v1` type strings |
| `Envelope.scala` | 59 | CloudEvents 1.0 envelope; `Source` must match `^dosecord\.[a-z]+$` |
| `LoopDispatch.scala` | 39 | Outbox payloads of the reminder loop: `Reminder`, `FinalizeControls`, `MissedNotice`, `Digest` |
| `Ids.scala`, `Actors.scala`, `Json.scala`, `OutboxPayload.scala`, `WizardDocument.scala` | 104 | Opaque ids, actor, JSON helpers, react payload, session documents |

Test shape: upickle round-trip properties over generated values for every type, validation rejections, and a test that every `ChatAdapter` argument and return type is serialisable.

### 3.2 `core` (10,051 lines)

| Package | Files / lines | Responsibility | Largest files |
|---|---|---|---|
| `core.chat` | 15 / 2,656 | Mediator, renderer and degradation ladders, callback codec, action registry, ack policy, wizard engine, form runner, session sweeper, keyed executor, capability profiles, markup lowering, in-memory `FakeAdapter` | `WizardEngine.scala` 759, `ChatMediator.scala` 490, `Renderer.scala` 402 |
| `core.domain` | 8 / 1,557 | Pure dose state machine `Decide`, schedule `Rule` enum (9 cases), evaluator, DST resolution, projection fold, adherence | `Decide.scala` 646, `Schedule.scala` 224, `Adherence.scala` 190 |
| `core.domain.copy` | 14 / 775 | Copy catalogue for reminders, wizard, menu, identity, mood, habit, digest, ops; forbidden-phrase list | `WizardCopy.scala` 140, `ReminderCopy.scala` 129 |
| `core.application` | 11 / 2,149 | Handler composition, command registry (11 commands), account create and timezone flows, add-medication wizard, menu, dose intake, `/mood` | `MenuHandler.scala` 628, `DoseIntakeHandler.scala` 514, `AddMedicationFlow.scala` 372 |
| `core.ports` | 24 / 1,108 | `Clock`, `UnitOfWork`, `Tx` (19 repositories plus `savepoint`), repository traits, `Wake`, metrics ports, `DomainEventBus` | `OutboxRepository.scala` 201, `OccurrenceRepository.scala` 180 |
| `core.scheduling` | 7 / 1,755 | `ReminderLoop`, `Materialiser`, `OutboxDispatcher`, `ScheduleLifecycle`, `CatalogueOutboxRenderer`, `TokenBuckets`, finalize dispatch | `ScheduleLifecycle.scala` 472, `CatalogueOutboxRenderer.scala` 370, `ReminderLoop.scala` 335 |
| `core.telemetry` | 1 / 43 | `LogContext` on Ox `ForkLocal` | |

Test shape: pure unit and table tests (one named test per state-machine cell in `DecideSuite.scala`, 911 lines), ScalaCheck properties (decide, evaluator, adherence, projection fold, DST resolution, callback codec), generated golden files (335 DST rows, occurrence goldens, 40 renderer goldens for five vendor profiles, 3 wizard transcripts), and mediator and wizard tests on in-memory fakes (`MediatorFakes.scala`, 640 lines).

### 3.3 `infra` (2,452 lines)

HikariCP pool, a 124-line `sql"..."` interpolator with row mappers (`Sql.scala`), 19 PostgreSQL repositories, `PgUnitOfWork` with savepoints, Flyway `Migrator` under a session advisory lock, `PgNotifyWake` (LISTEN on `dosecord_wake`), `PgVendorLocks` (`pg_advisory_lock(hashtext('vendor:' || name))`), `Settings` (343 lines, the single source for env parsing and `.env.example` generation), `Telemetry` (scribe JSON and masking), `Metrics` (micrometer Prometheus registry), `TzdbGuard` (startup assertion on the tz database version).

Test shape: 21 suites on Testcontainers PostgreSQL 18 through a shared `PgSuite` harness and a `Fixtures` builder. They cover schema constraints, migration idempotence, concurrent loops and dispatchers, crash injection, lease expiry, catch-up and digest, the loop-equivalence property, the live-slot property, wizard persistence across restart, and the M2.2a interaction recovery protocol (13 tests).

### 3.4 `adapter-console` (133 lines)

Stdin and stdout adapter with numbered options, a `#<n> <reply>` syntax for quoted replies and a text-only capability profile. It is the boundary proof, the second test channel and the transport of the 30-day simulation. Its tests (1,573 lines) hold most of the end-to-end flow transcripts.

### 3.5 `adapter-discord` (1,132 lines)

| File | Lines | Content |
|---|---|---|
| `DiscordAdapter.scala` | 456 | `ChatAdapter` implementation, event mapping, ack watchdog, interaction handle state machine, nonce derivation, DM channel cache |
| `JdaTransport.scala` | 359 | The only file that imports `net.dv8tion` |
| `DiscordModels.scala` | 98 | Gateway-free wire model, snowflake timestamp helper |
| `DiscordTransport.scala` | 66 | Transport trait and normalised event enum |
| `DiscordTranslation.scala` | 50 | Rendered message to Discord components and modals |
| `CallbackPeek.scala` | 42 | Reads the clear-text fields of a token without keys |
| `DiscordRegistration.scala` | 35 | Slash registration plan from `CommandSpec` |
| `DiscordErrorMapper.scala` | 26 | Vendor error codes to `ChatError` |

Test shape: a fake transport (`FakeDiscordTransport.scala`), the 14 shared suite A scenarios, event-mapping and delivery suites, a real JDA serialisation fixture (`JdaPayloadSuite.scala`) and one adapter-to-mediator suite on PostgreSQL.

### 3.6 `app` (286 lines)

`Main.scala` (67), `Adapters.scala` (92), `Shutdown.scala` (78), `Cli.scala` (32), `Health.scala` (17). Commands: `run [--role gateway|worker|all]` and `migrate`. It serves `/healthz` only. It does not serve `/readyz` or `/metrics`, and it does not start the reminder loop, materialiser, outbox dispatcher or sweeper. `HEALTH_PORT` is read directly from the environment and is absent from `Settings` and `.env.example`.

### 3.7 `tests.conformance` and `tests.simulation`

`tests.conformance` holds the vendor-neutral fake-wire vocabulary (`FakeVendorServer.scala`), the suite A runner over 14 JSON scenarios, suite B2 (mediator resolution per profile) and an in-memory `UnitOfWork` (214 lines). `tests.simulation` drives the real loop, dispatcher, state machine and intake handlers through the console adapter for 30 virtual days with four personas and commits the transcript and statistics as golden files.

## 4. How vendor-neutral `contracts` and `core` really are

The CI gate `scripts/check-vendor-symbols.sh` proves that `core/` and `contracts/` contain none of the strings `net.dv8tion`, `org.telegram`, `de.connect2x`, `kotlinx`, `sttp`. That is symbol neutrality. The table below lists where vendor concepts are nevertheless encoded.

| # | Location | Finding | Consequence for a Telegram-only product |
|---|---|---|---|
| 1 | `contracts/Chat.scala`, `Markup` | Enum cases `DiscordMd`, `TelegramHtml`, `ZulipMd`, `MatrixHtml`, `Plain` | Reduce to `TelegramHtml` and `Plain` |
| 2 | `contracts/Chat.scala`, `DmInitiation` | Cases `SharedGuildOrInstall` (Discord) and `Invite` (Matrix) | Remove both |
| 3 | `contracts/Chat.scala`, `ChoiceSet`, `Form`, `Choice` | `require` limits copied from Discord: at most 25 choices, form title at most 45 characters, at most 5 fields, label at most 80 characters | Replace with limits that follow from Telegram and from product needs |
| 4 | `contracts/Chat.scala`, `InteractionHandle` | Methods `deferUpdate`, `deferReply(ephemeral)`, `openForm`, `replyAvailable`, `modalAvailable` model the Discord interaction token | Telegram uses only `answer`. The trait shrinks to a single-shot answer plus source edit |
| 5 | `contracts/Chat.scala`, `Visibility.Ephemeral`; `contracts/Adapter.scala`, `CommandSpec.visibility`, `CommandSpec.opensForm` | Declared visibility and form-opening exist because Discord fixes the ephemeral flag at defer time | No Telegram equivalent in a private chat. Keep only if the console needs them, otherwise remove |
| 6 | `contracts/Adapter.scala`, `CommandArg.choices`, `CommandArg.autocomplete` | Typed slash options and autocomplete | ASSUMPTION: Telegram bot commands carry no typed options. Medication pickers become inline keyboards, inline queries or Mini App screens |
| 7 | `contracts/Chat.scala`, `RichText` | Documented as "no headings, tables or images". `OutboundMessage` has no media field. `RenderedMessage` requires at least one non-empty text chunk | A photo with a caption cannot be expressed. Generated images need a contract extension |
| 8 | `contracts/Adapter.scala`, `VendorOp` and `ChatAdapter` | No send-photo, edit-media, set-menu-button or answer-web-app operation | Add operations or a media-capable `send` |
| 9 | `contracts/Chat.scala`, `Choice.callback` | A choice carries only a callback token string | No way to express a URL button or a web-app button that opens the Mini App |
| 10 | `contracts/Chat.scala`, `Inbound` | `MessageReceived` carries text only. `ConversationStarted` carries no payload. No case for photos, voice, location, web-app data, or the user blocking the bot | Food photos, deep links (`/start <payload>`) and block detection need new cases |
| 11 | `core/chat/CapabilityProfiles.scala` | Five hard-coded profiles: Discord, Telegram, Zulip, Matrix, Console | Keep Telegram and Console. The Telegram profile already exists and has 8 renderer goldens |
| 12 | `core/chat/VendorMarkup.scala` | Per-vendor branches including Discord timestamps and spoilers | Delete three of five branches |
| 13 | `core/chat/Renderer.scala` | Rungs for native select, numbered reactions and modal; `MaxNativeButtons = 10`; digest cap derived from Discord's 25-component limit | The reaction tier and the select rung are dead code without Zulip, Matrix and Discord |
| 14 | `core/test/resources/goldens/telegram/days-multi-select.golden` | On the Telegram profile a multi-select degrades to rung `choices.numberedReactions`: a numbered list, seven digit reactions and "reply with the numbers" | This is not native Telegram interaction. A toggle keyboard that edits its own markup is the expected pattern and needs a new rung |
| 15 | `core/scheduling/TokenBuckets.scala` | Default limits keyed by `"discord"`, `"telegram"`, `"zulip"`, `"matrix"` | Keep the Telegram entry (30 per second global, 1 per second per chat), which matches the verified FAQ figures in section 12 |
| 16 | `core/chat/CallbackCodec.scala` | Wire prefix `dc:` and a property that tokens fit Discord's 100 characters | The 64-byte bound is the binding one and is verified (section 12) |
| 17 | `core/domain/copy/AdapterCopy.scala`, `HelpCopy.scala`, `IdentityCopy.scala` | Three user-facing strings contain the product name "Dosecord" | Rename with the product |
| 18 | `core/chat/ChatMediator.scala`, `core/chat/WizardEngine.scala` | Both import `java.sql.SQLException` | A JDBC type in `core`. Not a vendor leak, but an infrastructure leak that a second ingress (the Mini App API) will copy unless a port-level error is introduced |
| 19 | `core/ports/UnitOfWork.scala`, `Tx` | One trait exposes 19 repositories. At least four in-memory test doubles implement it (`InMemoryUnitOfWork`, `MediatorFakes`, `ConsoleFlowFakes`, `MedicationWizardRig`) | Every new domain (water, food, sleep, media) widens `Tx` and every double. Consider domain-scoped repository groups |
| 20 | `core/scheduling/ReminderLoop.scala` | The loop is typed on dose occurrences, `Decide` and `dose_actions` | Water reminders, mood check-in prompts and summaries need the generic reminder kind of roadmap slice M3.5, which is not built. Only the view stub `reminder_occurrences` exists |

What is neutral in practice and needs no change: vendor as an open string, `PlatformIdentity`, `ChatRef`, `MessageHandle`, `Principal` stamping, `InboundEvent` with a vendor event id and cursor, the closed `ChatError` hierarchy, `OutboundMessage.silent` and `discreet`, `dedupeKey`, the `Reply` document, the CloudEvents envelope, and the entire `core.domain` package.

## 5. Ideas in `adapter-discord` worth carrying into a Telegram adapter

| Idea | Where it lives | Transfer |
|---|---|---|
| Transport seam | `DiscordTransport` trait, `JdaTransport` as the only SDK file, `FakeDiscordTransport` in tests | Copy the structure: a `TelegramTransport` trait, one file that touches the HTTP client or SDK, and a fake transport so the whole adapter runs in suite A without a network |
| Ack watchdog | `DiscordAdapter.startWatchdog`: one virtual thread per interaction, deadline measured from the vendor creation time, applies the declared ack if the mediator has not, first ack wins under one lock | Keep the mechanism. ADR-013 already assigns Telegram a 2 second target. ASSUMPTION: a callback query carries no creation timestamp, so the budget starts at receipt |
| Ack latency metric | `recordAckLatency` injected by the composition root, `dosecord_interaction_ack_latency_seconds{vendor}` | Keep, with a renamed metric |
| Key-free token peek | `CallbackPeek`: reads action id, subject and value without the HMAC key so the adapter can choose the ack type; the mediator re-verifies | Reuse unchanged. Move it out of the Discord package |
| Idempotent sends | `nonceOf(sendKey) = base64url(sha256(sendKey)).take(22)` with `enforce_nonce` | No transfer. ASSUMPTION (recorded in DESIGN section 7.6): Telegram has no server-side idempotency key. The outbox rule "retry after 2 minutes is flagged `possible_duplicate`" is then the only protection |
| Distinct keys per chunk | `sendKey:chunk:<index>` for multi-part messages | Keep the key scheme for durable bookkeeping even without a vendor nonce |
| Event hand-off | Gateway thread to an Ox channel to one fork per event | Keep for long polling. Note that the channel is unbounded while `InboundSink.push` can report a full queue; a poller should honour that signal |
| Closed error mapping | `DiscordErrorMapper`: 50007 to `Unreachable`, 429 to `RateLimited`, 10008 to `Permanent(MissingTarget)`, 5xx to `Retryable` | Write the Telegram table: blocked or never started to `Unreachable`, `retry_after` to `RateLimited`, "message is not modified" to success, edit target missing to `Permanent(MissingTarget)`. The exact error strings are ASSUMPTION until verified |
| Stable vendor event ids | `discord:interaction:{id}`, `discord:message:{id}` | `telegram:update:{update_id}` is already specified in DESIGN section 4.1 |
| Private-chat-only refusal | `refuseNonDm` plus registration limited to the bot DM | Refuse or ignore any update whose chat is not private |
| Injected lookups | The autocomplete provider is wired by `app` so the adapter never calls the core | Same pattern for inline-query answers |
| Adapter-to-mediator test on PostgreSQL | `DiscordApplicationPgSuite.scala` | Same suite for Telegram |
| Real SDK payload fixture | `JdaPayloadSuite.scala` asserts the exact JSON the pinned library sends | Same technique for the chosen Telegram client |

Ideas that live in `core` and `infra`, not in the adapter, and are reusable as they stand: the callback token codec (34 bytes, HMAC-SHA256 truncated to 8 bytes, two active keys, 49 characters on the wire), the outbox with lease, backoff, dead-letter and `ops_done` resume, the M2.2a recovery protocol (stored-reply readback, `deliveryChat`, `MissingTarget` replacement), the wizard engine with persisted sessions and stale-step rejection, the form runner that asks one field per message, DST handling with `dst_kind`, and the reminder loop with epoch fencing, savepoints and quarantine.

## 6. Keep, adapt or retire

Verdicts are judged against a Telegram-only product with a bot interface and a Mini App. For `green` slices the verdict applies to the delivered artefact; for `todo` slices it applies to the planned work.

### 6.1 Modules

| Module | Verdict | How |
|---|---|---|
| `contracts` | ADAPT | Apply the changes of section 4 rows 1 to 10. Add media and web-app concepts. Rename the package and the event namespace |
| `core.domain` | KEEP | No change needed for medication. New domains add their own pure models next to it |
| `core.domain.copy` | ADAPT | Product name in three strings; new copy for new domains; extend the forbidden-phrase list to food, sleep and mood (no diagnosis, no diet advice) |
| `core.scheduling` | KEEP, then ADAPT | Keep as is. Later generalise the loop to non-dose reminder kinds (roadmap M3.5) |
| `core.chat` | ADAPT | Keep mediator, codec, registry, wizard engine, form runner, sweeper, keyed executor. Delete the Discord, Zulip and Matrix profiles, markup branches and dead ladder rungs. Add Telegram-native rungs (toggle keyboards, media) |
| `core.application` | ADAPT | Handlers are reusable. The menu tree and commands are extended for new domains. Split by domain before it grows |
| `core.ports` | ADAPT | Widen for new repositories; consider grouping (section 4 row 19) |
| `infra` | KEEP, then ADAPT | SQL layer, unit of work, migrator, wake, telemetry and metrics stay. `Settings` drops Discord, Zulip and Matrix variables and gains Telegram and Mini App variables. Role, function and channel names are renamed in `V1` |
| `adapter-console` | KEEP | It is the test harness of the simulation and of most flow transcripts. Rejected alternative: deleting it as "not Telegram" would remove the transport under 1,573 lines of flow tests and the simulation |
| `adapter-discord` | RETIRE | Delete the module, its 9 test files, `DiscordWiringSuite.scala`, the JDA dependency and the `Adapter.Discord` setting. Carry the ideas of section 5 |
| `app` | ADAPT | Start the workers (M2.2b). Add the Mini App HTTP API on the existing Tapir server. Wire the Telegram adapter |
| `tests.conformance` | ADAPT | Keep the runner and the vocabulary. Review the 14 scenarios: `03-deadline-auto-ack`, `05-rate-limited`, `06-not-modified-success`, `08-delete-window-permanent`, `09-edit-not-found-permanent`, `12-resume` and `13-log-hygiene` apply to Telegram directly; `14-raw-modal-payload` is Discord-only; `07-duplicate-reaction-success` is reaction-tier only |
| `tests.simulation` | KEEP | Extend personas when new reminder kinds exist |
| New modules needed (none exist) | n/a | `adapter-telegram`; a report or image renderer (already named `report-renderer` in DESIGN section 15); a Mini App front end and its API surface |

### 6.2 ADRs

| ADR | Subject | Verdict | Note |
|---|---|---|---|
| ADR-001 | Scala 3, JDK 25, Mill, Ox, modular monolith | KEEP the decision, ADAPT the record | The scoring lens "Discord-first library and boundary quality" (weight 0.25) and the 2026-09-23 addendum (`USER_INSTALL` only) are obsolete. Restate the language decision with a Telegram lens |
| ADR-002 | PostgreSQL 18, pgjdbc, HikariCP, hand-written SQL, Flyway | KEEP | The Matrix repository remark becomes irrelevant |
| ADR-003 | No broker; PostgreSQL tables as async edges | KEEP | |
| ADR-004 | Materialised occurrences, one `next_action_at` column, epoch fencing, revisions | KEEP | Generalisation to non-dose reminders is an extension |
| ADR-005 | Mediator with capability-driven renderer for many vendors | ADAPT, supersede in part | Keep the mediator and the adapter port. The multi-vendor ladders, the reaction tier and the "Telegram slice touches zero core files" proof no longer apply. The media addendum stays relevant |
| ADR-006 | 34-byte HMAC callback tokens, `dc:` prefix | KEEP the format, ADAPT the wording | Discord `custom_id` text and the Zulip and Matrix `choice_map` path are obsolete |
| ADR-007 | Identity from `platform_identities`; HTTPS link challenge; Argon2id | SUPERSEDE in part | Keep "the core resolves identity; no account id from the wire". Cross-vendor linking has no second vendor. The Mini App needs its own authentication decision |
| ADR-008 | In-repo greenfield rewrite from Python | KEEP as history | Add a sibling ADR for the dosegram pivot |
| ADR-009 | At-least-once outbox, vendor-owned dispatch, bounded flagged duplicate | KEEP the protocol, ADAPT the wording | Discord nonce and Matrix `txnId` passages are obsolete. The media addendum must be rewritten for Telegram uploads |
| ADR-010 | Matrix end-to-end encryption through Trixnity | RETIRE | Nothing was built |
| ADR-011 | Per-schedule IANA zone, gap and fold policy, injected clock | KEEP | |
| ADR-012 | Dose lifecycle semantics | KEEP | The silent flag maps to Telegram's `disable_notification` (the parameter name is VERIFIED present, section 12) |
| ADR-013 | Acknowledgement policy | ADAPT, supersede in part | Keep "ack before database work". The Discord defer, modal and visibility rules go. The Telegram rules (single-shot answer, pre-transaction toast, 2 second watchdog) become the whole policy |

### 6.3 Roadmap slices

| Slice | Status | Verdict | Note |
|---|---|---|---|
| M0.0 Documentation cut 1 | green | ADAPT | A new documentation cut replaces DESIGN, ROADMAP and the stubs; extend `docs/doc-lint.txt` |
| M0.1 Mill build and module graph | green | KEEP | Rename `DosecordModule`; drop `adapter-discord`; add modules |
| M0.2 Delete Python and Kafka | green | KEEP | History only |
| M0.3 Flyway V1, SQL layer, UnitOfWork, sessions | green | KEEP, schema ADAPT | `V1` may still be edited in place |
| M0.4 CI and quality gates | green | ADAPT | Symbol list, service names, job names |
| M0.5 Settings, root, health, image, shutdown | green | ADAPT | Settings and image names |
| M0.6 CallbackCodec and action registry | green | KEEP | New action ids for new domains |
| M0.7 DST primitives | green | KEEP | |
| M0.8 Spike: JDA DM | green | RETIRE | |
| M0.9 Contracts, chat model, renderer, goldens | green | ADAPT | Section 4; 24 of 40 renderer goldens (Discord, Zulip, Matrix) are deleted |
| M0.10 Outbox dispatcher protocol | green | KEEP | |
| M0.11 Telemetry skeleton | green | ADAPT | Masking allows sensitive values at DEBUG, which contradicts the unconditional rule in `AGENTS.md`; new sensitive keys for food, mood notes and sleep |
| M0.12a Mediator core | green | KEEP | |
| M0.12b Wizard engine, sessions, sweeper, form runner | green | KEEP | The form runner becomes the primary in-chat form path because Telegram has no modal |
| M0.12c Console adapter | green | KEEP | |
| M0.12d Account create, `/mood`, `/help` | green | ADAPT | Entry through Telegram `/start`; mood grows |
| M0.13 Conformance suite | green | ADAPT | Section 6.1 |
| M1.1 to M1.3 Rule enum, evaluator, `decide()` | green | KEEP | |
| M1.4a Copy catalogue | green | ADAPT | Product name; new domains |
| M1.4b Projection fold and adherence | green | KEEP | |
| M1.5 to M1.8 Repositories, loop, dispatcher, catch-up | green | KEEP | |
| M1.9 Wizard, menu, Today, History, pause and resume, timezone | green | ADAPT | Logic stays; menu shape and long forms are candidates for the Mini App |
| M1.10 One-tap flows, correction, Log dose, commands | green | KEEP | |
| M1.11 30-day simulation | green | KEEP | |
| M2.1 JDA gateway, slash commands, ack policy | green | RETIRE | Ideas carried (section 5) |
| M2.2 Components, modals, ephemeral, idempotent sends | in-progress | RETIRE the Discord acceptance | The vendor-neutral mediator repairs already merged stay |
| M2.2a Interaction recovery hardening | in-progress | KEEP the core protocol | The Discord 10008 mapping goes with the module |
| M2.2b Executable runtime integration | todo | KEEP, raise priority | Section 1 item 5 |
| M2.2c Row-safe durable pages | todo | ADAPT | Page keys stay; the five-row budget and nonces are Discord facts |
| M2.2d Scoped navigation and policy editing | todo | ADAPT | Behaviour stays; split between chat and Mini App is a design decision |
| M2.2e Preserved drafts | todo | KEEP | |
| M2.2f Single-use manual-log intent | todo | KEEP | The same rule is needed for water and food taps |
| M2.2g All-level log privacy | todo | KEEP | Scope grows with new health data |
| M2.2h Execution and coverage enforcement | todo | KEEP | |
| M2.3 Conformance gate, live smoke, tap budget | todo | ADAPT | Rewrite for Telegram |
| M2.4 Readiness, metrics, alerts, backups, operator CLI | todo | KEEP | |
| M2.5 Documentation cut 2 | todo | ADAPT | |
| M3.1 Notes, skip reasons, custom-time corrections | todo | KEEP | |
| M3.2 Schedule edit, timezone questions, history paging | todo | KEEP | |
| M3.3 Quiet hours and discreet mode | todo | KEEP | The Telegram spoiler markup is already in `VendorMarkup.scala` |
| M3.4 Stats | todo | KEEP, widen | The snapshot idea extends to every domain |
| M3.4a Attachment and render-container spike | todo | ADAPT, raise priority | The owner requires generated images |
| M3.4b Durable report preparation and assets | todo | ADAPT | |
| M3.4c Media transport conformance | todo | ADAPT | The JDA multipart fixture is replaced by a Telegram upload fixture |
| M3.4d Infographic qualification | todo | ADAPT | |
| M3.5 Generic reminder kind, weekly summary | todo | KEEP, raise priority | Prerequisite for water reminders and check-in prompts |
| M3.6 Simulation extended, nightly checks | todo | KEEP | |
| M4.1 Web edge, auth-link, handle step | todo | ADAPT heavily | The Tapir web edge becomes the Mini App back end; the link challenge has no second vendor |
| M4.2 Credentials, recovery, restore, unlink | todo | RETIRE recommended | Open question 3 |
| M4.3 Audit, key rotation, roles | todo | KEEP | |
| M4.4 Export and erasure | todo | KEEP, widen | |
| M4.5 Tracing, full metrics, dashboards | todo | KEEP | |
| M4.6 Security review gate | todo | KEEP, widen | The Mini App is a new public surface |
| M5.1 Telegram adapter | todo | ADAPT, becomes the first adapter | The "zero core files" acceptance cannot hold once contracts change. The client library choice (TelegramBots 10.3.0 in DESIGN) should be re-examined |
| M5.2 Multi-channel fallback | todo | RETIRE | One vendor. `delivery_channels` keeps one row per account |
| M5.3 Cross-vendor link | todo | RETIRE | |
| M6.0, M6.1, M6.2, M6.3, M6.3-alt, M6.4 | todo | RETIRE | Matrix, Zulip and the reaction tier |
| M7.1 Every-N, cycle, interval kinds | todo | KEEP | |
| M7.2 Chains and taper | todo | KEEP | |
| M7.3 Refill tracking | todo | KEEP | |
| M7.4 Habits | todo | ADAPT | Water intake can be modelled on the existing `count` or `measurement` habit type, or as its own domain; this is a design decision |
| M7.5 Mood breadth and NLU router | todo | ADAPT, raise priority | Mood becomes a first-class domain with generated images |
| M7.6 Generic reminders UI | todo | KEEP | |
| M7.7 Read-only REST API | todo | ADAPT, raise priority | The Mini App needs a read and write API, not a read-only bearer-token API |
| M7.8 Native-image experiment | todo | RETIRE | The reachability work targeted JDA; Java2D rendering makes a native image harder |
| M7.9 Documentation cut 3 and v1.0 | todo | ADAPT | |
| UX-00 UX governance adoption | green | ADAPT | The packet mapping and `scripts/check-bot-ux-plan.py` are bound to `docs/design-tracks/bot-ux-graph.json` (70 nodes, 441 edges, 19 packets) and to a `max_discord_rows` invariant |

Roadmap statements that the owner's direction contradicts directly: section 4 M5 "Non-goals. Mini Apps, reply keyboards, groups"; section 7 "Matrix polls (MSC3381), Telegram Mini Apps, Zulip widgets, Discord V2 components: not part of the unified model in v1"; section 7 "Integration service, external health apps ... single-tenant scale needs none"; section 9 Definition of Done, which is written entirely around Discord.

### 6.4 Spikes, design tracks and other documents

| Document | Verdict | Note |
|---|---|---|
| `docs/spikes/callback-tokens.md` | KEEP | The Telegram 64-byte budget was the binding constraint from the start |
| `docs/spikes/dst-primitives.md` | KEEP | Also records why `jvmVersion` is pinned and why the image refreshes tzdata |
| `docs/spikes/outbox-idempotency.md` | KEEP | |
| `docs/spikes/m2-2a-recovery.md` | KEEP | Evidence for the recovery protocol |
| `docs/spikes/jda-dm.md` | RETIRE (archive) | Contains the owner directive "DM-only personal app", which still applies to Telegram private chats |
| `docs/verification/m2.2-implementation.md` | RETIRE (archive) | Keep as evidence of test counts |
| `docs/design-tracks/reminder-reliability.md` and `unified-chat-api.md` | ADAPT | Fix the swapped contents; the scheduling track stays valid, the chat track is rewritten |
| `docs/design-tracks/bot-ux-graph.json` | ADAPT | Regenerate for Telegram |
| `docs/DESIGN.md` | ADAPT | Sections 7 to 9 and 11 stay largely valid; sections 2 to 6, 12 and 15 are rewritten |
| `docs/ROADMAP.md` | ADAPT | Reissue with a new status table; keep the green engine slices as the inherited baseline |
| `docs/BOT_UX_PLAN.md` | ADAPT | Keep sections 3 (UX contract), 5 (notification behaviour) and 6 (truthful statistics and infographics). Its assumptions "Keep ... JDA 6.6.0", "Mood ... not a seventh top-level category" and "wearable integration [is] not added implicitly" need review |
| `docs/MEDICATION_REMINDER_UX.md` | KEEP | Product specification; one Discord mention; BOT_UX_PLAN already notes stale worker and wizard sections |
| `docs/AGENTIC_DEVELOPMENT.md`, `AGENTS.md` | ADAPT | Entry point, slice names, the Discord wording; the process itself stays |
| `docs/archive/SWARM_RESEARCH_SUMMARY.md` | KEEP as archive | |
| `README.md`, `QUICKSTART.md`, `docs/DEVELOPMENT.md`, `docs/API.md`, `docs/PRODUCTION_PLAN.md` | ADAPT | All are stubs of 8 to 17 lines |

### 6.5 Scripts and CI

| Item | Verdict | Note |
|---|---|---|
| `scripts/check-vendor-symbols.sh` | ADAPT | Remove `net.dv8tion`, `de.connect2x`, `kotlinx`; keep `org.telegram`; add the symbols of whichever Telegram client and chart library are chosen |
| `scripts/check-sql-now.sh` | KEEP | The module list in its `find` omits `adapter-discord` already; add new modules |
| `scripts/doc-lint.sh`, `docs/doc-lint.txt` | ADAPT | Add retired names to the forbidden list |
| `scripts/flyway-validate.sh` | ADAPT | Default database, user and password are `dosecord` |
| `scripts/license-gate.sh` | KEEP | 124 dependencies passed on 2026-10-15; a chart library must pass the same permissive-licence regex |
| `scripts/coverage-gate.sh` | ADAPT | Package names `dosecord.core.domain` and `dosecord.core.chat` |
| `scripts/rss-check.sh` | KEEP | The 200 MB idle gate will need re-measurement once image rendering is in process |
| `scripts/check-bot-ux-plan.py`, `scripts/test-check-bot-ux-plan.py` | ADAPT | Bound to the Discord-era graph |
| `.github/workflows/ci.yml` | ADAPT | Nine jobs; names and credentials only |
| `renovate.json`, `.scalafmt.conf`, `.scalafix.conf`, `.mill-version` | KEEP | |

## 7. Persistence model and schema

One Flyway migration: `infra/src/main/resources/db/migration/V1__baseline.sql` (225 lines). PostgreSQL 18, extension `citext`. All instants are `timestamptz`, all ids are `uuid` except `domain_events.seq` (`bigserial`). SQL `now()` appears only in `DEFAULT` clauses; every query binds `now` from the injected clock (enforced by `scripts/check-sql-now.sh`).

Objects: 24 tables, 1 view, 2 enum types, 2 roles, 1 trigger function, 2 triggers.

| Group | Table | Key columns and constraints | Written by code today |
|---|---|---|---|
| Identity | `users` | `handle citext UNIQUE`, `timezone` default `UTC`, `locale`, `status` (active, pending_credentials, disabled), `quiet_hours jsonb`, `discreet_default`, `delivery_policy` | yes |
| Identity | `platform_identities` | `(vendor, vendor_user_id)` unique while linked; `(user_id, vendor)` unique while linked; `dm_channel_id`; `last_seen_at`; `unlinked_at` | yes |
| Identity | `user_credentials`, `auth_challenges`, `recovery_codes` | Argon2 parameters, hashed tokens and codes, lockout counters | no |
| Identity | `auth_audit_log` | Append-only by trigger | yes (tamper audit rows) |
| Medication | `medications` | `name_norm` generated as `lower(btrim(name))`; unique per user while not archived; `dose_amount`, `dose_unit`, `instructions`, `discreet`, `status` | yes |
| Medication | `medication_schedules` | `tz`, `tz_follows_user`, `start_date`, `end_date`, `materialized_through`, `current_revision` | yes |
| Medication | `schedule_revisions` | Append-only by convention; `rule jsonb`, `reminder_policy jsonb`, `dose_snapshot jsonb`; unique `(schedule_id, revision)` | yes |
| Medication | `dose_occurrences` | Status enum of 8 values; `origin` (scheduled, chain, manual); `local_date`, `local_time`, `slot_key`, `tz`, `dst_kind`; windows; `epoch`, `reminder_seq`, `snooze_count`, `next_action_at`, `error_count`, `version`; 6 named CHECK constraints plus 3 inline ones; partial unique index `uq_occ_live_slot (schedule_id, local_date, slot_key) WHERE status <> 'cancelled'`; hot index `ix_occ_next_action` | yes |
| Medication | `dose_actions` | Append-only by trigger and by grant; action enum of 13 values; `idempotency_key UNIQUE`; unique `(occurrence_id, seq)`; `note` | yes |
| Async edges | `outbox_messages` | `send_key UNIQUE`; `op` (send, edit, finalize, delete, react); `vendor`; `epoch`; `items`, `target`, `payload` as jsonb; 7 statuses; `lease_until`, `attempted_at`, `ops_done`, `possible_duplicate`, `traceparent` | yes |
| Async edges | `rendered_messages` | Unique `(vendor, chat_id, message_id)`; `choice_map jsonb`; `superseded_by`; `controls_removed_at` | yes |
| Async edges | `inbound_events` | Primary key `(vendor, vendor_event_id)`; `reply jsonb`; `processed_at`. No code deletes rows from this table | yes |
| Async edges | `domain_events` | Append-only log with CloudEvents envelope in `data` | yes |
| Async edges | `worker_heartbeat` | One row per process instance | yes |
| Conversation | `callback_slots`, `conversation_sessions`, `form_runs` | Session unique on `(principal_key, vendor, chat_id)`; `step_seq`, `version`, `last_prompt`, `expires_at` | yes |
| Delivery | `delivery_channels` | One row per platform identity; `role`, `priority`, `state`, `last_ack_at` | yes |
| Satellites | `mood_checkins` | `mood_level` 1 to 10, `note`, `tags text[]`, `recorded_at` | yes (insert only; no read query exists) |
| Satellites | `habits` | `habit_type` (boolean, count, duration, measurement, avoid), `cadence jsonb`, `streak_policy`; unique `lower(name)` per user | no |
| Satellites | `habit_checkins` | `local_date`, `status` (done, partial, planned_skip, missed), `value numeric`, `note` | no |
| Satellites | `reminders` | `title`, `repeat` (none, daily, weekly), `next_due_at`, `status` | no |
| View | `reminder_occurrences` | Stub over `dose_occurrences` with `kind = 'dose'` | no |

Roles and guards: `dosecord_app` has SELECT, INSERT and UPDATE on all tables except UPDATE on `dose_actions` and `auth_audit_log`. `dosecord_erasure` may DELETE from those two tables, and only while the session setting `dosecord.erasure = 'on'` is set; the trigger function `dosecord_guard_append_only()` checks role membership in `pg_auth_members` directly.

Coordination outside tables: migration advisory lock key `0x7a65d05ec0d` (`Migrator.AdvisoryLockKey`); per-vendor advisory locks on `hashtext('vendor:' || name)`; NOTIFY and LISTEN channel `dosecord_wake`.

Observations that matter for the new direction:

1. `platform_identities.last_seen_at` is overwritten on every inbound event by `PgIdentityRepository.resolve`. It is the time of the last interaction with the bot, not a platform presence signal, and it keeps no history.
2. `inbound_events.received_at` is retained for every inbound event and is never pruned. It is the only existing per-interaction time series. No retention policy is defined for it.
3. `users.locale` exists and is unused. No localisation mechanism exists in the copy catalogue; all copy is English.
4. There is no table for binary assets. DESIGN section 15 proposes PostgreSQL `bytea` assets with a 2 MB limit; nothing is implemented.
5. There is no table for any HTTP session, API token or Mini App state.
6. The roadmap rule "V1 is edited in place until the `0.1.0` tag" still applies because no tag exists. ASSUMPTION: there is no production data to preserve; the owner-use week on Discord was never completed according to Appendix B.

## 8. Build, test and CI commands that exist

None of these commands was executed in this run. "Last recorded result" refers to evidence committed in the repository.

| Purpose | Command | Defined in | Last recorded result |
|---|---|---|---|
| Format check | `./mill __.checkFormat` | CI job `scalafmt` | pass, 2026-10-15 |
| Lint check | `./mill __.fix --check` | CI job `scalafix` | pass |
| Compile all | `./mill __.compile` | CI job `build-test` | pass under `-Werror` |
| Test all | `./mill __.test` | CI job `build-test` | 86 suites, 1,074 tests, 0 failed |
| Test one module | `./mill --no-server -j 1 <module>.test`, for example `core.test`, `infra.test`, `adapter-console.test`, `tests.conformance.test`, `tests.simulation.test`, `app.test` | `docs/verification/m2.2-implementation.md` | per-module counts in section 3 |
| Env example check | `./mill app.test.runMain dosecord.app.envExampleCheck .env.example` | CI job `build-test` | pass |
| Env example generate | `./mill app.test.runMain dosecord.app.envExampleGenerate .env.example` | header of `.env.example` | n/a |
| Flyway migrate and validate | `scripts/flyway-validate.sh` (Flyway CLI image `flyway/flyway:13.6.0-alpine`) | CI job `build-test` | pass |
| Vendor-symbol gate | `scripts/check-vendor-symbols.sh` | CI job `vendor-symbols` | clean |
| SQL time gate | `scripts/check-sql-now.sh` | CI job `sql-now` | clean |
| Documentation lint | `scripts/doc-lint.sh` | CI job `doc-lint` | clean |
| UX plan validator | `python3 scripts/check-bot-ux-plan.py`; `python3 scripts/test-check-bot-ux-plan.py` | CI job `doc-lint` | pass, 44 negative tests |
| Licence gate | `scripts/license-gate.sh` | CI job `license-gate` | 124 dependencies, all permissive |
| Coverage report | `./mill core.test`, `./mill core.scoverage.xmlReport`, `scripts/coverage-gate.sh` | CI job `coverage` | reports only; `COVERAGE_GATE_ARMED: "false"` |
| Assembly | `./mill app.assembly` | `docker/Dockerfile` | pass |
| Image build | `docker build -f docker/Dockerfile -t dosecord:ci .` | CI job `docker` | pass |
| Compose smoke | `docker compose -f docker/compose.yml up -d --build --wait`, then `curl http://localhost:28080/healthz` | CI job `docker` | pass |
| Idle memory gate | `scripts/rss-check.sh 200` | CI job `docker` | 126 to 140 MB |
| Run | `java -jar dosecord.jar run [--role gateway\|worker\|all]`, or `migrate` | `app/src/dosecord/app/Cli.scala` | n/a |

Golden regeneration entry points (all `@main` functions in test sources): `rendererGoldenGenerate`, `firstFlowsGoldenGenerate`, `medicationWizardGoldenGenerate`, `dstGoldenGenerate <path>`, `occurrenceGoldenGenerate <path>`, `consoleFirstFlowsGoldenGenerate`, `consoleIntakeGoldenGenerate`, `simulationGoldenGenerate`. The documented invocation form is `./mill core.test.runMain dosecord.core.domain.dstGoldenGenerate core/test/resources/dst-golden.txt`.

Known hazards recorded in the repository: a multi-selector Mill invocation once applied a test filter and executed zero tests (use separate commands or the `+` separator and check executed counts); Testcontainers start-up occasionally times out under parallel load; issue #9 tracks an intermittent Mill subprocess failure; the Mill launcher ignores `JAVA_HOME`, which is why `jvmVersion` is pinned in `build.mill`.

CI jobs (`.github/workflows/ci.yml`, triggers: push to `main`, pull requests): `scalafmt`, `scalafix`, `build-test`, `vendor-symbols`, `sql-now`, `doc-lint`, `docker`, `license-gate`, `coverage`.

## 9. Rename checklist

Totals over tracked files, case-insensitive: `dosecord` 1,677 occurrences on 1,623 lines in 290 files (1,618 lower-case, 59 capitalised, no upper-case form). `discord` 946 occurrences on 847 lines in 84 files (736 capitalised, 202 lower-case, 8 upper-case). 256 tracked paths contain a `dosecord` directory segment; 27 tracked paths contain `discord`.

### 9.1 `dosecord`

| # | Where | Count | What to change |
|---|---|---|---|
| 1 | Scala package directories `*/dosecord/` | 256 file paths | Move to `*/dosegram/` |
| 2 | `package dosecord...` declarations | 256 in 256 files, 21 distinct packages | Rename |
| 3 | `import dosecord...` lines | 962 | Rename |
| 4 | `core/src` | 444 occurrences in 81 files | Packages, imports, comments, 3 user-facing strings (row 14) |
| 5 | `core/test/src` and `core/test/resources` | 252 in 50 files; 7 in 5 golden files | Packages; goldens that print the welcome and help copy |
| 6 | `infra/src` | 156 in 30 files | Packages; `Settings` examples; logger modifier id `dosecord.masking`; `LISTEN dosecord_wake` |
| 7 | `infra/src/main/resources/db/migration/V1__baseline.sql` | 23 | Roles `dosecord_app`, `dosecord_erasure`; setting `dosecord.erasure`; function `dosecord_guard_append_only`; exception text prefix `dosecord:` |
| 8 | `infra/test/src` | 259 in 28 files | Packages; role and setting names in constraint tests |
| 9 | `contracts/src`, `contracts/test/src` | 28 in 11 files; 15 in 5 files | Packages; 7 event type strings and 6 command type strings of the form `dosecord.<domain>.<verb>.v1`; `Source.Pattern = ^dosecord\.[a-z]+$` |
| 10 | `adapter-console` | 4 main, 78 test in 7 files, 3 in goldens | Packages, goldens |
| 11 | `adapter-discord` | 27 main in 8 files, 45 test in 9 files | Deleted with the module |
| 12 | `app/src`, `app/test/src` | 33 in 5 files; 14 in 6 files | Packages; `mainClass`; CLI usage string `dosecord run ...`; error prefix `dosecord:` |
| 13 | `tests/conformance`, `tests/simulation` | 28 in 9 files; 72 in 7 files | Packages |
| 14 | User-facing copy | 3 strings | `AdapterCopy.dmOnlyRedirect`, `HelpCopy.intro`, `IdentityCopy.welcome` |
| 15 | `build.mill` | 19 | Trait names `DosecordModule`, `DosecordTests`; `mainClass = Some("dosecord.app.Main")` |
| 16 | `docker/Dockerfile` | 12 | Group and user `dosecord` (uid 10001), `/opt/dosecord/dosecord.jar`, `/home/dosecord/dosecord.cds`, comments |
| 17 | `docker/compose.yml` | 9 | `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `pg_isready` arguments, `DATABASE_URL`, image tag `dosecord:local` |
| 18 | `.github/workflows/ci.yml` | 7 | PostgreSQL service credentials, `dosecord.app.envExampleCheck`, image tag `dosecord:ci` |
| 19 | `.env.example` (generated from `Settings.scala`) | 8 | Database URL, `INSTANCE_ID=dosecord-1`, `MATRIX_USER=@dosecord:...`, `PUBLIC_BASE_URL=https://dosecord.example.com`, generator class names |
| 20 | Environment variable names | 0 contain `DOSECORD` | Nothing to rename; no variable carries the product name |
| 21 | Metric names with prefix `dosecord_` | 15 distinct names (below); 7 exist in Scala source | Rename the prefix |
| 22 | `scripts/` | 7 in 2 files | `flyway-validate.sh` defaults; `coverage-gate.sh` package names |
| 23 | `.github/ISSUE_TEMPLATE/roadmap-slice.yml` | 1 | Description text |
| 24 | `AGENTS.md`, `README.md`, `QUICKSTART.md` | 2, 2, 1 | Text |
| 25 | `docs/ROADMAP.md`, `docs/DESIGN.md`, `docs/BOT_UX_PLAN.md` | 33, 27, 20 | Rewritten by the new documentation cut |
| 26 | `docs/archive/`, `docs/spikes/`, `docs/adr/`, `docs/design-tracks/`, `docs/doc-lint.txt`, `docs/API.md`, `docs/DEVELOPMENT.md` | 21, 11, 2, 3, 2, 1, 1 | Historical records should keep the old name; add a note at the top instead of rewriting |
| 27 | GitHub repository name and remote URL | 1 | `w0rxbend/dosecord`; owner action |
| 28 | Local checkout directory | 1 | `/home/worxbend/Projects/dosecord`; owner action |

Metric names in source and documents: `dosecord_unknown_total`, `dosecord_outbox_dead_total`, `dosecord_interaction_ack_latency_seconds`, `dosecord_scheduler_tick_age_seconds`, `dosecord_tzdb_info`, `dosecord_possible_duplicates_total`, `dosecord_form_degraded_total`, `dosecord_reminders_due_total`, `dosecord_ratelimit_hits_total`, `dosecord_outbox_depth`, `dosecord_occurrence_quarantined_total`, `dosecord_materialized_lag_seconds`, `dosecord_auth_failures_total`, `dosecord_adapter_errors_total`, `dosecord_adapter_connected`. The first seven appear in Scala source or tests. The last eight appear only in documents and are not implemented.

Related constant that does not contain the product name but was chosen for it: the callback token wire prefix `dc:` (29 occurrences in 13 Scala files; 477 occurrences in 39 golden and JSON files). See open question 1.

### 9.2 `discord`

| # | Where | Count | Action |
|---|---|---|---|
| 1 | `adapter-discord/src` | 256 in 8 files | Delete the module |
| 2 | `adapter-discord/test/src` | 297 in 9 files | Delete |
| 3 | `build.mill` | 2 | Remove the module object and the `app` dependency; remove JDA 6.6.0 |
| 4 | `app/src/dosecord/app/Adapters.scala` | 11 | Remove the Discord case, the autocomplete limit and the metric tag |
| 5 | `app/test/src/dosecord/app/DiscordWiringSuite.scala` | 4 | Delete the file |
| 6 | `infra/src/dosecord/infra/Settings.scala` | 14 | Remove `Adapter.Discord`, `discordToken`, `DISCORD_TOKEN`; consider removing the Zulip and Matrix settings in the same change |
| 7 | `infra/test/src` | 21 in 5 files | `SettingsSuite`, and four database suites that use `"discord"` as a sample vendor string or reference Discord error semantics |
| 8 | `.env.example` | 3 | Regenerated from `Settings` |
| 9 | `contracts/src` | 6 in 2 files | `Markup.DiscordMd`; four comments and `require` messages |
| 10 | `contracts/test/src/dosecord/contracts/Gens.scala` | 6 | Generators |
| 11 | `core/src` | 20 in 8 files | `CapabilityProfiles.Discord`; `VendorMarkup` (8); `TokenBuckets` (2); comments in `AckPolicy`, `Renderer`, `WizardEngine`, `DoseIntakeHandler`; sample text in `OpsCopy` |
| 12 | `core/test/src` | 87 in 11 files | `RendererSuite` (26 lines), `MedicationWizardSuite` (21 lines), `CatalogueOutboxRendererSuite` (12 lines), `MedicationWizardRig` (7 lines) and seven smaller files |
| 13 | `core/test/resources/goldens/discord/` | 8 files, 17 occurrences | Delete the directory |
| 14 | `core/test/resources/goldens/transcripts/medication-wizard.discord.golden` | 1 file | Delete or replace with a Telegram transcript |
| 15 | `tests/conformance` | 3 in source, 1 in `10-markup-golden.json` | Remove the `discordMd` golden key |
| 16 | `scripts/check-bot-ux-plan.py` | 1 | `max_discord_rows` |
| 17 | `AGENTS.md` | 2 | "Current entry point" paragraph |
| 18 | `docs/ROADMAP.md`, `docs/DESIGN.md`, `docs/BOT_UX_PLAN.md` | 65, 34, 14 | Rewritten by the new documentation cut |
| 19 | `docs/adr/` | 25 in 8 files | Historical; superseded by new ADRs (section 10) |
| 20 | `docs/design-tracks/`, `docs/archive/`, `docs/spikes/`, `docs/verification/`, `docs/doc-lint.txt`, `docs/MEDICATION_REMINDER_UX.md`, `docs/AGENTIC_DEVELOPMENT.md` | 32, 13, 5, 4, 1, 1, 1 | Historical except the last two |

Other vendor names that leave with the pivot, counted in non-document files (`*.scala`, `*.mill`, `*.sh`, `*.yml`, `.env.example`, `*.sql`): `zulip` 90 occurrences in 12 files; `matrix` 78 in 13 files; `jda` 150 in 12 files. `telegram` already occurs 56 times in 16 files. The string "Zephyr" (the display name of the Discord application) occurs 3 times, only in `docs/spikes/jda-dm.md`.

Recommended order, to keep each diff reviewable: first delete `adapter-discord` and the Discord, Zulip and Matrix profiles, settings and goldens; then perform the mechanical `dosecord` to `dosegram` rename as one change with no behaviour change; then start feature work. The rejected alternative is renaming first, which would rename 553 Discord-module occurrences and 24 golden files that are about to be deleted.

## 10. Accepted ADRs that the pivot contradicts

The ADR README rule is that a changed decision is recorded as an addendum and the original text is never edited. The pivot is larger than an addendum for the records below, so new ADRs that supersede them are the appropriate form, with a short "Superseded by" addendum added to each old record.

| ADR | Contradicted by | Extent | What the superseding record must decide |
|---|---|---|---|
| ADR-001 | Telegram-only direction | Partial: the rationale and the addendum, not the decision | Whether Scala 3, Mill and Ox stay (the evidence in this brief supports staying), with the library lens restated for Telegram and for a Mini App front end, which introduces a web technology the ADR never considered |
| ADR-005 | One vendor; Telegram-native interface; Mini App as a second surface | Substantial | Whether the adapter port stays with two implementations (Telegram and console); which ladders survive; how Telegram-only concepts (media, web-app buttons, reply keyboards, deep links) enter the contract; how the Mini App API shares the mediator's identity stamping and per-event transaction |
| ADR-006 | No Discord `custom_id`; no reaction tier | Wording only | Confirm the token format; decide the prefix (open question 1); define how the Mini App authorises the same actions without callback tokens |
| ADR-007 | No second vendor to link; Mini App authentication | Substantial | How the Mini App authenticates a Telegram user (ASSUMPTION: by validating the signed launch data that Telegram passes to a Mini App; to be verified by the Telegram research brief); whether passwords, recovery codes and the link challenge are built at all |
| ADR-009 | No Discord nonce, no Matrix transaction id, one vendor | Partial | Restate the duplicate rule for a vendor without an idempotency key; rewrite the media addendum for Telegram photo uploads, including whether a stable server-side file identifier may be stored (the sendPhoto documentation recommends reuse by `file_id`, VERIFIED in section 12, while the current addendum forbids persisting provider identifiers that expire) |
| ADR-010 | Matrix is dropped | Complete | Withdraw |
| ADR-013 | No deferrable interactions, no modals, no ephemeral replies | Substantial | The Telegram-only acknowledgement policy; the policy for Mini App requests, which have no chat acknowledgement at all |

Records that stand: ADR-002, ADR-003, ADR-004, ADR-011, ADR-012. ADR-008 stands as history.

Decisions that do not exist yet and that the new direction requires: the Mini App technology and hosting; the public HTTP surface (the current design states that the only public HTTP surface is the auth-link page and that no inbound webhook is required); the image rendering library and its runtime budget; the data model for water, food, mood breadth and sleep; the handling of user-supplied media such as food photos (the logging rule in `AGENTS.md` currently names credentials, message bodies, medication names and notes only).

## 11. Gaps between the repository and the owner's direction

| Required by the owner | What exists | What is missing |
|---|---|---|
| Telegram bot interface | Telegram capability profile, HTML markup lowering, token bucket entry, 8 renderer goldens, `TELEGRAM_TOKEN` setting, `Adapter.Telegram` enum case | The adapter module, a Telegram client dependency, native interaction rungs |
| Telegram Mini App for registry and schedules | Tapir server with one endpoint; all write logic behind `UnitOfWork` and `ScheduleLifecycle` | Any HTTP API, authentication, front end, static hosting, CORS or CSRF policy |
| Reminders | Complete engine, tested | The executable does not start it (M2.2b) |
| Analytics and dashboards | Pure adherence functions; a specified but unbuilt statistics snapshot | Queries, read models, any dashboard |
| Generated images | A paper design (DESIGN section 15) and one out-of-repo fixture noted in BOT_UX_PLAN (XChart 4.0.4 with Java2D, 1200 by 900 PNG, 54,541 bytes) | Attachment contract, renderer module, asset storage, transport, fonts, measured budgets |
| Mood tracker | `/mood` command, `mood_checkins` table, parsing with 8 keywords | Scheduled check-in prompts, history queries, trends, the "mood blob" image |
| Water intake | Nothing specific; the unused `habits` table has `count` and `measurement` types | Domain model, logging flow, goals, reminders |
| Food intake | Nothing | Domain model, photo intake, any nutrition data source |
| Sleep tracker from "last seen" | `platform_identities.last_seen_at` (bot interaction time, overwritten) and `inbound_events.received_at` (retained) | A presence source (see section 12, item 9), an activity log, a sleep model |
| Non-clinical guidance | `ForbiddenPhrases` (13 clinical and 11 shaming phrases) checked against the whole copy catalogue | Coverage of the new domains |

## 12. External platform statements used in this brief

| # | Statement | Status | Source and date |
|---|---|---|---|
| 1 | The current Bot API version is 10.4 | VERIFIED | https://core.telegram.org/bots/api, checked 2026-11-12 |
| 2 | Inline keyboard `callback_data` is 1 to 64 bytes | VERIFIED | same page: "Data to be sent in a callback query to the bot when the button is pressed, 1-64 bytes" |
| 3 | Message text is 1 to 4096 characters after entity parsing | VERIFIED | same page, `sendMessage` |
| 4 | A message can be deleted only if it was sent less than 48 hours ago; bots can delete outgoing and incoming messages in private chats | VERIFIED | same page, `deleteMessage` |
| 5 | A photo is at most 10 MB; width plus height at most 10,000; aspect ratio at most 20; a photo already on Telegram servers can be re-sent by `file_id`, which the documentation recommends | VERIFIED | same page, `sendPhoto` |
| 6 | Captions are 0 to 1024 characters after entity parsing | VERIFIED | same page |
| 7 | Clients show a progress bar after a callback button press until `answerCallbackQuery` is called, so it must always be called | VERIFIED | same page, note under `CallbackQuery` |
| 8 | The `message_reaction` update requires the bot to be an administrator in the chat and to list the update type explicitly in `allowed_updates`; it is not sent for reactions set by bots | VERIFIED as quoted | same page, `Update`. Whether it is delivered in a private chat is not stated. The repository's Telegram profile claims `reactionEvents = true`; treat that claim as an ASSUMPTION |
| 9 | The Bot API reference exposes no field or method for a user's last-seen time or online status | VERIFIED by absence | same page; a text search for "last seen", "last_seen", "was_online" and "online" finds only flags about hidden presence in group chats |
| 10 | In a single chat a bot should not send more than one message per second; bulk sending is limited to about 30 messages per second; exceeding the limits returns error 429 | VERIFIED | https://core.telegram.org/bots/faq, checked 2026-11-12 |
| 10a | `sendMessage` and `sendPhoto` accept a `disable_notification` parameter | VERIFIED (parameter name present) | https://core.telegram.org/bots/api, checked 2026-11-12 |
| 11 | Bot-authored messages can be edited at any age | ASSUMPTION | Recorded in DESIGN section 2. The reference states a 48-hour limit only for business messages not sent by the bot |
| 12 | A no-op edit returns HTTP 400 "message is not modified" | ASSUMPTION | Recorded in DESIGN section 2 |
| 13 | A callback query expires after roughly 10 seconds | ASSUMPTION | Recorded in DESIGN section 2 as unpublished |
| 14 | `sendMessage` has no server-side idempotency key | ASSUMPTION | Recorded in DESIGN section 7.6 |
| 15 | TelegramBots 10.3.0 (`org.telegram:telegrambots-longpolling`) is the current Java client | ASSUMPTION | Recorded in DESIGN section 2 on 2026-09-11; not re-checked |
| 16 | In a private chat the chat id equals the user id | ASSUMPTION | Not recorded in the repository; relevant to `resolveChat` |
| 17 | The Telegram profile value `maxChoicesPerRow = 4` | Design choice, not a platform limit | `core/chat/CapabilityProfiles.scala` |
| 18 | A Mini App receives signed launch data that a server can validate with the bot token | ASSUMPTION | Not checked in this brief |

## 13. Open questions

1. **Callback token prefix.** Keep `dc:` or change it with the product name? Recommendation: keep `dc:`. It is a wire constant, no user sees it, the length budget (49 characters) is unchanged either way, and changing it touches 13 source files and 39 golden files for no behavioural gain. Rejected alternative: `dg:` for naming consistency.
2. **Shape of the chat boundary.** Recommendation: keep `ChatAdapter` and `ChatMediator` with exactly two adapters, Telegram and console, and allow Telegram-native concepts in `contracts` with a defined console fallback. Rejected alternative: fold the mediator into a Telegram-specific bot, which would discard the console transport under the simulation and most flow tests.
3. **Passwords and recovery codes (ADR-007, slice M4.2).** Recommendation: do not build them. With one vendor, the Telegram identity is the account, and export plus erasure cover data rights. Revisit only if a second login surface is added. Rejected alternative: keep Argon2id credentials as a recovery path for a lost Telegram account.
4. **Event type namespace.** `dosecord.<domain>.<verb>.v1` is stored in `domain_events`. Recommendation: rename to `dosegram.` together with the package, because no consumer and no retained data exist. This rests on the assumption in section 7, item 6; the owner should confirm that no database needs to be kept.
5. **First engineering slice.** Recommendation: after the two mechanical changes of section 9, implement M2.2b (start the loop, materialiser, dispatcher and sweeper from the executable, with launch-path tests) before any Telegram code. Without it no reminder can be delivered on any platform.
6. **Sleep tracking source.** The repository has no platform presence data and the Bot API reference exposes none (section 12, item 9). Recommendation: derive a sleep estimate from the user's own interaction gaps (`inbound_events.received_at` and Mini App requests) combined with explicit good-night and wake-up check-ins, and present it as an estimate. This needs an explicit retention policy for the activity log. The owner should confirm that this meets the "last seen" intent.
7. **Telegram client library.** DESIGN names TelegramBots 10.3.0. The alternative is a small hand-written client over the JDK HTTP client, in the style the design already chose for Zulip. This brief does not decide it; the Telegram research brief should, with the licence gate in mind.
8. **Supersession mechanics.** Recommendation: issue new ADRs (ADR-014 onward), add one-line "Superseded by" addenda to ADR-001, 005, 006, 007, 009, 010 and 013, reissue the roadmap with a new Appendix B that lists the green engine slices as inherited, and update `AGENTS.md` in the same change. `AGENTS.md` forbids product slices that are absent from the roadmap, so the roadmap change must merge before product work.
9. **GitHub repository rename.** This run may not rename anything. Recommendation: rename the GitHub repository after the mechanical rename is merged, then update the remote URL, CI badges and branch protection names. ASSUMPTION: GitHub redirects the old repository URL.
10. **Unused multi-channel machinery.** `delivery_channels`, per-vendor advisory locks and channel fallback exist for several vendors. Recommendation: leave the tables and code in place with one channel per account and remove only the Discord, Zulip and Matrix specifics. Rejected alternative: deleting the channel model now, which would rewrite the dispatcher and its crash tests for no user-visible gain.

## 14. What was not checked, and the risk

1. No build or test was run. The figures in sections 2 and 3 are from committed evidence dated 2026-10-15; the tree could have regressed since, although `main` has no later commit.
2. `JdaTransport.scala`, `Renderer.scala`, `WizardEngine.scala`, `MenuHandler.scala`, `DoseIntakeHandler.scala`, `Decide.scala`, most repositories and most test files were classified from headers, signatures and documentation rather than read line by line. A hidden Discord assumption inside a handler is possible; the 20 `discord` occurrences in `core/src` were all inspected and are comments, one profile, markup branches, token buckets and sample copy.
3. Open GitHub issues and pull requests were not queried. Issues #2, #7, #9, #10 and #12 are known only from documents.
4. Telegram behaviour beyond the eleven verified statements of section 12 was not researched here.

Next action for the design agents: decide open question 2 (shape of the chat boundary) first, because the contract revision in section 4 and every module verdict in section 6 depend on it.
