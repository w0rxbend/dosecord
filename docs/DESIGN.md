# Dosecord Design
Status: Accepted 2026-09-11
Plan and decisions: the ordered delivery plan is docs/ROADMAP.md and the thirteen decision records this design rests on are in docs/adr/.
Supersedes: the retired architecture, production-plan, research-summary, and API documents. M0.0 archives or replaces those documents; this design and the roadmap are now authoritative.
Inputs: docs/design-tracks/unified-chat-api.md and docs/design-tracks/reminder-reliability.md (language-agnostic derivations).
---

# Dosecord Architecture and Technology Stack (revision 2, after adversarial review)

Status: decided 2026-09-11. Supersedes revision 1 of this document, docs/ARCHITECTURE.md, the Kafka sections of docs/PRODUCTION_PLAN.md and docs/SWARM_RESEARCH_SUMMARY.md. docs/MEDICATION_REMINDER_UX.md remains the product specification; the lifecycle questions it left open are resolved in section 7 and must be appended to it.

## 1. Decision in one paragraph

Dosecord becomes a single Scala 3 direct-style process (splittable by role) on JDK 25 virtual threads under Ox structured concurrency, on top of PostgreSQL 18. The bot core exposes one vendor-neutral chat API (inbound events, an abstract outbound message model, signed interaction callbacks) through a `ChatMediator`; vendor adapters (Discord first via JDA; Telegram, Zulip, Matrix next) implement a small `ChatAdapter` trait and declare a capability profile; the core's renderer degrades one message model deterministically per profile. Reminders are driven by materialised `dose_occurrences` rows with a single `next_action_at` column claimed with `FOR UPDATE SKIP LOCKED`; every state change, action-log row and outbound message are written in one transaction; delivery goes through an outbox dispatcher with vendor-side idempotency where the vendor offers it and a bounded, flagged duplicate everywhere else. Kafka, ZooKeeper and Kafka UI are deleted; the Python services are deleted once the Discord adapter reaches parity.

This revision changes the implementation language from Python to Scala 3 (ADR-001 records why, with the corrected facts and scoring) and fixes every accepted finding from the three adversarial reviews: interaction acknowledgement policy (modals, ephemerality, Telegram's single-shot answer), vendor-owned outbox dispatch, an outbox that can edit and react, two-phase recording on the reaction tier, cross-revision occurrence uniqueness, revisioned pause/resume, chain doses, quiet-hours defer, snooze and undo semantics, per-row savepoints in the loop, per-instance heartbeats, delivery-evidence-based `unknown`, epoch in `send_key`, corrected DST golden data and a `dst_kind` column.

## 2. Stack

| Concern | Choice | Version (verified 2026-09-11 unless noted) |
|---|---|---|
| Language / runtime | Scala 3.8.x on JDK 25 LTS (virtual threads, ScopedValue), `-Werror -Wunused:all -deprecation` | Temurin 25.0.4 installed locally; Scala 3.8.4 is what the owner's repos pin (patch not re-fetched) |
| Build | Mill, one `build.mill`, module graph = dependency graph; scalafmt + scalafix in CI | Mill 1.1.9 (2026-09-07) |
| Concurrency | Ox direct style: `supervised` scopes, `fork`, `Channel`, `ForkLocal`, `retry`, `timeout`; `Either`/`boundary` for typed errors | Ox 1.0.6 (2026-07-23) |
| JSON / contracts | upickle `ReadWriter` derivation on Scala 3 enums and case classes; opaque types for ids; validation in smart constructors | upickle 4.4.3 |
| Database | PostgreSQL 18 (`postgres:18-alpine`) | 18.6 (2026-08-11), supported to 2030-11 |
| DB access | pgjdbc + HikariCP; hand-written SQL through a ~150-line in-repo `sql"..."` interpolator and row mappers; Flyway migrations (plain SQL, expand/contract) | pgjdbc 42.7.13, HikariCP 7.1.0, Flyway 13.6.0; ScalaSql 0.3.2 is an option for read models later, not used in v1 |
| Web edge | Tapir `tapir-netty-server-sync` (direct style, Java 21+, Scala 3 only): `/healthz`, `/readyz`, `/metrics`, `/link/{token}`, later REST with OpenAPI | Tapir 1.13.31 |
| HTTP client (Zulip, vendor probes) | sttp client4 `DefaultSyncBackend` | 4.0.26 |
| Discord | JDA (slash commands, buttons, selects, modals, `IntegrationType.USER_INSTALL`, `deferReply(ephemeral)` / `deferEdit`, `MessageCreateAction.setNonce` which writes `enforce_nonce: true` to the payload — verified in `MessageCreateActionImpl`) | 6.6.0 (2026-09-06); library targets Java 8, examples Java 25 |
| Telegram | TelegramBots (`org.telegram:telegrambots-longpolling`, `telegrambots-client`), plain Java, OkHttp | 10.3.0 (2026-09-07); telegramium 10.1002.0 rejected (cats-effect, contradicts direct style) |
| Zulip | hand-rolled client (~250 lines) on sttp + upickle: register/get_events long poll, send, add/remove reaction, update_message, `BAD_EVENT_QUEUE_ID` re-register | – (Zulip has no maintained client on any JVM language; the official Python client is a sync `requests` wrapper, so this is the same work everywhere) |
| Matrix | Trixnity `trixnity-client` + `trixnity-crypto-driver-vodozemac` + `trixnity-client-repository-exposed` (JVM, Apache-2.0, E2EE), called from a small Kotlin shim module compiled by Mill `kotlinlib`; `runBlocking` on a virtual thread; `kotlinx-coroutines-core-jvm` | Trixnity 5.8.1 (2026-09-01, group `de.connect2x.trixnity`; pluggable crypto driver since 5.0.0 on 2026-01-23); kotlinx-coroutines 1.11.0. Exposed-repository-on-Postgres is not verified for 5.x; SQLite file on a named volume is the fallback |
| Passwords / tokens | Bouncy Castle `Argon2BytesGenerator` (Argon2id, pure JVM) | bcprov-jdk18on 1.86 (2026-09-11) |
| Time | `java.time` (`ZoneRules.getTransition`, `ZonedDateTime.ofLocal`); JDK tzdb version exported as a metric; JDK patch cadence brings tzdata | – |
| Logging / tracing / metrics | scribe + scribe-slf4j2 bridge (JDA, Hikari, Flyway, pgjdbc, Ktor log through slf4j); context via Ox `ForkLocal` (ScopedValue); OpenTelemetry Java agent (JDBC, HikariCP, OkHttp, Netty, Ktor auto-instrumented) + `opentelemetry-api` manual spans; micrometer Prometheus registry on `/metrics` | scribe 3.19.0; otel javaagent 2.31.1; micrometer 1.17.x (1.18.0-M1 is a milestone; 1.17 patch not re-fetched) |
| Tests | munit + munit-scalacheck (properties), testcontainers-scala Postgres 18, in-repo golden-file helper for renderer snapshots | munit 1.3.6, munit-scalacheck 1.3.1, testcontainers-scala 0.44.1 / testcontainers 1.21.4 |
| Scheduler | none (bespoke, section 7) | Quartz/db-scheduler rejected for the same reason APScheduler was: they model jobs, not this lifecycle |
| Container | `eclipse-temurin:25-jre-alpine`, Mill `assembly`, `-XX:+UseSerialGC -Xmx256m -XX:+AutoCreateSharedArchive`; native-image via Mill `NativeImageModule` is a later experiment | ~180-250 MB RSS expected; native-image feasibility with JDA/Netty/Ktor reflection not verified |

Vendor facts verified this session: Discord `nonce` ≤ 25 chars, `enforce_nonce` dedupes only "in the past few minutes"; the former Python adapter and JDA both send `enforce_nonce: true` when a nonce is set; Telegram Bot API 10.x, `callback_data` 1-64 bytes, no edit age limit on bot-authored messages, `deleteMessage` only within 48 h, no-op edits return 400 "message is not modified", `answerCallbackQuery` is single-shot and the query expires after roughly 10 s (exact figure unpublished); Zulip default edit window 10 min, `REACTION_ALREADY_EXISTS` on duplicate reactions, removing own reactions has no time limit; Matrix `M_DUPLICATE_ANNOTATION` on duplicate reactions, Synapse default `rc_message` 0.2/s burst 10 per account; Europe/Kyiv 2026-03-29 gap is 03:00-03:59 (02:30 exists), 2026-10-25 03:30 is ambiguous; Australia/Lord_Howe gap is 02:00-02:29.

## 3. Repository and module layout

One repo, one Mill build. Dependency direction is the Mill module graph, so "adding a vendor touches zero core files" is enforced by the build: a `core` module cannot see an adapter because it does not depend on it.

```
build.mill                     # modules, scalacOptions (-Werror), scalafmt/scalafix, assembly, (later) NativeImageModule
contracts/                     # pure data: enums + case classes + upickle ReadWriters, no I/O, no JDK I/O imports
  Envelope.scala Actors.scala  # CloudEvents shape transcribed from shared/contracts
  Commands.scala Events.scala  # sealed hierarchies replacing registry.py
  Schedule.scala               # enum Rule, ReminderPolicy, QuietHours
  Chat.scala                   # InboundEvent, OutboundMessage, Block, CallbackToken, CapabilityProfile, ChatError
core/
  domain/                      # pure: occurrence FSM (decide), materialiser, adherence, copy catalogue
  application/                 # command handlers, wizard Flows, NLU router, queries, identity flows
  chat/                        # ChatMediator, Renderer (ladders), CallbackCodec, ChatAdapter trait, InteractionHandle
  scheduling/                  # ReminderLoop, Materializer, OutboxDispatcher, CatchUp
  ports/                       # Clock, UnitOfWork, repositories, IdentityResolver (traits)
infra/
  db/                          # HikariCP, Sql interpolator, repositories implementing core ports, LISTEN connection
  db/migrations/V*.sql         # Flyway; V1 = today's 13 tables corrected + the tables in section 8
  Settings.scala               # env parsing into a case class; generates .env.example
  Telemetry.scala              # scribe, OTel spans, micrometer
adapter-console/               # stdin/stdout, ships in slice 1
adapter-discord/               # JDA
adapter-telegram/              # TelegramBots
adapter-zulip/                 # sttp hand-rolled client
adapter-matrix/                # Kotlin shim (Mill kotlinlib) over Trixnity; Scala trait implemented in Kotlin
app/                           # composition root: `dosecord run [--role gateway|worker|all]`, `migrate`, `admin`, Tapir web
tests/conformance/             # JSON scenario set every adapter must pass
```

Module dependencies: `contracts` depends on upickle only; `core` on `contracts` and Ox; `infra` on `core` + `contracts` + JDBC/Flyway; each `adapter-*` on `contracts` and `core` (chat traits only) and never on `infra` or another adapter; `app` on everything. A CI grep additionally asserts that no `net.dv8tion`, `org.telegram`, `de.connect2x`, `kotlinx` or `sttp` symbol appears under `core/` or `contracts/`. Adapters are discovered by the composition root from `Settings` (one adapter enabled per present credential set); there is no runtime plugin mechanism in v1.

Process model: `dosecord run` opens one Ox `supervised` scope and forks: one gateway fork per enabled adapter, the reminder loop, the materialiser, one outbox dispatcher per owned vendor, the sweeper and the LISTEN connection. Any fork failure fails the scope and the process restarts (supervision is the recovery model). `--role gateway` runs adapters plus their vendors' dispatchers; `--role worker` runs the reminder loop, materialiser and sweeper. Dispatch for a vendor always runs in the process that hosts that vendor's client (section 7.6), so Matrix E2EE and Discord sends never leave the gateway. Each process takes `pg_advisory_lock(hash('vendor:'+name))` per vendor it owns at startup, so two processes cannot own the same vendor. Because everything blocks on virtual threads, a slow Zulip call or a stuck Matrix `/sync` stalls only its own fork; the role split is for isolation and scaling, not for stall protection.

## 4. The unified chat API

Design principle: adapters are dumb and stateless apart from a resume cursor; the mediator owns identity stamping, callback verification, acknowledgement timing, presentation memory for degraded UIs, rendering, delivery routing and per-chat ordering; the core never sees a vendor except as an opaque string it stores and hands back.

### 4.1 Inbound event model (`contracts/Chat.scala`)

```scala
final case class PlatformIdentity(vendor: String, vendorUserId: String, displayName: Option[String])
final case class ChatRef(vendor: String, chatId: String, threadId: Option[String])
final case class MessageHandle(vendor: String, chatId: String, messageId: String, revision: Int = 0)
final case class Principal(identityId: UUID, accountId: Option[UUID], linked: Boolean)   // stamped by the mediator only

enum Inbound derives ReadWriter:
  case MessageReceived(text: String, replyTo: Option[MessageHandle], truncated: Boolean)
  case CommandInvoked(name: String, args: Map[String, String], raw: String)
  case InteractionSubmitted(callback: CallbackRef, values: List[String], source: Option[MessageHandle])
  case FormSubmitted(formId: String, callback: CallbackRef, fields: Map[String, String])
  case ReactionChanged(emoji: String, target: MessageHandle, added: Boolean)
  case ConversationStarted
  case AccountLinkCompleted(linkId: UUID, accountId: UUID)
  case AdapterLifecycle(state: LifecycleState, fromCursor: Option[String])

final case class InboundEvent(
  eventId: UUID, vendor: String, vendorEventId: String,        // discord:interaction:{id} | discord:message:{id} | telegram:update:{id} | zulip:{queue}:{id} | matrix:{event_id}
  receivedAt: Instant, createdAt: Option[Instant],             // createdAt from the Discord snowflake / Telegram date, used for ack budgets
  actor: PlatformIdentity, chat: ChatRef, cursor: Option[String],
  interaction: Option[InteractionHandle],                      // opaque; the mediator acks through it, the core never inspects it
  principal: Option[Principal], body: Inbound)
```

`(vendor, vendor_event_id)` is the PRIMARY KEY of `inbound_events`; a duplicate is acked to the vendor and the stored reply is replayed. Text is NFC-normalised, trimmed and capped at 4 000 chars.

`InteractionHandle` is the one vendor-timing abstraction and its ack path is explicit:

```scala
trait InteractionHandle:
  def deferUpdate(): Unit                          // component interactions
  def deferReply(ephemeral: Boolean): Unit         // command interactions; flag is fixed here
  def answer(toast: Option[String]): Unit          // Telegram answerCallbackQuery; final
  def respond(rendered: RenderedMessage): MessageHandle
  def editSource(rendered: RenderedMessage): Unit
  def openForm(form: RenderedForm): Unit           // only while un-acked; Discord modal
  def acked: Boolean
```

### 4.2 Outbound message model

```scala
enum Inline: case Text(s: String); case Bold(s: String); case Italic(s: String); case Code(s: String); case Link(label: String, url: String); case LineBreak; case Emoji(name: String); case Time(at: Instant, tz: ZoneId, style: TimeStyle)
enum Node:   case Paragraph(inlines: List[Inline]); case BulletList(items: List[List[Inline]]); case CodeBlock(s: String)
type RichText = List[Node]                                        // an AST, never a vendor string; no headings/tables/images

final case class Choice(label: String, callback: CallbackToken, style: ChoiceStyle = Secondary, emoji: Option[String] = None, hotkeys: List[String] = Nil)
final case class ChoiceSet(id: String, prompt: Option[RichText], choices: List[Choice], layout: Buttons | Select = Buttons, minSelect: Int = 1, maxSelect: Int = 1, ttl: Option[Duration] = None)
final case class Field(key: String, label: String, tpe: FieldType, required: Boolean = true, placeholder: Option[String] = None, options: List[Choice] = Nil)
final case class Form(id: String, title: String, fields: List[Field], submit: CallbackToken)      // <= 5 fields (Discord modal cap)
final case class Notice(level: Info | Success | Warning, text: RichText)
enum Block: case Choices(cs: ChoiceSet); case FormBlock(f: Form); case NoticeBlock(n: Notice)

final case class OutboundMessage(
  body: RichText, blocks: List[Block] = Nil,
  visibility: Persistent | Ephemeral = Persistent,
  importance: InteractionReply | Reminder | Info | Bulk = Info,   // rate-limit priority + notification hints
  replaces: Option[MessageHandle] = None, deleteAfter: Option[Duration] = None,
  discreet: Boolean = false, silent: Boolean = false,              // silent -> Discord SUPPRESS_NOTIFICATIONS / Telegram disable_notification
  dedupeKey: String, correlationId: String)
```

Operations the core may request through `ChatPort` (implemented by the mediator): `send(target, msg)`, `edit(handle, msg)`, `finalize(handle, summary, keep: Option[ChoiceSet])` ("remove controls, show outcome"), `delete(handle)`, `ack(interaction, toast)`, `react(handle, emoji, on)`, `typing(chat)`, `registerCommands(specs)`. `send` is idempotent on `dedupeKey`; `edit`/`finalize` on `(handle, revision)` and by content.

`CommandSpec(name, description, args, visibility: Persistent | Ephemeral, opensForm: Boolean)` is static per command and shipped with `registerCommands`, because Discord fixes the ephemeral flag at defer time.

### 4.3 Capability profile and degradation ladders

```scala
final case class CapabilityProfile(
  nativeCommands: Boolean, buttons: Boolean, select: Boolean, modal: Boolean, ephemeral: Boolean,
  editOwn: Any | Window | NoEdit, deleteOwn: Any | Window | NoDelete, editWindow: Option[Duration], deleteWindow: Option[Duration],
  botReactions: Boolean, reactionEvents: Boolean,
  transientAck: Boolean, deferrable: Boolean, ackDeadline: Option[Duration],
  polls: Boolean, silentDelivery: Boolean, canInitiateDm: Always | AfterUserStart | SharedGuildOrInstall | Invite,
  maxText: Int, maxChoicesPerRow: Int, maxRows: Int, callbackBudgetBytes: Option[Int], eventsPerMessageBudget: Int,
  markup: DiscordMd | TelegramHtml | ZulipMd | MatrixHtml | Plain)
```

Profiles: Discord (buttons, select, modal, ephemeral as interaction response, edit/delete any age on own messages, 5x5 rows, 100-char custom_id, deferrable with a 3 s deadline measured from the interaction's creation, 2 000 chars). Telegram (inline keyboard ≤ 4 per row, no select/modal/ephemeral, edit any age, delete within 48 h (`deleteOwn = Window(48h)`), `transientAck = true, deferrable = false`, 64-byte callback_data, 4 096 chars, `disable_notification`, DM only after `/start`). Zulip (no buttons; bot reactions and reaction events; edit within the realm window probed at start, default 10 min; remove-own-reaction always allowed; 10 000 chars; can DM anyone in the realm; `eventsPerMessageBudget = 4`). Matrix (no buttons; reactions both ways; `m.replace` edits; polls off by default; invite-based DM; E2EE; `eventsPerMessageBudget = 4` because of Synapse's burst of 10). Console (text only).

`Renderer.render(message, profile): (List[VendorOp], RenderReport)` is a pure function, golden-tested per profile. Ladders, first supported rung wins:

- ChoiceSet(buttons): native buttons → native select (Discord, > 10 choices) → numbered list plus bot-added digit reactions on its own message with a `rendered_messages.choice_map` row `{index | label | emoji -> CallbackToken}`, at most `eventsPerMessageBudget - 1` reactions (reminders on this tier render Taken, Snooze[default], Skip as reactions; other snooze durations, notes and corrections go through the numbered-reply path) → numbered list only.
- ChoiceSet(select): native select → buttons paged 5 per row → rungs 3-4 above.
- Form: native modal (Discord, only while the interaction is un-acked; section 4.6 guarantees that for `opensForm` actions) → mediator `FormRunner` asks one field per message, keeps partial answers in `form_runs`, emits one `FormSubmitted`; the core never learns which happened. A fallback from rung 1 to rung 2 caused by a missed ack deadline is logged and counted (`dosecord_form_degraded_total{reason}`).
- Ephemeral: native (Discord, live interaction only) → persistent + `deleteAfter = 60 s` where delete is allowed within its window → persistent + Notice "not kept".
- Edit/finalize: native edit → on the reaction tier, remove the bot's own reactions on the target (always permitted on Zulip and Matrix), then reply-quote the outcome, keep the `choice_map` row but set `controls_removed_at` so a late reaction gets "already recorded" → send replacement replying to the old, delete old if allowed, record `superseded_by` → send replacement only.
- Silent: vendor flag where it exists, otherwise ignored.
- Discreet: the core puts "your 09:00 dose" in the body; the renderer wraps any remaining medication name in the vendor spoiler and suppresses previews.
- Text over `maxText`: split at paragraph boundaries; blocks attach to the last chunk. Digests are capped at 8 doses × 3 buttons per Discord message (25-component cap) and rendered as one numbered message with a single reaction set on the reaction tier; longer digests are paged.
- Commands: native registration (Discord slash with `IntegrationType.USER_INSTALL` + `GUILD_INSTALL` and bot-DM context; Telegram `setMyCommands`) → text `/command` parsed by the mediator. `/taken`, `/snooze <min>`, `/skip`, `/today` exist on every vendor as the universal escape hatch.

### 4.4 Interaction callbacks (payload format)

Every tappable control carries a `CallbackToken` that fits Telegram's 64 bytes and Discord's 100 chars, is unforgeable, survives restarts and days-old messages, and never depends on the message being readable.

```
raw = 34 bytes -> base64url without padding = 46 chars; wire form = "dc:" + 46 = 49 chars (Discord custom_id and Telegram callback_data alike)
byte 0      version (4 bits) | key_id (4 bits)         # two active HMAC keys, rotated
byte 1      mode: 0 = direct, 1 = slot
bytes 2-3   action: UInt16 from a registry; each entry declares opensForm, visibility, requiresSession
            (1 dose.taken, 2 dose.snooze, 3 dose.skip, 4 dose.undo, 5 dose.note[opensForm], 6 dose.correct[opensForm],
             7 dose.keep_missed, 20 menu.open, 30 wizard.step, 31 wizard.text_step[opensForm], 32 wizard.confirm, 40 link.confirm ...)
bytes 4-19  subject: 16-byte UUID (occurrence id in direct mode; callback_slots.id in slot mode)
bytes 20-25 value: 6 bytes, zero-padded (snooze minutes, choice index, step_seq, enum code)
bytes 26-33 MAC: HMAC-SHA256(server_key[key_id], bytes 0..25)[:8]
```

Direct mode is stateless: a reminder from last week still works because occurrence ids never expire and the transition is idempotent. Slot mode points at `callback_slots {id, account_id, chat, payload jsonb, session_id, step_seq, expires_at}` for wizard/menu payloads larger than 6 bytes; reminder follow-up slots never expire, menu/wizard slots expire with the session. The mediator verifies the MAC before anything reaches the core; a bad MAC yields a toast "This button is no longer valid" and an audit row. Discord routes by the `dc:` prefix on `getComponentId` / modal custom id (JDA needs no view registration: buttons, selects and modal submits arrive as plain events keyed by custom id, so persistence across restarts is free). A property test asserts every valid token matches the `^dc:[A-Za-z0-9_-]{46}$` shape and fits both budgets. Zulip and Matrix never receive tokens; they resolve through `rendered_messages.choice_map`.

### 4.5 Adapter trait

```scala
sealed abstract class ChatError(msg: String, val channelFatal: Boolean = false) extends Exception(msg)
final case class Retryable(msg: String) extends ChatError(msg)
final case class RateLimited(retryAfter: Duration) extends ChatError("rate limited")
final case class Unreachable(msg: String) extends ChatError(msg, channelFatal = true)   // DM closed, blocked, never /start-ed, no shared guild, M_FORBIDDEN on the DM room, Discord 50007
final case class TooOld(msg: String) extends ChatError(msg)
final case class Unsupported(op: String) extends ChatError(op)                          // conformance failure if the profile claimed support
final case class Permanent(msg: String, override val channelFatal: Boolean) extends ChatError(msg, channelFatal)

trait ChatAdapter:
  def vendor: String
  def capabilities: CapabilityProfile
  def start(sink: InboundSink, resumeFrom: Option[String])(using Ox): Unit    // forks the gateway inside the caller's scope
  def stop(): Unit
  def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle
  def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle
  def delete(handle: MessageHandle): Unit
  def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit
  def registerCommands(specs: List[CommandSpec]): Unit
  def resolveChat(identity: PlatformIdentity): ChatRef
  def renderText(text: RichText): List[String]
```

Adapter contract rules (each is a conformance case): "message is not modified" (Telegram), `REACTION_ALREADY_EXISTS` (Zulip) and `M_DUPLICATE_ANNOTATION` (Matrix) map to success; "message can't be deleted" / "message to edit not found" map to `Permanent(channelFatal = false)`; only "bot was blocked", "chat not found", Discord 50007 and `M_FORBIDDEN` on the DM room are `channelFatal`. An adapter must not parse intents, hold conversation state, decide identity, retry beyond one `Retry-After`, invent fallbacks, or call the core. All methods block; they run on virtual threads.

### 4.6 Mediator flow and acknowledgement policy

Acknowledgement happens before any database work and outside the per-chat ordering executor:

1. The adapter builds the `InboundEvent` with `createdAt` from the vendor (Discord snowflake timestamp). For an interaction, the mediator decodes and MAC-verifies the token (pure, no I/O) and looks up the action registry entry, then acks according to the entry: component action with `opensForm = false` → `deferUpdate()`; command with declared visibility → `deferReply(ephemeral)`; `opensForm = true` → no ack, the mediator resolves the principal with one indexed read and calls `openForm` inside the budget, and the handler that needs the database runs on `FormSubmitted`; Telegram → `answer(toast)` where the toast is computed from cheap checks (bad MAC, ownership, already-resolved epoch read with one indexed query) or none. The adapter runs a watchdog from `createdAt` with a 1.5 s target (Discord) and 2 s (Telegram) that acks with the same declared type if the mediator has not; the watchdog is disabled for `opensForm` tokens and if the deadline passes the Form is rendered through `FormRunner` and counted. `dosecord_interaction_ack_latency_seconds{vendor}` is measured from `createdAt`.
2. The event enters a keyed executor (`vendor:chatId` striped over N single-consumer Ox channels) so per-chat order is preserved while chats run in parallel.
3. Insert `inbound_events` (dedupe; on conflict replay the stored reply and stop).
4. `IdentityResolver.resolve(actor)` stamps the `Principal` (account id never comes from the wire).
5. Resolution rules: `ReactionChanged` and numbers in a reply that quotes a bot message resolve through `rendered_messages.choice_map` by the target handle unconditionally (the FSM and epoch then decide "already recorded" / "no longer valid"); `ReactionChanged(added = false)` is ignored; bare text digits and hotkeys resolve against the latest pending prompt only, so "2" is not eaten while a wizard asks for a dose.
6. Dispatch to exactly one of: active wizard step, command handler, action handler, NLU router, help.
7. The handler returns `Reply(replace: Option[OutboundMessage], followUps, toast, sessionPatch, domainEvents)`.
8. One transaction commits the state change, action rows, `domain_events`, outbox rows and the processed reply. If a `replace` can be delivered synchronously through a live interaction handle, the mediator delivers it after commit and records `sent` plus the handle in a follow-up write; the outbox row for it is created with `next_attempt_at = now + 30 s` so the dispatcher only claims it if that follow-up write is lost, and it is an `edit` op targeting the source handle (idempotent by content), never a new send. On Telegram, a toast produced after the single-shot answer is degraded to a short message with `deleteAfter = 30 s` or folded into the edited reminder text. If a handler returns a visibility different from the command's declared one, the reply goes as a follow-up message (which carries its own flag) instead of editing the deferred response.

Conversation state (`conversation_sessions`) is owned by the core; wizards are declarative `Flow(steps: Map[String, Step(prompt, accepts, validate, next)])` run by one engine that loads the row `FOR UPDATE`, applies the input, bumps `step_seq` and `version`, finalizes the old prompt and sends the next. Tokens inside flows carry `step_seq`; a stale tap gets a toast and the current prompt again. Sessions expire after 30 minutes idle with one "Still there?" grace prompt. Direct-mode dose tokens bypass sessions, so a reminder tap mid-wizard works.

## 5. Adapters per vendor

Discord (JDA 6.6.0): slash commands registered globally with `IntegrationType.USER_INSTALL` and `GUILD_INSTALL` and bot-DM interaction context so a user-installed app can be DMed without a shared guild (keep the "home guild" setup step documented until the user-install DM path is smoke-tested). No prefix commands, no message-content intent. Components and modals map 1:1; modal submits are `ModalInteractionEvent`s routed by custom id, so a modal opened before a restart still submits. `MessageCreateAction.setNonce(base64url(sha256(sendKey)).take(22))` sends `enforce_nonce: true` (a fixture test asserts the JSON body). DM channel id cached on `platform_identities.dm_channel_id`. `Unreachable` on 50007. JDA's own event threads hand every event to an Ox fork immediately; nothing blocks JDA's gateway thread. Gotchas: 3 s from creation, 15-minute token, 100-char custom_id, 25 select options, 5x5 rows.

Telegram (TelegramBots 10.3.0): long polling (one poller per token); `InlineKeyboardMarkup` with 49-char tokens; `answerCallbackQuery` exactly once per query (single-shot); HTML parse mode; `ForceReply` for text steps; edits on own messages at any age; deletes only within 48 h; `Unreachable` on 403 blocked / never started; `RetryAfter` → `RateLimited`; "message is not modified" → success.

Zulip (hand-rolled on sttp): `register(event_types=[message, reaction], narrow=[[is, dm]])`, long-poll `get_events` in a fork, re-register on `BAD_EVENT_QUEUE_ID`; reminder rendered as a numbered legend with at most 3 pre-added reactions; the bot ignores its own reaction events by user id; finalize = remove own reactions + reply-quote (edit within the probed window when possible); Zulip markdown; `/poll` widgets unused.

Matrix (Trixnity 5.8.1 through a Kotlin shim module): `trixnity-client` with the vodozemac crypto driver; the Trixnity repository (Exposed on the same Postgres in a `matrix` schema if verified in the slice-7 spike, else SQLite on a named volume backed up by the same job as `pg_dump`) holds device keys, Megolm sessions and the sync batch token together, so keys and token cannot diverge on restore; startup compares the repository's last sync with the last inbound Matrix event in Postgres and refuses to run if they diverge by more than a threshold. Invites for rooms with `is_direct = true` are accepted from any MXID (optional homeserver allowlist, per-sender invite rate limit, leave after 10 minutes without a message) and produce `ConversationStarted`; non-direct rooms are joined only when invited by a linked MXID. Reactions via `m.reaction` with `txnId = s"$sendKey:r$i"`; edits via `m.replace`; `formatted_body` HTML; `txnId = sendKey` on sends gives server-side idempotency scoped to the access token; MSC3381 polls behind the `polls` flag, off by default. The shim exposes the Scala `ChatAdapter` trait implemented in Kotlin with `runBlocking` on the calling virtual thread and collects Trixnity flows inside an Ox fork. Escape hatch if the spike fails: the adapter trait is JSON-serialisable, so a Matrix adapter can run out of process over a local Unix socket without changing the core.

Console: stdin/stdout, numbered options, ships in slice 1 as the proof that the boundary holds.

## 6. Identity and auth

Tables: `users`, `platform_identities` (unique `(vendor, vendor_user_id) WHERE unlinked_at IS NULL`, unique `(user_id, vendor)` likewise, `dm_channel_id`, `last_seen_at`), `user_credentials` (Argon2id `m=64MiB,t=3,p=1` via Bouncy Castle, `failed_login_count`, `locked_until`), `auth_challenges` (`kind setup|link|restore`, `token_hash` = SHA-256 of a 256-bit URL token, 10-minute `expires_at`, single-use `consumed_at`, `created_ip`), `recovery_codes` (hashed, `used_at`), `auth_audit_log` (append-only), `api_tokens` (later, hashed, scoped).

Flows (all in `core/application/identity`, never in adapters): create account from chat links the current identity in one transaction; link existing from a new vendor issues a challenge and the bot sends `https://<PUBLIC_BASE_URL>/link/<token>` plus an 8-char code the user can type into an already-linked chat on another vendor; `POST /link/{token}` (Tapir, server-rendered form, CSRF double-submit, generic errors, per-IP and per-challenge rate limit via a Postgres token bucket) consumes the challenge, inserts the identity, writes the audit row and injects `AccountLinkCompleted` into both chats through the normal inbound sink; restore uses recovery codes through the same shape. Passwords are never accepted in chat. Unlinked principals reach only identity flows and help. Per-account delivery preferences: `delivery_channels {platform_identity_id, role primary|fallback|off, priority, state healthy|degraded|dead, last_ack_at}` and `delivery_policy primary_then_fallback|broadcast`.

## 7. Scheduling and reminder engine

Principle: the database is the schedule, one loop, one column.

### 7.1 Schedule rules and revisions

`schedule_revisions.rule` is a Scala 3 `enum Rule derives ReadWriter` stored as jsonb and validated on every write: `FixedTimes(slotGroups: List[(days, times)])`, `EveryNDays`, `EveryNWeeks`, `Cycle(onDays, offDays, times, anchorDate)`, `IntervalFixedStart(anchorTime, intervalMinutes, maxPerDay, activeDays)`, `Chain(anchor: FirstTaken | EachTaken, intervalMinutes, dosesPerDay, dayStart, dayCutoff, firstDosePromptTime)`, `Taper(phases)`, `AsNeeded(maxPerDay, minGapMinutes)`, `Paused`. MVP implements `FixedTimes` and `AsNeeded`; the enum is exhaustive, so a new kind fails compilation in every `match` under `-Werror`. Validation: taper phases contiguous and non-overlapping, inner rule not `Taper`/`Chain`; `Cycle` stores `anchorDate` inside the rule so the phase survives edits and pauses (pause is calendar-transparent).

`slot_key` is stable across revisions for unchanged wall times: `t0900` for fixed times, `int:{k}` for interval slots, `chain:{k}:{anchorActionSeq}` for chain doses, `manual:{uuid}` for as-needed logs.

`ReminderPolicy` defaults: `initialOffsetMinutes = 0`, `repeatEveryMinutes = 10`, `maxReminders = 3` (counts the initial reminder: sent at +0, +10, +20), `missAfterMinutes = 120`, `onTimeGraceMinutes = 60`, `snoozeOptionsMinutes = [10, 30, 60]`, `maxSnoozes = 3`, `missAfterSnoozeMinutes = 30`, `maxLateMinutes = 360` (bounds snooze), `lateLogWindowMinutes = 1440`, `undoWindowMinutes = 15`, `quietHoursMode = deliver | defer | silent`, `discreet = false`. Quiet hours live on the user and may be overridden per schedule. Derived instants: `due_window_start = scheduled_for + initialOffset`, `due_window_end = scheduled_for + onTimeGrace` (extended on snooze to `snoozed_until + onTimeGrace`), `miss_deadline = scheduled_for + missAfter`.

Schedule edits create append-only `schedule_revisions`. Pause and resume are revisions too (`Paused` rule; resume copies the prior rule), never in-place flags. In the edit transaction: lock the schedule's open rows (`SELECT ... FOR UPDATE`, not SKIP LOCKED, so a concurrent tick is waited for), cancel old-revision pending rows with `scheduled_for >= effective_from` as `superseded` (or `paused`), leave due/snoozed rows living under the old revision so their message controls keep working, insert the new revision, and materialise it. `effective_from` defaults to the next local midnight when any of today's slots under the old revision is already resolved or due (the wizard says "today's 09:00 is already recorded; the new plan starts tomorrow"), and the user may override to "now". Old-revision pending rows whose `scheduled_for` is earlier than the first candidate the new revision produces are kept, not cancelled, and the wizard asks ("Keep tonight's dose at 21:00 New York (04:00 Kyiv) or skip it?"), which closes the eastward-move gap. Each schedule stores its own IANA zone with `tz_follows_user`; a user timezone change creates revisions only for following schedules and asks about the others.

### 7.2 Materialisation and time

`occurrences(revision, fromUtc, toUtc): List[Candidate(localDate, slotKey, scheduledFor, dstKind, doseSnapshot)]` is a pure function over local dates in the schedule zone.

```scala
enum DstKind: case None, Gap, Fold
def resolveLocal(d: LocalDate, t: LocalTime, zone: ZoneId): (Instant, DstKind) =
  val ldt = LocalDateTime.of(d, t)
  Option(zone.getRules.getTransition(ldt)) match
    case scala.None      => (ldt.atZone(zone).toInstant, DstKind.None)
    case Some(tr) if tr.isGap => (ZonedDateTime.ofLocal(ldt, zone, null).toInstant, DstKind.Gap)   // java.time shifts forward by the gap length, same as zoneinfo fold=0
    case Some(tr)        => (ZonedDateTime.ofLocal(ldt, zone, tr.getOffsetBefore).toInstant, DstKind.Fold)   // earlier instant
```

`dst_kind`, `local_date`, `local_time`, `tz` are stored on the row so the audit can explain both "fired at 04:30 because 03:30 did not exist" and "chose the first 03:30". `IntervalFixedStart` is per-local-day: the anchor is resolved on each local date and `k * interval` offsets are generated within `[anchor, next day's anchor)`; on transition days the last gap is 23 h or 25 h and the wizard says so. `Chain` uses pure instant arithmetic from the anchor dose's `effective_at` ("every 6 h after the previous dose"). The rolling horizon is 48 h; the materialiser runs every 15 minutes and on demand, inserts with `INSERT ... ON CONFLICT DO NOTHING` (no conflict target, so every unique index arbitrates, including the cross-revision one in section 8), then advances `materialized_through`. A nightly verify pass recomputes `scheduled_for` for every pending row from its natural key and rewrites drift as `system:tz_rules_changed`; the JDK tzdb version is exported as a metric. The reminder loop also materialises any schedule with `materialized_through < now + 4 * TICK` as a safety net, and `min(materialized_through) < now` is an alert from day one.

### 7.3 Occurrence state machine

Statuses: `pending, due, snoozed, taken, skipped, missed, unknown, cancelled`. `unknown` means the window elapsed without evidence that a reminder was delivered; it is excluded from the adherence denominator. `decide(occ, policy, quiet, now, ctx): Transition` is a pure, exhaustive `match` over `(status, event)` (compiler-checked) and additionally table-tested over every cell. `ctx` carries `lastHealthyTick` (max of all workers' heartbeats, read once per tick) and `delivered: Boolean` (whether any outbox row for this occurrence has `sent_at`).

System rows:

| status | condition | transition | next_action_at |
|---|---|---|---|
| pending | now ≥ due_window_start, quiet mode deliver/silent or not in quiet hours | due, seq = 1, dispatch reminder (silent flag when silent mode) | min(now + repeatEvery, miss_deadline) if seq < maxReminders else miss_deadline |
| pending | now ≥ due_window_start, quiet mode defer, quiet_end < next occurrence of the schedule | stays pending; due_window_start := quiet_end; miss_deadline := max(miss_deadline, quiet_end + missAfter); action `reminder_deferred` | quiet_end |
| pending | as above but quiet_end ≥ next occurrence | falls back to silent delivery | as row 1 |
| snoozed | now ≥ snoozed_until | due, dispatch snooze reminder (not counted in seq; silent flag inside quiet hours) | min(now + repeatEvery, miss_deadline) |
| due | now < miss_deadline and seq < maxReminders | dispatch repeat (finalize old message, send new), seq + 1 | as above |
| due / snoozed | now ≥ miss_deadline and delivered | missed; dispatch missed notice (deferred to quiet_end inside quiet hours) | NULL |
| pending / due / snoozed | now ≥ miss_deadline and not delivered | unknown(reason = outage if lastHealthyTick < due_window_start else undelivered) | NULL |

Quiet hours never produce `unknown`. `reminder_deferred` now has a producer.

User rows:

- `taken` from due/snoozed/missed/unknown: if `now ≤ scheduled_for + lateLogWindow` then `taken` with `effective_at = now` (`taken_late` when `now > due_window_end`); beyond the window a direct Taken becomes the `correct` flow ("Log as taken at Tue 09:00 / at another time / cancel"), recording `effective_at` explicitly. A second Taken on a taken occurrence is a semantic no-op returning "Already recorded at 09:03".
- `skipped` with reason chips.
- `snoozed` from due, or from pending via Today: `snoozed_until = max(now, due_window_start) + N` (the button says "remind at 21:10"), bounded by the next occurrence of the same schedule minus one minute and by `scheduled_for + maxLate`; for chains bounded by `scheduled_for + interval - 1 min`; options that would pass those bounds are filtered out; `miss_deadline := max(miss_deadline, snoozed_until + missAfterSnooze)`; `due_window_end := snoozed_until + onTimeGrace`. An explicit snooze bypasses quiet-hours defer (the user chose the time) and is delivered with the silent flag inside them.
- `correct` from any resolved status, with an explicit `effective_at`.
- `undo` of the latest action within 15 minutes: transition to `due` with `next_action_at = now + repeatEvery`, `miss_deadline := max(miss_deadline, now + 2 * repeatEvery)`, `reminder_seq` unchanged, `taken_at / skipped_at / snoozed_until` set to NULL, reply "Undone. I'll check again at 11:15 [Taken] [Skip]". Undo of a chain anchor cancels its children (`cancel_reason = anchor_undone`) in the same transaction.
- `note_added` never changes status.

Chains: on any terminal transition of chain dose k (taken/skipped/missed), the same transaction creates dose k + 1 anchored at `effective_at` (taken) or `scheduled_for(k) + interval` (skipped/missed) if before `dayCutoff`, with `slot_key = chain:{k+1}:{anchorActionSeq}` so a re-Taken after undo creates a fresh child; at `dayCutoff` the sweeper inserts `dosesPerDay - created` rows as `missed` with `reason = chain_not_started` so adherence has the right denominator.

Epoch fencing: `dose_occurrences.epoch` increments on every state change and is carried in every outbox payload; a dispatcher or edit job whose epoch is stale is a no-op. Nothing is ever cancelled or raced.

Adherence: `(taken_on_time + taken_late) / (taken_on_time + taken_late + skipped + missed)` computed on `effective_at`, undefined when the denominator is 0; `unknown`, `cancelled`, open statuses and `origin = manual` are excluded; delay statistics (median, p90) exclude corrections; streaks treat no-dose days as neutral.

### 7.4 The reminder loop

```scala
final class ReminderLoop(uow: UnitOfWork, clock: Clock, wake: Wake, instance: String):
  def run()(using Ox): Unit =
    materializer.catchUp()
    forever:
      val n = tick(clock.now())
      heartbeat.touch(instance, clock.now())          // own short transaction, one row per instance
      wake.awaitOrTimeout(if n == Batch then Duration.Zero else Tick)   // LISTEN dosecord_wake shortens latency

  def tick(now: Instant): Int = uow.transaction: tx =>
    val ctx = TickContext(lastHealthyTick = tx.heartbeat.maxLastTick())
    val rows = tx.occurrences.claimDue(now, limit = Batch)      // FOR UPDATE SKIP LOCKED ORDER BY next_action_at, Batch <= 50
    rows.foreach: occ =>
      tx.savepoint:                                            // one row cannot poison the batch
        val (policy, quiet) = tx.policies.forOccurrence(occ)
        val t = decide(occ, policy, quiet, now, ctx.withDelivered(tx.outbox.deliveredFor(occ.id)))
        tx.occurrences.apply(occ, t, expectedVersion = occ.version)     // status, epoch + 1, next_action_at, error_count = 0
        tx.actions.append(occ, t.actionRow(actor = System, catchUp = t.catchUp))
        tx.outbox.cancelOlderQueued(occ.id, epoch = occ.epoch + 1)
        t.dispatches.foreach(d => tx.outbox.enqueue(d, epoch = occ.epoch + 1))   // ON CONFLICT (send_key) DO NOTHING
      .recover: e =>
        tx.occurrences.quarantine(occ.id, nextActionAt = now + 5.minutes, errorCount = occ.errorCount + 1)   // alert at error_count >= 3
        log.error(e, occurrenceId = occ.id)
    rows.size
```

`send_key = s"occ:$occurrenceId:e$epoch:s$reminderSeq:k$kind:c$channelId"` (UNIQUE) prevents double enqueue with two workers or a restart mid-loop, and because the epoch is in the key a catch-up decision produces a new row while `cancelOlderQueued` retires the stale one. All queries take `now` as a bind parameter from the injected `Clock`, never SQL `now()`. No network call ever happens inside the tick transaction. A user tap on a claimed row waits for the in-flight tick (the tap handler does `SELECT ... FOR UPDATE` on the occurrence first, then calls `decide` on the fresh row); it never fails with a version conflict.

### 7.5 Catch-up after downtime

The materialiser runs first and inserts rows whose `miss_deadline < now` directly as `unknown(reason = outage)`. `decide()` collapses to the target state as of `now` instead of replaying ticks: a row whose window passed with no delivery evidence becomes `unknown`, with evidence becomes `missed`, one still in its window gets one late reminder with `reminder_seq` set to what would have elapsed so the cadence continues. Any dispatch created when `now - next_action_at > 10 min` is folded into one `digest` per account, `send_key = s"digest:$account:$bucket"` with `bucket = floor(first next_action_at / 15 min)`; the digest payload carries `items: [(occurrence_id, epoch)]`, and the dispatcher re-reads those rows before rendering, drops resolved items and skips the send if none remain. Catch-up sends are rate-limited per vendor. The loop-equivalence property is stated precisely: for every row, the final status under continuous ticking equals the status under one jump except that `missed` with no delivery evidence becomes `unknown`; the count of `reminder_sent` actions under the jump is ≤ the count under ticking; outbox contents are compared only by the set of occurrence ids covered.

### 7.6 Outbox dispatcher and delivery

Outbox rows carry `op IN ('send','edit','finalize','delete','react')`, a `target` handle for non-send ops, `vendor` for ownership, and an `ops_done` bitmap for multi-step renders.

```scala
def dispatchOnce(now: Instant): Unit =
  val rows = uow.transaction(_.outbox.claim(now, ownedVendors, limit = 20, lease = 60.seconds))   // tx1: short claim, WHERE vendor = ANY(:owned)
  rows.foreach: row =>                                                                              // vendor calls outside any transaction
    val adapter = adapters(row.vendor)
    if occurrences.epochIsStale(row) then outbox.cancel(row.id)
    else
      try
        row.op match
          case Send =>
            val (primary, reactions) = render(row, adapter.capabilities)
            val handle = if row.opsDone.has(0) then row.handle.get
                         else
                           val h = adapter.send(row.chat, primary, sendKey = row.sendKey)
                           uow.transaction(tx => { tx.outbox.recordHandle(row.id, h, opsDone = 1); tx.renderedMessages.record(h, row, choiceMap = primary.choiceMap) })   // controls resolvable before any reaction
                           h
            reactions.zipWithIndex.filterNot((_, i) => row.opsDone.has(i + 1)).foreach: (emoji, i) =>
              adapter.react(handle, emoji, on = true, txnKey = s"${row.sendKey}:r$i")             // duplicate-annotation errors map to success
              uow.transaction(_.outbox.markOpDone(row.id, i + 1))
            uow.transaction(_.outbox.markSent(row.id, handle, now))
          case Edit | Finalize => val h = adapter.edit(row.target.get, render(row, adapter.capabilities).primary); uow.transaction(_.outbox.markSent(row.id, h, now))   // idempotent by content
          case Delete => adapter.delete(row.target.get); uow.transaction(_.outbox.markSent(row.id, row.target.get, now))
          case React  => adapter.react(row.target.get, row.emoji, on = row.on, txnKey = row.sendKey); uow.transaction(_.outbox.markSent(row.id, row.target.get, now))
      catch
        case RateLimited(after) => outbox.retry(row.id, at = now + after)
        case _: Retryable | _: TooOld =>
          if row.op == Send && row.attemptedAt.exists(now - _ > 2.minutes) then outbox.retry(row.id, at = now + backoff(row.attempts), possibleDuplicate = true)
          else outbox.retry(row.id, at = now + backoff(row.attempts))                                // 5 s .. 30 min, jitter, 8 attempts then dead
        case e: ChatError if e.channelFatal => outbox.dead(row.id, e); channels.markDead(row.channel, e); fallback(row)
        case e: ChatError => outbox.dead(row.id, e)                                               // Permanent(channelFatal = false): no channel action
```

`attempted_at` is set in tx1 before the vendor call for every vendor. Idempotent sends: Discord `nonce = base64url(sha256(sendKey)).take(22)` with `enforce_nonce` (dedupes only within a few minutes); Matrix `txnId = sendKey` (scoped to the access token). Telegram and Zulip have no server-side key. The uniform rule is therefore: a lease-expired retry of a `send` whose `attempted_at` is more than 2 minutes old sends once more and flags `possible_duplicate`; duplicates are bounded to one per crash on every vendor and made harmless because both copies carry the same occurrence token (or `choice_map` row) and the post-resolution `finalize` op is enqueued for every recorded handle of the occurrence. The sync-delivered `replace` is recorded as sent in the same transaction when the handle is known.

Channel fallback: `fallback_after` means "row not in status `sent` 5 minutes after `next_attempt_at`" (above the early backoff rungs) or `channelFatal`; then the dispatcher enqueues the next channel's dispatch with a new `send_key`. User non-response is handled only by the repeat cadence on the primary channel. The first user action wins and other channels' messages get a `finalize` op ("Recorded via Telegram at 09:03"). Dead channels are announced on a healthy one.

Rate limiting lives in the dispatcher: per-vendor and per-chat token buckets that count vendor events, not rows (a reaction-tier reminder is 1 + reactions events): Discord 50 req/s global and 5 msg/5 s per channel; Telegram ~30 msg/s and ~1 msg/s per chat (configuration, not code); Zulip 200 req/min; Matrix `retry_after_ms` plus a configured 0.2 events/s burst 10 per account, with the documented per-user `rc_message` override for the bot account. Priority `ack > interaction_reply > reminder > info > bulk`.

### 7.7 Habits and generic reminders

`reminders` and habit nudges reuse the same loop through a `reminder_occurrences` view with `kind`; the outbox and dispatcher are shared, so delivery guarantees are identical.

## 8. Data model (PostgreSQL 18, all instants `timestamptz`, ids `uuid`)

```sql
CREATE TYPE occ_status AS ENUM ('pending','due','snoozed','taken','skipped','missed','unknown','cancelled');
CREATE TYPE dose_action AS ENUM ('reminder_sent','reminder_deferred','taken','skipped','snoozed','auto_marked_missed',
  'marked_unknown','manually_corrected','undone','note_added','cancelled','catch_up_collapsed','chain_child_created');

CREATE TABLE users (id uuid PRIMARY KEY, handle citext UNIQUE, display_name text, timezone text NOT NULL DEFAULT 'UTC',
  locale text, status text NOT NULL CHECK (status IN ('active','pending_credentials','disabled')),
  quiet_hours jsonb, discreet_default boolean NOT NULL DEFAULT false, delivery_policy text NOT NULL DEFAULT 'primary_then_fallback',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE platform_identities (id uuid PRIMARY KEY, user_id uuid REFERENCES users, vendor text NOT NULL, vendor_user_id text NOT NULL,
  vendor_username text, dm_channel_id text, linked_at timestamptz, last_seen_at timestamptz, unlinked_at timestamptz);
CREATE UNIQUE INDEX uq_platform_identity ON platform_identities (vendor, vendor_user_id) WHERE unlinked_at IS NULL;
CREATE UNIQUE INDEX uq_user_vendor ON platform_identities (user_id, vendor) WHERE unlinked_at IS NULL;

CREATE TABLE medications (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users, name text NOT NULL,
  name_norm text GENERATED ALWAYS AS (lower(btrim(name))) STORED, display_alias text, dose_amount numeric, dose_unit text,
  instructions text, discreet boolean NOT NULL DEFAULT false, status text NOT NULL CHECK (status IN ('active','paused','archived')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX uq_medication_name ON medications (user_id, name_norm) WHERE status <> 'archived';

CREATE TABLE medication_schedules (id uuid PRIMARY KEY, medication_id uuid NOT NULL REFERENCES medications, user_id uuid NOT NULL,
  kind text NOT NULL, status text NOT NULL CHECK (status IN ('active','paused','archived')), current_revision int NOT NULL DEFAULT 1,
  tz text NOT NULL, tz_follows_user boolean NOT NULL DEFAULT true, start_date date NOT NULL, end_date date,
  materialized_through timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ix_schedules_materialize ON medication_schedules (status, materialized_through);

CREATE TABLE schedule_revisions (id uuid PRIMARY KEY, schedule_id uuid NOT NULL REFERENCES medication_schedules, revision int NOT NULL,
  effective_from timestamptz NOT NULL, tz text NOT NULL, rule jsonb NOT NULL, reminder_policy jsonb NOT NULL, dose_snapshot jsonb NOT NULL,
  created_by text NOT NULL, reason text, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (schedule_id, revision));

CREATE TABLE dose_occurrences (id uuid PRIMARY KEY, account_id uuid NOT NULL, medication_id uuid NOT NULL, schedule_id uuid, revision int,
  origin text NOT NULL CHECK (origin IN ('scheduled','chain','manual')), local_date date NOT NULL, local_time time, slot_key text NOT NULL,
  tz text NOT NULL, dst_kind text NOT NULL DEFAULT 'none' CHECK (dst_kind IN ('none','gap','fold')),
  scheduled_for timestamptz NOT NULL, due_window_start timestamptz NOT NULL, due_window_end timestamptz NOT NULL, miss_deadline timestamptz NOT NULL,
  status occ_status NOT NULL, cancel_reason text CHECK (cancel_reason IS NULL OR cancel_reason IN ('superseded','paused','archived','anchor_undone')),
  unknown_reason text, missed_reason text,
  epoch int NOT NULL DEFAULT 0, reminder_seq int NOT NULL DEFAULT 0, snooze_count int NOT NULL DEFAULT 0, snoozed_until timestamptz,
  last_reminded_at timestamptz, taken_at timestamptz, effective_at timestamptz, skipped_at timestamptz, missed_at timestamptz, next_action_at timestamptz,
  error_count int NOT NULL DEFAULT 0, dose_snapshot jsonb NOT NULL, anchor_occurrence_id uuid REFERENCES dose_occurrences, version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (schedule_id, revision, local_date, slot_key),
  CHECK ((status IN ('pending','due','snoozed')) = (next_action_at IS NOT NULL)),
  CHECK ((status = 'taken') = (taken_at IS NOT NULL)), CHECK ((status = 'skipped') = (skipped_at IS NOT NULL)),
  CHECK (status <> 'snoozed' OR snoozed_until IS NOT NULL), CHECK (status <> 'unknown' OR unknown_reason IS NOT NULL),
  CHECK (due_window_start <= miss_deadline));
CREATE UNIQUE INDEX uq_occ_live_slot ON dose_occurrences (schedule_id, local_date, slot_key) WHERE status <> 'cancelled';   -- one live dose per slot across revisions
CREATE INDEX ix_occ_next_action ON dose_occurrences (next_action_at) WHERE status IN ('pending','due','snoozed');           -- the loop's only hot index
CREATE INDEX ix_occ_account_day ON dose_occurrences (account_id, local_date, status);
CREATE INDEX ix_occ_account_med ON dose_occurrences (account_id, medication_id, local_date);

CREATE TABLE dose_actions (id uuid PRIMARY KEY, occurrence_id uuid NOT NULL REFERENCES dose_occurrences, account_id uuid NOT NULL,
  seq int NOT NULL, action dose_action NOT NULL, actor_type text NOT NULL CHECK (actor_type IN ('user','system')),
  vendor text, platform_identity_id uuid, platform_message_id text, interaction_id text, idempotency_key text UNIQUE,
  occurred_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(), prior_status occ_status NOT NULL, new_status occ_status NOT NULL,
  effective_at timestamptz, reason_code text, note text, undoes_seq int, catch_up boolean NOT NULL DEFAULT false,
  correlation_id text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}', UNIQUE (occurrence_id, seq));
-- append-only: app role has INSERT+SELECT only; trigger rejects UPDATE/DELETE. Projection = fold(actions); a nightly job checks it.

CREATE TABLE outbox_messages (id uuid PRIMARY KEY, send_key text UNIQUE NOT NULL, op text NOT NULL CHECK (op IN ('send','edit','finalize','delete','react')),
  kind text NOT NULL, vendor text NOT NULL, account_id uuid, occurrence_id uuid, channel_id uuid, epoch int, items jsonb,
  target jsonb, importance text NOT NULL, payload jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('queued','sending','sent','failed_retry','failed_permanent','cancelled','dead')),
  attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL, lease_until timestamptz, attempted_at timestamptz, ops_done int NOT NULL DEFAULT 0,
  possible_duplicate boolean NOT NULL DEFAULT false, platform_message_id text, last_error text, traceparent text,
  created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz);
CREATE INDEX ix_outbox_due ON outbox_messages (vendor, next_attempt_at) WHERE status IN ('queued','failed_retry');
CREATE INDEX ix_outbox_occ ON outbox_messages (occurrence_id, status);

CREATE TABLE rendered_messages (id uuid PRIMARY KEY, account_id uuid, vendor text NOT NULL, chat_id text NOT NULL, message_id text NOT NULL,
  revision int NOT NULL DEFAULT 0, kind text NOT NULL, subject_type text, subject_id uuid, epoch int, choice_map jsonb,
  superseded_by uuid, sent_at timestamptz NOT NULL, last_edited_at timestamptz, controls_removed_at timestamptz,
  UNIQUE (vendor, chat_id, message_id));

CREATE TABLE inbound_events (vendor text NOT NULL, vendor_event_id text NOT NULL, event_id uuid NOT NULL, account_id uuid,
  received_at timestamptz NOT NULL, reply jsonb, processed_at timestamptz, PRIMARY KEY (vendor, vendor_event_id));
CREATE TABLE callback_slots (id uuid PRIMARY KEY, account_id uuid, chat jsonb NOT NULL, payload jsonb NOT NULL, session_id uuid, step_seq int, expires_at timestamptz);
CREATE TABLE conversation_sessions (id uuid PRIMARY KEY, principal_key text NOT NULL, vendor text NOT NULL, chat_id text NOT NULL,
  flow text NOT NULL, step text NOT NULL, step_seq int NOT NULL DEFAULT 0, data jsonb NOT NULL DEFAULT '{}', version int NOT NULL DEFAULT 1,
  last_prompt jsonb, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (principal_key, vendor, chat_id));
CREATE TABLE form_runs (session_id uuid PRIMARY KEY, form_id text NOT NULL, answers jsonb NOT NULL DEFAULT '{}', field_index int NOT NULL DEFAULT 0);
CREATE TABLE delivery_channels (id uuid PRIMARY KEY, account_id uuid NOT NULL, platform_identity_id uuid UNIQUE NOT NULL,
  role text NOT NULL, priority int NOT NULL, state text NOT NULL, last_ack_at timestamptz, last_error text, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE domain_events (seq bigserial PRIMARY KEY, id uuid NOT NULL, type text NOT NULL, source text NOT NULL, subject text,
  account_id uuid, correlation_id text, causation_id text, traceparent text, actor jsonb, occurred_at timestamptz NOT NULL, data jsonb NOT NULL);
CREATE TABLE worker_heartbeat (instance text PRIMARY KEY, role text NOT NULL, last_tick_at timestamptz NOT NULL);   -- one row per process instance
CREATE TABLE auth_challenges (id uuid PRIMARY KEY, kind text NOT NULL, user_id uuid, vendor text, vendor_user_id text, token_hash bytea NOT NULL UNIQUE,
  code_hash bytea, expires_at timestamptz NOT NULL, consumed_at timestamptz, created_ip inet);
-- plus user_credentials, recovery_codes, auth_audit_log (append-only), api_tokens, mood_checkins(+tags text[]), habits(unique lower(name)),
-- habit_checkins, reminders, adherence_daily (optional rollup, full recompute of one (account, medication, day) row per action).
-- Matrix: Trixnity repository tables live in schema `matrix` (Exposed) if the slice-7 spike verifies it; otherwise SQLite on a named volume.
```

`domain_events.data` keeps the CloudEvents envelope transcribed from `shared/contracts` (id, type `dosecord.<domain>.<verb>.v1`, source, subject, time, correlation_id, causation_id, traceparent, actor, data); in-process subscribers (stats invalidation, weekly summary) consume it after commit; an external relay can tail `seq` later.

## 9. Reliability summary and failure modes

| Failure | Mitigation |
|---|---|
| Worker crash mid-tick | one transaction per batch, per-row savepoints; re-claim next tick |
| Poison occurrence row | savepoint rollback, `next_action_at` bumped 5 min, `error_count`, alert at 3; the rest of the batch proceeds |
| Two workers / restart mid-loop | SKIP LOCKED; UNIQUE natural key; UNIQUE `send_key` with epoch; `cancelOlderQueued`; epoch fencing; per-instance heartbeat rows |
| Vendor accepted, commit lost | `attempted_at` before the call on every vendor; retry after 2 min flags `possible_duplicate`; bounded to one duplicate per crash; finalize op edits every recorded handle |
| Reaction-tier crash between primary send and reactions | handle + `choice_map` recorded before the first reaction; `ops_done` bitmap resumes; duplicate-reaction errors are success |
| Vendor down | backoff retries, 8 attempts then `dead` (metric + alert), channel fallback after 5 min unsent or `channelFatal`, digest on recovery |
| DM closed / blocked | `Unreachable(channelFatal)` → channel dead → notify on another channel; setup flow sends a test DM |
| Reminder never delivered (dispatcher stuck, dead row, channel dead) | miss-time check of delivery evidence → `unknown(undelivered)` instead of `missed`; `dosecord_unknown_total{reason}` |
| 6 h downtime | materialise first, collapse instead of replay, `unknown(outage)`, one digest per 15-min bucket with per-item epochs, rate-limited |
| DST gap / fold | `java.time` transition API, `dst_kind` recorded, golden rows generated from tzdb transitions for Europe/Kyiv (03:xx gap), Europe/London (01:xx), America/New_York (02:xx), Australia/Lord_Howe (02:00-02:29), Asia/Kolkata, Pacific/Apia, America/Sao_Paulo |
| tzdata rule change | 48 h horizon, nightly verify pass, JDK tzdb version metric |
| Same-day edit / timezone move | cross-revision partial unique index; edit locks open rows FOR UPDATE; `effective_from` defaults to next local midnight when today is touched; eastward-move rows kept and asked about; property test: each slot exactly once in the 24 h after any change |
| Pause / resume | both are revisions; cancelled rows are outside the live-slot index so resume re-materialises immediately |
| Quiet hours | defer row with `reminder_deferred`; silent row sends with the flag; never `unknown` |
| Chain anchor missed / undone | children created on any terminal transition; `chain_not_started` rows at cutoff; undo cascades cancel |
| Schedule edit while due | old due rows keep living; tokens resolve by occurrence id |
| Stale message tapped days later | direct tokens or `choice_map` by target; late-log window → correction flow; current state replied |
| Duplicate button press | `inbound_events` PK + semantic no-op |
| Forged callback | HMAC, then ownership check on the account |
| Slow vendor call | blocks one virtual thread; Ox `timeout` per call; supervision restarts a failed fork |
| Materialiser bug | loop safety net; `min(materialized_through) < now` alert |
| Audit tampering | INSERT-only role, trigger, nightly fold-vs-projection check |
| Matrix crypto store vs Postgres restore skew | keys and sync token in one Trixnity repository; same backup job; startup divergence check |

## 10. Observability

scribe JSON with `correlation_id`, `vendor_event_id`, `account_id`, `vendor`, `handler`, `occurrence_id`, `send_key` bound per fork via Ox `ForkLocal`; message bodies and medication names never logged above DEBUG (a masking processor). OpenTelemetry Java agent for JDBC/HikariCP/OkHttp/Netty/Ktor plus manual spans for `mediator.handle`, `reminder.tick`, `outbox.dispatch`, `adapter.send`; `traceparent` stored on outbox rows so a reminder's trace spans materialise → tick → send. Prometheus on `/metrics`: `dosecord_reminders_due_total`, `_sent_total`, `_missed_total{reason}`, `_unknown_total{reason}`, `dosecord_outbox_depth{status,vendor}`, `dosecord_outbox_dead_total`, `dosecord_scheduler_tick_age_seconds{instance}`, `dosecord_materialized_lag_seconds`, `dosecord_occurrence_quarantined_total`, `dosecord_interaction_ack_latency_seconds{vendor}` (from creation time), `dosecord_form_degraded_total{reason}`, `dosecord_adapter_connected{vendor}`, `dosecord_adapter_errors_total{vendor,class}`, `dosecord_possible_duplicates_total{vendor}`, `dosecord_tzdb_info{version}`. Alerts: tick age > 60 s, outbox dead > 0, adapter disconnected > 5 min, materialised lag > 0, quarantined > 0, unknown-rate above an SLO. `/healthz` liveness; `/readyz` = DB ping + Flyway at head + tick age < 30 s + at least one enabled adapter connected. Ops alerts are also DMed to the owner through the bot itself.

## 11. Testing

1. Domain (pure, ~80% of tests, munit + munit-scalacheck): the FSM `match` is compiler-exhaustive and additionally table-tested over every (status, event, now) cell including undo, late-log window (`(missed, taken, now = +72 h)`), quiet-hours defer/silent, snooze bounds, outage-vs-undelivered-vs-no-response; materialiser golden tables per rule kind in seven zones generated from `ZoneRules` transitions; properties: determinism, composability `occ(a,c) == occ(a,b) ++ occ(b,c)`, unique `(local_date, slot_key)`, wall-time round trip outside transition days, per-day interval generation, idempotent re-materialisation, one live slot per (schedule, local_date, slot_key) across any sequence of edits/pauses/timezone moves, the restated loop-equivalence property, projection = fold(actions), snooze options never pass the next occurrence, adherence invariants; copy catalogue forbidden-phrase test.
2. Chat core: `FakeAdapter(profile)` for five profiles; renderer golden files per profile for every message the core can produce; token round-trip, ≤ 64 bytes and `dc:` shape, tamper rejection; numbered reply "2" and reaction 2 resolve to the same token; reaction on a 3-day-old reminder resolves by target; stale step rejection; mediator idempotency; ack-before-work ordering with a fake clock started at `createdAt`; modal for `opensForm` on a `modal = true` profile; visibility mismatch → follow-up; per-chat ordering under interleaved concurrency (Ox forks).
3. Infra (testcontainers Postgres 18): Flyway migrate + `validate`; constraint rejection including the live-slot index and reverse CHECKs; two concurrent loops produce exactly one outbox row per `send_key`; poison row leaves the batch committed and the row quarantined; lease expiry, `possible_duplicate` flagging and dead-lettering; crash injection between send and record yields at most one extra message and no extra state change; reaction-tier crash after primary send leaves resolvable controls; revision reconciliation cancels exactly the expected rows; pause → advance 1 h → resume fires the next slot; 6 h clock jump yields `unknown` rows, one digest, no stale reminders; crash with a queued s1 row + 30 min jump yields exactly one sent message with the new epoch.
4. Adapter conformance suite, one JSON scenario set every adapter must pass against a fake vendor transport: event mapping with stable `vendor_event_id` and monotonic cursor; duplicate update → same id; deadline auto-ack with a fake clock and declared visibility; toast after auto-answer on Telegram; capability honesty; 429 → `RateLimited`; "not modified" / duplicate-reaction → success; delete-window and edit-not-found → non-fatal `Permanent`; markup golden files; text splitting; resume without replay or gaps; log hygiene; raw modal-submit payload. Live smoke tests per vendor run before release; the Matrix E2EE smoke test runs in CI against a Synapse testcontainer from slice 7 on.
5. Scenario simulation: 30 virtual days for eight personas (daily 09:00, twice daily, Mon/Wed/Fri, every 8 h fixed, chain after first dose, 21/7 cycle, taper, as-needed) with scripted behaviour (late answers, two snoozes, ignore, undo, zone travel east and west, pause/resume, same-day edit, 6 h outage on day 12); the dispatch transcript and final stats are golden files and the review artefact for any loop change.

CI (GitHub Actions): scalafmt check, scalafix, `mill __.compile` with `-Werror`, tests with a Postgres 18 service, Flyway validate, vendor-symbol grep on core/contracts, licence gate on the dependency tree, docker build; coverage gate 85% on core/domain and core/chat, none on adapters.

## 12. Deployment and operations

One multi-stage image (Mill `assembly` on a JDK 25 builder, `eclipse-temurin:25-jre-alpine` runtime, non-root, `-XX:+UseSerialGC -Xmx256m -XX:+AutoCreateSharedArchive -javaagent:otel.jar`), compose with `postgres:18-alpine` (healthcheck, `depends_on: condition: service_healthy`, named volume), `dosecord` (`dosecord run`), optional `dosecord-worker` (`--role worker`), a `prodrigestivill/postgres-backup-local` sidecar doing nightly `pg_dump -Fc` with 30-day rotation (plus the Matrix volume in the same job when SQLite is used) and optional restic off-site, and a `grafana/otel-lgtm` profile for local dashboards. Configuration through environment variables and Docker secrets parsed into a `Settings` case class (`DATABASE_URL` = `jdbc:postgresql://…`, `DISCORD_TOKEN`, `TELEGRAM_TOKEN`, `ZULIP_SITE/EMAIL/API_KEY`, `MATRIX_HOMESERVER/USER/TOKEN`, `PUBLIC_BASE_URL`, `CALLBACK_KEYS`, `ENABLED_ADAPTERS`, `ROLE`, `INSTANCE_ID`, `LOG_FORMAT`, `OTEL_*`); `.env.example` is generated from the case class so it cannot drift. Startup: Flyway migrations under `pg_advisory_lock`, per-vendor ownership locks, materialiser catch-up, reminder loop, dispatchers, then adapters connect (outbox rows wait rather than fail). SIGTERM: stop accepting inbound, stop claiming, finish in-flight sends within 20 s, close gateways and the pool (Ox scope cancellation does the ordering). Upgrades: pull, `dosecord migrate`, restart; migrations are expand/contract. `dosecord admin` CLI: `outbox ls|retry|dead`, `occurrence show|fold-check|unquarantine`, `user export` (doctor-ready CSV/JSON), `user delete`, `tokens rotate`. The public HTTP surface is only the auth-link page behind a reverse proxy (Caddy); vendor traffic is gateway/long-poll, so no inbound webhook is required. A native-image build via Mill `NativeImageModule` (which the owner already uses) is a slice-8 experiment, not a launch dependency.

## 13. Migration from the current code

Honest accounting: the current repository is 2,307 lines of Python in 3 commits; `shared/contracts` is 422 lines and `models.py` 201. Nothing is reused as executable code. What is salvaged, and is language-neutral: the CloudEvents envelope and actor shape (transcribed to `contracts/Envelope.scala`), the payload types with their defaults (folded into the sealed command/event hierarchies), the 13-table schema as Flyway `V1` after correcting naive timestamps, adding enums/CHECKs, `unique(user_id, lower(name))`, `step_seq` on sessions and the tables in section 8, the CommandProcessor's dedupe → route → record → outbox transaction shape as the mediator's per-event transaction, the OutboxPublisher's SKIP LOCKED shape as the dispatcher, `_parse_mood`, `_is_hhmm`, keyword maps and all user-facing/safety copy (into `core/domain/copy`), the menu trees and callback payload shape, and the UX document as the spec extended with the resolved questions.

Deleted: the former prefix bot, the legacy producer/consumer/outbox publisher, the old compose services, the three scripting projects and import-path hacks. There is no production data; the dev volume is dropped. Git history is kept by rebuilding inside the same repository.

Cut-over in thin, individually shippable slices (each a PR with CI green):
0. Mill build with module graph, scalafmt/scalafix/`-Werror`, CI, Flyway V1 reproducing today's schema with the corrections, Tapir `/healthz`, Docker image.
1. Contracts, chat model, token codec, renderer with `FakeAdapter` golden files; console adapter; mediator with ack policy + Postgres session store; `/start`, account create, mood check-in end to end on the console.
2. Domain: rule enum, materialiser with DST golden/property tests, occurrence FSM (compiler-exhaustive `decide`), adherence maths.
3. Discord adapter (JDA: slash, components, modals, ack policy, `setNonce`), main menu, add-medication wizard, Today. The Python services, Kafka and compose services are deleted in this PR so there is never a half state; README/QUICKSTART/DEVELOPMENT rewritten.
4. Reminder loop with savepoints and heartbeats, vendor-owned dispatcher with ops, catch-up/digest, Taken/Snooze/Skip/Undo/notes/missed/late-log flows, action log, stats.
5. Tapir web: auth-link/restore pages, Argon2id, audit, `/metrics`, `/readyz`; scribe/OTel.
6. Telegram adapter (must touch zero core files; that is the acceptance test of the mediator claim).
7. Matrix spike (two days, time-boxed): Trixnity from the Kotlin shim under Ox, E2EE DM against a Synapse testcontainer, repository-on-Postgres check; go/no-go between in-process shim and out-of-process adapter. Then Zulip (reaction tier, hand-rolled client) and Matrix adapters via the conformance suite.
8. Habits/streaks, weekly summary, generic reminders, interval/chain/cycle/taper kinds, export/delete, REST API with bearer tokens via Tapir; native-image experiment.

## 14. Corrections applied to the source proposals and to revision 1

Language decision re-run with corrected facts (ADR-001); the former adapter's `send(nonce=)` already sets `enforce_nonce` and JDA's `setNonce` does too, so no raw route exists anywhere; matrix-nio's 21-month release gap and 7-week-old vodozemac binding are recorded as the reason it is not a top reason for any stack; TypeScript's Matrix option is matrix-js-sdk 42.3 with WASM crypto, not a pre-release fork, and its Zulip client (zulip-js 2.1.0, 2024-10) is stale; the fabricated brief quotation about the scripting runtime is removed; the Scala column is rebuilt with JDA 6.6 / TelegramBots 10.3 / Trixnity 5.8.1 (de.connect2x) / hand-rolled Zulip / JDBC+Flyway rather than Magnum; reuse is quantified (422 + 201 lines) and dropped as a ranked reason; PostgreSQL 18; Kyiv's spring gap is 03:00-03:59 and folds are recorded via `dst_kind`; `max_reminders` counts the initial reminder; `due_window_end` is derived; `late_log_window` has a consumer; quiet-hours `defer` has decide rows and never yields `unknown`; snooze from pending is anchored at `due_window_start`; undo is a defined transition with reverse CHECK constraints; chains create children on terminal transitions; pause/resume are revisions; the cross-revision live-slot index replaces reliance on the revisioned natural key; `send_key` carries the epoch; the heartbeat is per instance and outside the batch transaction; the tick uses savepoints and holds no network calls; the "user tap wins" sentence is replaced with "the tap waits and applies on the fresh row"; the Discord "duplicates impossible" claim is replaced by "bounded to one per crash on every vendor"; the outbox has `op`/`target`/`vendor`/`ops_done`; the modal/defer contradiction is resolved by the action registry's `opensForm`; slash visibility is declared on `CommandSpec`; Telegram's answer is single-shot in the profile; Telegram delete window is 48 h; `channelFatal` separates dead channels from non-fatal permanent errors; Matrix first-contact invites are accepted for direct rooms; Matrix keys and sync token live in one store; the reaction tier is budgeted at 4 events per message; ack latency is measured from creation time; `fallback_after` is defined; Zulip finalize removes own reactions; the loop-equivalence property is stated so that it is satisfiable.
