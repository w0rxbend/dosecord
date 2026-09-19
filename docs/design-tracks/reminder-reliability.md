Note: this is the language-agnostic input (Track B, scheduling, reminder delivery and intake recording) to docs/DESIGN.md; the Scala 3 realisation lives in DESIGN.md sections 7-9 and ADR-004, ADR-009, ADR-011 and ADR-012.
Note: reference material, not a normative spec on its own; where this file and DESIGN.md differ, DESIGN.md wins.

# Track A: Unified Chat API and Mediator

## 0. Scope and positioning

This document specifies the boundary between vendor adapters (Discord, Telegram, Zulip, Matrix) and the Dosecord bot core. It is language-agnostic: every type is given as a shape, every operation as a contract with pre/post-conditions. It assumes the core (domain: accounts, medications, occurrences, reminders, stats) already exists behind a small set of ports; it does not choose the core's storage or transport. Everything below can be implemented as in-process interfaces in a modular monolith (recommended first, per the gap map's criticism of Kafka for a single-user bot) or serialized onto a queue later without changing the contracts, because all types are plain data and every operation is idempotent by key.

Three layers, strict dependency direction (adapter -> mediator -> core; never reversed):

1. **Adapter** (one per vendor): speaks the vendor protocol, converts vendor updates into `InboundEvent`s, executes `OutboundOp`s. Stateless apart from a resume cursor and rate-limit buckets.
2. **Mediator** (the "chat gateway", one process-level component): owns identity resolution, callback tokens, presentation memory for degraded UIs, rendering/degradation, delivery routing, delivery log, message handles, per-chat serialization, and rate limiting. It is the only component that knows both the vendor capability profile and the core's abstract UI model.
3. **Core**: receives normalized events already stamped with a resolved principal, runs domain logic and the wizard state machine, and emits abstract UI (`OutboundMessage`) and delivery requests. It never sees a vendor name except as an opaque `ChatRef` it stores and hands back.

Constraint check against the brief: adding a vendor means writing one adapter class plus a capability profile and running the conformance suite; core code is untouched (constraint 1). Degradation is deterministic and spec'd (constraint 2). Delivery is at-least-once with idempotent keys and handles for later edits (constraint 3). Every inbound is recorded with vendor event id (constraint 4). Rich-where-possible rendering is the renderer's job (constraint 5).

## 1. Inbound event model

Every adapter produces one `InboundEvent` per vendor update it decides is relevant. The envelope is uniform; the `kind` payload varies.

**Envelope fields**: `event_id` (mediator-assigned UUID), `vendor` (string, not a closed enum), `vendor_event_id` (the vendor's own id: Discord interaction id or message id, Telegram `update_id`, Zulip event `id` within queue, Matrix `event_id`), `received_at`, `actor: PlatformIdentity{vendor, vendor_user_id, display_name?}`, `chat: ChatRef{vendor, chat_id, thread_id?}`, `cursor` (opaque resume token; see 7), `kind`.

`vendor_event_id` is the idempotency basis for the whole pipeline: `inbound_events` table has unique `(vendor, vendor_event_id)`; a duplicate is acknowledged to the vendor and dropped before reaching core. This closes the "every command runs twice" gap and Telegram's retried callback queries.

**Kinds** (closed set, versioned):

- `MessageReceived{text, entities?: [mention|link], reply_to?: MessageHandle}` – plain text a human typed. Attachments are ignored in v1 (recorded as `has_attachments`).
- `CommandInvoked{name, args: map, raw}` – a vendor-native command (Discord slash, Telegram `/cmd`, Zulip/Matrix `/cmd` text parsed by the mediator, not the adapter). Adapters only emit this for vendor-native command surfaces; for text-only vendors the mediator's `CommandParser` promotes `MessageReceived` beginning with `/` into `CommandInvoked`, so the core sees one shape.
- `InteractionSubmitted{callback: CallbackRef, values?: [string], source: MessageHandle?, ack: InteractionAck?}` – a button/select press. `callback` is already decoded and verified by the mediator (see 3). `ack` is an opaque handle the mediator uses to satisfy vendor acknowledgement deadlines; core never touches it.
- `FormSubmitted{form_id, callback: CallbackRef, fields: map}` – a modal submit, or the synthesized result of the mediator's sequential-question fallback.
- `ReactionChanged{emoji, target: MessageHandle, added: bool}` – raw reaction. The mediator consumes reactions that map to a presented choice set (turning them into `InteractionSubmitted`) and forwards the rest to core only if core subscribed (used for "👍 = taken" quick actions on Zulip/Matrix).
- `ConversationStarted{}` – Telegram `/start`, Discord first DM or app install, Zulip first PM to bot, Matrix DM room joined. Core answers with the welcome/identity menu.
- `AccountLinkCompleted{link_id, account_id}` – synthetic, injected by the identity module after a web-side link succeeds; delivered through the same sink so the core has one entry path and the mediator can refresh its identity cache.
- `AdapterLifecycle{state: connected|disconnected|resumed, from_cursor?}` – lets core trigger catch-up delivery.

**Principal stamping.** Before dispatch, the mediator calls `IdentityResolver.resolve(actor)` and attaches `principal: Principal{account_id?, identity_id, linked: bool}`. Core rejects any event without a mediator-stamped principal; adapters have no field to supply an account id (fixes R8: account id never trusted from the wire).

**Normalization rules**: text is NFC-normalized, trimmed, max 4 000 chars (longer is truncated with `truncated=true`). Vendor markup in inbound text is stripped to plain text; only the original is stored in the audit row.

## 2. Outbound message model

Core produces `OutboundMessage`, an abstract description; the mediator renders it against a `CapabilityProfile`.

**Content**: `body: RichText` is an AST, not a string, so the renderer can target Discord markdown, Telegram MarkdownV2/HTML, Zulip markdown and Matrix HTML without escaping bugs. Portable subset (anything else is a design error caught at type level): `Paragraph`, `Text`, `Bold`, `Italic`, `Code` (inline), `CodeBlock`, `Link{url,label}`, `BulletList`, `LineBreak`, `Emoji{shortcode}`, `Time{instant, tz, style: time|datetime|relative}` (renders as `09:03`, or Discord's `<t:…>`). No headings, tables, images, or colors.

**Blocks** (0..n per message, ordered):

- `ChoiceSet{id, prompt?, choices: [Choice{label, callback: CallbackToken, style: primary|secondary|danger|success, emoji?, disabled?}], layout: buttons|select, min_select=1, max_select=1}`. Max 25 choices (Discord select limit; larger sets must be paged by core).
- `Form{id, title, fields: [Field{key, label, type: text|number|time|date|choice, required, placeholder?, options?}], submit: CallbackToken}`. Max 5 fields (Discord modal limit).
- `Notice{level: info|success|warning}` – a short banner rendered as an italic line or emoji prefix.

**Message-level attributes**: `visibility: persistent|ephemeral`, `importance: interaction_reply|reminder|info|bulk` (drives rate-limit priority and discreet-mode rendering), `replaces?: MessageHandle` (edit-in-place intent), `delete_after?: duration`, `discreet: bool` (medication names replaced by "your 09:00 dose" in the body already by core; the flag tells the renderer to suppress previews/emoji), `dedupe_key`, `correlation_id`.

**Operations the core may request** (`ChatPort`, section 6 for reminders):

- `send(target, message) -> SendResult{handle: MessageHandle, rendered_as: RenderReport}`
- `edit(handle, message)` – full replacement of body and blocks.
- `finalize(handle, summary: RichText)` – "disable controls and show outcome" (buttons removed, body replaced by e.g. "Recorded at 09:03 · Undo · Add note"). This is the common post-action op, given its own name so degraded vendors can implement it as "edit text, remove reaction shortcuts".
- `delete(handle)`
- `ack(interaction, toast?: string)` – transient feedback; mediator picks Discord deferred update / Telegram `answerCallbackQuery` / no-op.
- `react(handle, emoji)` / `unreact`.
- `typing(chat)` (best effort).

All operations are idempotent by `(dedupe_key)` for send and `(handle, revision)` for edit/finalize; the mediator's `delivery_log` records outcome per key.

### 2.1 Degradation ladders (deterministic)

The renderer is a pure function `render(message, profile) -> [VendorOp]` plus `RenderReport` (which ladder rung was used). Ladders are evaluated top-down; the first rung the profile supports wins. Rungs are fixed and part of the conformance golden files.

**ChoiceSet, layout=buttons**
1. Native buttons (Discord components: up to 5 per row, rows packed in label order; Telegram inline keyboard: up to 4 per row).
2. Native single-select menu (Discord only, when >10 choices).
3. Reply keyboard (Telegram only, when inline is unavailable in context – practically never; kept for completeness).
4. Numbered list + reaction shortcuts: body gets "1) Taken 2) Snooze 10m 3) Skip"; the mediator posts the message and then adds reactions 1️⃣ 2️⃣ 3️⃣ (up to 9, then 🔟 is not used; >9 falls to rung 5) to its own message so the user taps an existing reaction (one tap on Zulip/Matrix). The mediator persists a `presentation` row mapping `(handle, index) -> CallbackToken`; a reply "2", "2)", the choice label (case-insensitive), or a reaction with that digit resolves to the token. The presentation expires with the message's `choice_ttl` (reminder messages: none; menus: 24 h).
5. Numbered list only (reaction posting failed or disallowed).

**ChoiceSet, layout=select**: native select -> native buttons paged 5 per row -> rung 4 -> rung 5.

**Form**: native modal (Discord) -> sequential questions run by the mediator's `FormRunner` (one `send` per field; free-text answer or numbered choice; `Cancel` always accepted; the runner stores partial answers in `mediator_form_runs` keyed by chat and emits one `FormSubmitted` when done). Core never learns which happened. Telegram Mini Apps are deliberately not used in v1.

**Ephemeral**: native ephemeral (Discord, only as an interaction response; the mediator downgrades automatically if the message is not a response to a live interaction) -> persistent with `delete_after = 60 s` where delete is supported (Telegram <48 h, Matrix redaction, Zulip if realm allows) -> persistent with a trailing Notice "This message will not be kept".

**Edit / finalize**: native edit -> if the vendor refuses (Zulip realm edit window elapsed, Matrix edit of a redacted event) -> send a new message quoting/replying to the old one and, if allowed, delete the old -> send new message only. The `MessageHandle` returned from a fallback becomes the new canonical handle and the mediator records `superseded_by` so the core's stored handle still resolves.

**Delete**: native delete -> edit to "(cleared)" with controls removed -> leave and mark handle `unreachable`.

**Text length**: bodies exceeding the profile's `max_text` are split at paragraph boundaries; blocks always attach to the last chunk.

**Commands**: native command registration (Discord slash, Telegram `setMyCommands`) -> text `/command` parsing in the mediator. Core declares its command list once (`CommandSpec[]`), the mediator registers it where possible.

Discreet mode never changes ladders; it only changes what core puts in the body.

## 3. Interaction identity: CallbackToken

Every tappable thing carries a `CallbackToken` that must (a) fit Telegram's 64-byte `callback_data` and Discord's 100-char `custom_id`; (b) be unforgeable; (c) keep working on a message that is days old or after a restart; (d) map back to server-side state.

**Binary layout (34 bytes raw, 46 chars base64url, no padding)**:

```
byte 0     : version(4 bits) | key_id(4 bits)
byte 1     : mode  0=direct 1=indirect
byte 2-3   : action (uint16, registry: 1=dose.taken 2=dose.snooze 3=dose.skip ... 200+=wizard.*)
byte 4-11  : subject (int64 public short id: occurrence id, session id, or slot id)
byte 12-25 : value (14 bytes, zero-padded: snooze minutes, choice index, step seq, enum)
byte 26-33 : MAC = HMAC-SHA256(server_key[key_id], bytes 0..25)[0:8]
```

*Direct mode* is stateless: the token itself names a durable domain entity (dose occurrence) and a value. A reminder from last week still works because occurrence ids never expire and the core's transition is idempotent (taken twice = one record + "already recorded" ack).

*Indirect mode* points at an `interaction_slots` row `{slot_id, account_id, chat, payload json, session_id?, step_seq?, expires_at}`. Used by wizards and menus, where the payload is bigger than 14 bytes or ties to a step. Slots for reminder-related follow-ups (Undo, Add note) have no expiry; menu/wizard slots expire with the session.

**Verification** in the mediator: check MAC and key_id (keys rotate; two active keys), decode, then attach `CallbackRef{action, subject, value, slot?}` to the event. A bad MAC produces an `ack(toast="This button is no longer valid")` and an audit entry; nothing reaches core. Because Telegram may deliver a callback whose original message is inaccessible (too old), nothing in the token depends on the message being readable.

**Staleness**: wizard tokens carry `step_seq`; core compares with the session's current `step_seq` and, on mismatch, re-sends the current prompt with `ack(toast="That step is done")`. Direct tokens are never stale; the entity state decides.

**Idempotency of the resulting command**: `command_key = hash(vendor, vendor_event_id)`; retried vendor deliveries collapse. The token is deliberately reusable (not single-use) so a duplicate press is a no-op rather than an error.

For vendors without callback data (Zulip, Matrix) tokens are never sent to the vendor; they live in the `presentation` rows and are resolved from `(handle, choice index)`.

## 4. Conversation and session state

**Ownership**: the core owns `ConversationSession`; the mediator owns only ephemeral presentation memory and form runs. Nothing conversational lives in adapter memory, so any process can handle any event.

`ConversationSession{id, principal_key (account_id or unlinked identity id), chat: ChatRef, flow, step, step_seq, data json, version, expires_at, last_prompt: MessageHandle?, created_at, updated_at}` with unique `(principal_key, chat)` for active sessions.

**Wizard definition** is declarative: `Flow{name, steps: map[step -> Step{prompt(data) -> OutboundMessage, accept(input) -> Validation, next(data, input) -> step | Done | Abort, back?: step}]}`. The engine is generic: `Engine.handle(session, input)` loads the session (`SELECT ... FOR UPDATE`), validates, mutates data, increments `step_seq`, persists (version check), then asks the mediator to `finalize(last_prompt)` (removing old buttons) and `send` the next prompt, then stores the new handle. Persist-then-send ordering means a crash after the DB commit but before the send is recovered by the sweeper: sessions whose `last_prompt` is null re-send their current prompt.

**Inputs a step accepts**: `InteractionSubmitted` (button), `MessageReceived` (typed text for name/dose/custom time), `FormSubmitted`. Each step declares which; anything else gets the current prompt again plus a Notice.

**Global interrupts**: direct-mode tokens (reminder Taken/Snooze/Skip) bypass the session entirely and are handled even mid-wizard. Commands `/cancel`, `/menu` abort the session. Starting a new flow while one is active asks "Cancel the current add-medication setup?" (ChoiceSet).

**Timeouts**: `expires_at = now + 30 min` refreshed on every input. A sweeper (core scheduler) runs every minute: sessions past expiry get one "Still there? [Continue] [Cancel]" prompt with a 30-minute grace; then abort, `finalize` the prompt with "Setup cancelled – nothing saved", and delete slots.

**Resume after restart**: because state is in the DB and every token resolves through the DB, resumption is automatic. The engine's only in-memory state is the compiled `Flow` table.

**Concurrency**: the mediator serializes all events for a `(vendor, chat_id)` key (section 7), so a session is never mutated by two workers at once; the version column is belt-and-braces for multi-process deployments.

## 5. Identity mapping and account linking

`PlatformIdentity(vendor, vendor_user_id)` -> `platform_identities` row -> `account_id`. Resolution is a core port (`IdentityResolver`) called by the mediator, with a short-TTL cache invalidated by `AccountLinkCompleted` and `IdentityUnlinked` events.

**Unlinked principals** exist (first contact). The mediator stamps `Principal{linked=false, identity_id}`; the core's router allows only the identity flows (create account, link, restore) and help. Sessions for unlinked users are keyed by `identity_id`; on link, the core rekeys the session to the account (this replaces the partition-key flip risk from the Kafka design: ordering is per chat, not per account).

**Where linking lives**: in the core's identity module, never in adapters or the mediator. Flows:

1. *Create account* from a chat: core creates account + links the current identity in one transaction.
2. *Link existing* (from a new vendor chat): core issues `link_challenge{code (8 chars), url (HTTPS, 10 min TTL), identity_id}`; the mediator sends the URL (Link block) and the code. The user either opens the URL while authenticated in the web session or types the code into an already-linked chat on another vendor. The web/API layer calls `Identity.completeLink(code)`, which inserts the `platform_identities` row, writes the audit row, and injects `AccountLinkCompleted` for both chats.
3. *Restore* uses recovery codes through the same challenge shape.

Passwords are never accepted in chat; the mediator's `secret_guard` refuses to store bodies for steps flagged `sensitive` (kept for the future MVP DM fallback R7, off by default).

**Per-identity delivery preferences** live in the identity module: `reminder_channel: bool`, `discreet: bool`, and an ordered `channel_priority` on the account.

## 6. Reminder delivery API

Core does not choose vendors. It calls:

`Delivery.deliver(DeliveryRequest{dedupe_key, account_id, message: OutboundMessage, policy: DeliveryPolicy{mode: primary|all|first_available, expires_at, requires_ack?: bool}, correlation_id}) -> DeliveryTicket{delivery_id}`

The mediator: (1) inserts a `deliveries` row `{delivery_id, dedupe_key unique, account_id, status=pending, request json}` – when called inside the core's transaction (same DB, modular monolith) this is the transactional outbox; (2) a `DeliverySender` worker claims pending rows (`FOR UPDATE SKIP LOCKED`, lease 60 s), resolves channels from account preferences (`primary` = the identity flagged `reminder_channel`, else most recently active), calls `send`, and records `delivery_attempts{delivery_id, channel, status, handle, error}`; (3) emits `DeliveryStateChanged{delivery_id, channel, status: sent|failed|suppressed|expired, handle}` to the core, which stores handles on the occurrence (`occurrence_messages` table) for later `finalize`/`edit`.

**At-least-once with bounded duplicates**: the send is executed with the row in `sending` state; success records `handle` and `sent_at`. If the process dies between vendor success and the DB write, the lease expires and the row is retried; the duplicate is possible but detectable afterwards (the second message replaces the first via `finalize` because both handles are recorded against the same `dedupe_key`), and the sender first asks the adapter `findRecentByMarker(chat, marker)` where supported (Discord/Zulip/Matrix can search the bot's last N messages for the hidden marker `delivery_id` embedded as a zero-width sequence or Matrix `content["io.dosecord.delivery_id"]`; Telegram cannot, so it accepts the rare duplicate).

**Expiry**: reminders carry `expires_at` (e.g. miss window). The sender never sends past expiry; it marks `expired` so catch-up after downtime does not flood the user with stale reminders; the core's catch-up logic then sends one "While I was away…" summary instead.

**Later actions on old messages**: `finalize(handle, ...)` and `edit(handle, ...)` accept any stored handle; the mediator handles superseded handles and vendor refusals via the edit ladder. Undo after Taken edits the same message back to its button state (or sends a fresh one on degraded vendors).

**Receipts**: `DeliveryStateChanged` is the only feedback path; `deliver` itself never blocks on the vendor.

## 7. Adapter contract

An adapter is a small, dumb, replaceable component. It **must**:

- Implement `ChatAdapter` (key_types): `capabilities()` returning a static `CapabilityProfile`; `start(sink, cursor?)`, `stop()`; `execute(op: VendorOp) -> VendorResult` for send/edit/delete/react/ack/typing/registerCommands/openChat; `resolveChat(identity) -> ChatRef` (open a DM: Discord `create DM`, Telegram chat id = user id, Zulip = PM to email/id, Matrix = find or create DM room and invite).
- Convert every relevant vendor update into exactly one `InboundEvent` with a stable `vendor_event_id` and a `cursor` (Telegram `update_id`+1; Zulip `queue_id:last_event_id`; Matrix `next_batch`; Discord none – gateway is push and the adapter reports `resumed` on reconnect so the core can run catch-up).
- Acknowledge vendor deadlines itself when the mediator has not done so in time (Discord: deferred update at 2.5 s; Telegram: `answerCallbackQuery` at 5 s) – via the `InteractionAck` handle, so the mediator can still send the real response later.
- Translate `RichText` using the profile's escaping rules; split at `max_text`.
- Map vendor errors to the closed `VendorError` set: `rate_limited{retry_after}`, `not_found`, `forbidden`, `too_old`, `unsupported`, `transient`, `permanent`.
- Redact message bodies from logs; emit metrics `adapter_events_in`, `adapter_ops_out`, `adapter_errors`, `adapter_rate_limit_waits` with `vendor` label.

It **must not**: parse user text into intents; hold conversation or user state; decide identity or account; retry beyond a single `Retry-After`-guided retry (the mediator owns retry policy); invent fallbacks (the mediator renders; an adapter receiving an op it cannot perform returns `unsupported`, which is a conformance failure if the profile claimed support); reorder events for a chat; call core.

**Threading and ordering**: adapters may be fully async; they deliver events to `InboundSink.push(event)` which is non-blocking with a bounded per-adapter queue (default 1 000). The mediator dispatches events through a keyed executor: key = `(vendor, chat_id)`, so per-chat order is preserved while chats run in parallel (worker pool sized to CPU; handlers are I/O bound). Discord's 3-second rule is met by the sink immediately scheduling `ack(defer)` when the event is an interaction and the queue depth for that key exceeds 0.

**Backpressure**: pull-based adapters (Telegram long-poll, Zulip event queue, Matrix `/sync`) simply stop polling when `push` returns `full`. Push-based (Discord gateway) cannot; the adapter drops non-interaction events beyond the queue, records a `dropped_events` metric, and answers interactions with `ack(toast="I'm busy, try again in a moment")`. The core's catch-up (on `AdapterLifecycle{resumed}`) re-derives state from the DB, so dropped events are recoverable by the user re-tapping.

**Rate limits** are enforced in the mediator's `OutboundScheduler`, not adapters: a global token bucket per vendor (Discord 50 req/s global; Telegram ~30 msg/s), a per-chat bucket (Telegram ~1 msg/s; Discord 5 msg/5 s per channel; Zulip 200 req/min per bot), and a priority queue `ack > interaction_reply > reminder > info > bulk`. On `rate_limited{retry_after}` the scheduler pauses that bucket for `retry_after` and re-enqueues; on repeated 429 it halves the bucket rate for 10 minutes. Limits are configuration, not code, since Telegram publishes no official numbers.

## 8. Conformance suite and fake vendor

Two harness levels, both language-neutral in intent (a fixture format of JSON scenario files so every implementation runs the same cases):

**A. Adapter conformance** – runs any adapter against a `FakeVendorServer` implementing the vendor's *wire* (an in-memory HTTP/WS stub per vendor, or the vendor SDK's test transport) and a scripted `Scenario{given: [vendor updates], when: [ops], expect: [vendor calls, events]}`. Mandatory scenarios:
1. Event mapping: each vendor update type -> the expected `InboundEvent` kind, `vendor_event_id`, cursor monotonicity.
2. Duplicate delivery of the same vendor update produces the same `vendor_event_id`.
3. Deadline ack: an interaction unanswered by the mediator is deferred before the vendor deadline (fake clock).
4. Capability honesty: for every capability the profile claims, an op succeeds against the fake; for every one it denies, the adapter returns `unsupported` without calling the vendor.
5. Rate limit: fake returns 429 with `Retry-After`; adapter returns `rate_limited` and performs no further calls.
6. Long text split, markup escaping golden files (`RichText` fixtures -> expected vendor payload).
7. Resume: stop, restart with the last cursor, no replays or gaps.
8. Log hygiene: no message body appears in captured logs.

**B. Mediator conformance** – runs the mediator with a `FakeAdapter` parameterized by a `CapabilityProfile` (all four real profiles plus a minimal "text-only" profile) and an in-memory core stub. Scenarios: every degradation ladder rung produces the golden `RenderReport`; numbered reply "2" and reaction 2️⃣ both resolve to the second token; token round-trip (encode -> decode) and tamper rejection; stale step rejection; delivery outbox retry with a crash injected after vendor success; edit ladder when the fake refuses edits; ephemeral downgrade when no live interaction; per-chat ordering under concurrency (interleaved chats keep order); priority scheduling under a saturated bucket.

Any new adapter ships with suite A green and its profile included in suite B's parameter list. The suite is the definition of "supports vendor X".

## 9. Storage owned by the mediator

`inbound_events` (audit and dedupe), `interaction_slots`, `presentations`, `form_runs`, `deliveries`, `delivery_attempts`, `message_handles` (with `superseded_by`), `identity_cache` (optional). All keyed to survive restarts; nothing else is in memory except rate buckets, which are safe to lose.

## 10. Migration notes for the existing repo

Keep: envelope/actor shape (as the serialized form of `InboundEvent` if a queue is introduced), UX copy and menu trees, callback shape (`action, conversation_id, value`) which maps directly onto the token's `(action, subject, value)`. Replace: the text-only chat contract with `OutboundMessage`; the closed `Platform` enum with a vendor string plus a profile registry; per-vendor idempotency keys with `(vendor, vendor_event_id)`.

## Capability matrix
| Capability | Discord | Telegram | Zulip | Matrix | Mediator fallback (deterministic ladder) |
|---|---|---|---|---|---|
| Native commands | Slash commands, registered; work in bot DM incl. user-installed apps | `/cmd` + `setMyCommands` menu | None (text `/cmd` parsed by mediator) | None (text `/cmd` parsed by mediator) | Text `/command` parsing in mediator; core declares `CommandSpec[]` once |
| Buttons | Yes (components; 5/row, 40 components/message with V2) | Yes (inline keyboard, `callback_data` 1-64 bytes) | No | No | Numbered list + bot-added digit reactions (1️⃣-9️⃣) with `presentation` map -> numbered list only |
| Select / dropdown | Yes (string select, max 25 options) | No | No | No | Buttons paged 5/row -> numbered list + reactions -> numbered list |
| Forms / modals | Yes (modal, 1-5 components, selects/checkbox allowed) | No (Mini Apps out of scope) | No | No | Mediator `FormRunner` sequential questions, emits one `FormSubmitted` |
| Ephemeral messages | Yes, only as interaction responses (flag 64) | No (transient toast via `answerCallbackQuery` only) | No | No | Persistent + `delete_after` -> persistent + Notice "not kept" |
| Edit own message | Yes, indefinitely | Yes for bot's own messages (delete limited to 48 h; edit limit not documented, treat as unlimited but verify) | Yes if realm edit-window allows (author-only; realm setting) | Yes (`m.replace`) | Send replacement (reply to old) + delete old if allowed -> send replacement only; handle `superseded_by` |
| Delete own message | Yes | Yes within 48 h | Realm-permission dependent | Yes (redaction of own event) | Edit to "(cleared)" with controls removed -> leave, mark `unreachable` |
| Finalize (remove controls, show outcome) | Edit components | `editMessageReplyMarkup`/text | Edit text, remove bot reactions | Edit text, redact bot reactions | Falls to edit ladder |
| Bot adds reactions | Yes | Yes (`setMessageReaction`, limited emoji set) | Yes (`add-reaction`) | Yes (`m.reaction`) | Skip reaction shortcuts (rung 5) |
| Bot receives reactions | Yes (gateway event, DMs included) | Yes (`message_reaction` update, must be in `allowed_updates`) | Yes (event queue `reaction` with `user_id`) | Yes (`m.reaction` events) | Not needed if buttons exist; without both, numbered text replies only |
| Transient ack / toast | Deferred update, ephemeral follow-up | `answerCallbackQuery` (text / alert) | None | None | No-op; outcome shown via finalize |
| Portable markup | Markdown subset | MarkdownV2 or HTML | Zulip markdown | `org.matrix.custom.html` | `RichText` AST rendered per vendor; unsupported nodes rendered as plain text |
| Text limit | 2 000 chars content (V2 text display larger; treat 2 000) | 4 096 chars | ~10 000 chars (realm setting) | 65 KB event size | Split at paragraph boundaries; blocks attach to last chunk |
| Callback payload budget | `custom_id` 1-100 chars | 64 bytes | None (server-side presentation map) | None (server-side presentation map) | 46-char signed token fits all; text vendors use presentation map |
| Ack deadline | Initial response within 3 s; token valid 15 min | Answer callback promptly (spinner) | None | None | Adapter auto-defers at 2.5 s (Discord) / 5 s (Telegram) |
| Proactive DM (reminder push) | Needs DM channel (shared guild or user-install context) | Only after user sent `/start` | Yes, any realm user | Create/reuse DM room; user must have joined | `resolveChat` at link time; store `ChatRef`; on failure mark channel unavailable and try next channel |
| Threads | Yes (not used) | Topics in forums (not used) | Topics (not used for DMs) | `m.thread` (not used) | `thread_id` carried opaquely, never required |
| Polls | Yes (native) | Yes (native, no callback) | `/poll` widget | MSC3381 unstable (`org.matrix.msc3381.*`), Element only | Not part of the unified model in v1 |
| Rate limits | 50 req/s global, ~5 msg/5 s per channel, 429 with `Retry-After` | ~30 msg/s global, ~1 msg/s per chat (unofficial), 429 `retry_after` | 200 req/min per user default | Homeserver-configured, `M_LIMIT_EXCEEDED` + `Retry-After` | Mediator `OutboundScheduler`: per-vendor + per-chat token buckets, priority queue, single `Retry-After` retry in adapter, backoff in mediator |
| Resume cursor | None (gateway push; report `resumed`) | `update_id` offset | `queue_id` + `last_event_id` (re-register on expiry) | `/sync` `next_batch` | Core catch-up on `AdapterLifecycle{resumed}` from DB state |
| E2E encryption | No | No (secret chats unavailable to bots) | No | Yes in DMs by default in many clients | Adapter must use a crypto-capable SDK or create unencrypted DM; flagged as risk |

## Key types
// ---------- Shared value types ----------
type Vendor = string                          // "discord" | "telegram" | "zulip" | "matrix" | future
type PlatformIdentity = { vendor: Vendor, vendor_user_id: string, display_name?: string }
type ChatRef        = { vendor: Vendor, chat_id: string, thread_id?: string }
type MessageHandle  = { vendor: Vendor, chat_id: string, message_id: string, revision: int }
type Principal      = { identity_id: UUID, account_id?: UUID, linked: bool }  // stamped by mediator only
type Cursor         = opaque string

// ---------- Inbound (adapter -> mediator -> core) ----------
type InboundEvent = {
  event_id: UUID, vendor: Vendor, vendor_event_id: string, received_at: Instant,
  actor: PlatformIdentity, chat: ChatRef, cursor?: Cursor,
  principal?: Principal,                 // absent from adapters, present when core sees it
  kind: InboundKind
}
type InboundKind =
  | MessageReceived   { text: string, reply_to?: MessageHandle, truncated: bool }
  | CommandInvoked    { name: string, args: map<string,string>, raw: string }
  | InteractionSubmitted { callback: CallbackRef, values: [string], source?: MessageHandle, ack?: InteractionAck }
  | FormSubmitted     { form_id: string, callback: CallbackRef, fields: map<string,string> }
  | ReactionChanged   { emoji: string, target: MessageHandle, added: bool }
  | ConversationStarted {}
  | AccountLinkCompleted { link_id: UUID, account_id: UUID }
  | AdapterLifecycle  { state: "connected"|"disconnected"|"resumed", from_cursor?: Cursor }

type InteractionAck = opaque                    // vendor handle for deferring/answering; core never inspects
type CallbackRef = { action: uint16, subject: int64, value: bytes14, slot?: InteractionSlot }
type InteractionSlot = { slot_id: int64, account_id?: UUID, payload: json, session_id?: UUID, step_seq?: int, expires_at?: Instant }

// ---------- Outbound (core -> mediator -> adapter) ----------
type RichText = [Node]
type Node = Paragraph([Inline]) | BulletList([[Inline]]) | CodeBlock(string)
type Inline = Text(s) | Bold([Inline]) | Italic([Inline]) | Code(s) | Link(url,label) | LineBreak
            | Emoji(shortcode) | Time(instant, tz, style: "time"|"datetime"|"relative")

type Choice    = { label: string(<=80), callback: CallbackToken, style: "primary"|"secondary"|"danger"|"success", emoji?: string, disabled: bool }
type ChoiceSet = { id: string, prompt?: RichText, choices: [Choice](<=25), layout: "buttons"|"select", min_select: int, max_select: int, choice_ttl?: Duration }
type Field     = { key, label, type: "text"|"number"|"time"|"date"|"choice", required: bool, placeholder?: string, options?: [Choice] }
type Form      = { id: string, title: string(<=45), fields: [Field](<=5), submit: CallbackToken }
type Block     = ChoiceSet | Form | Notice{ level: "info"|"success"|"warning", text: RichText }

type OutboundMessage = {
  body: RichText, blocks: [Block],
  visibility: "persistent"|"ephemeral", importance: "interaction_reply"|"reminder"|"info"|"bulk",
  replaces?: MessageHandle, delete_after?: Duration, discreet: bool,
  dedupe_key: string, correlation_id: string
}
type Target = { account: UUID } | { chat: ChatRef } | { reply_to_interaction: InteractionAck }

type RenderReport = { rung_used: map<block_id, string>, split_into: int, warnings: [string] }
type SendResult   = { handle: MessageHandle, report: RenderReport }

// Port the CORE calls (implemented by the mediator)
interface ChatPort {
  send(target: Target, msg: OutboundMessage): SendResult            // idempotent on msg.dedupe_key
  edit(handle: MessageHandle, msg: OutboundMessage): SendResult     // may return a new handle (superseded)
  finalize(handle: MessageHandle, summary: RichText, keep?: ChoiceSet): SendResult
  delete(handle: MessageHandle): void
  ack(interaction: InteractionAck, toast?: string): void
  react(handle: MessageHandle, emoji: string, on: bool): void
  typing(chat: ChatRef): void
  registerCommands(specs: [CommandSpec]): void
}
type CommandSpec = { name: string, description: string, args: [{name, type, required}] }

// Port the MEDIATOR calls (implemented by the core)
interface CoreHandler {
  handle(event: InboundEvent /* principal stamped */): void          // must be idempotent on (vendor, vendor_event_id)
  subscribedReactions(chat: ChatRef): bool                          // whether raw reactions are wanted
}
interface IdentityResolver { resolve(id: PlatformIdentity): Principal }   // core-owned

// ---------- Callback tokens ----------
type CallbackToken = string   // base64url(34 bytes) = 46 chars; <=64 bytes Telegram, <=100 chars Discord
interface TokenCodec {
  encodeDirect(action: uint16, subject: int64, value: bytes14): CallbackToken
  encodeIndirect(slot: InteractionSlot): CallbackToken            // subject = slot_id
  decode(token: CallbackToken): CallbackRef | InvalidToken         // verifies HMAC[key_id]; loads slot if indirect
  rotateKeys(active: [key_id -> key], primary: key_id): void
}

// ---------- Conversation state (core-owned) ----------
type ConversationSession = { id: UUID, principal_key: string, chat: ChatRef, flow: string, step: string,
  step_seq: int, data: json, version: int, expires_at: Instant, last_prompt?: MessageHandle }
type Step = { prompt: (data) -> OutboundMessage, accepts: set<"interaction"|"text"|"form">,
  validate: (data, input) -> Ok(value) | Err(message), next: (data, value) -> string | Done | Abort, back?: string }
type Flow = { name: string, entry: string, steps: map<string, Step>, on_done: (data) -> Command, ttl: Duration }
interface WizardEngine {
  start(principal, chat, flow): ConversationSession
  handle(session_id, input: InboundEvent): void      // load FOR UPDATE, validate, persist (version++), finalize old prompt, send next
  sweep(now): void                                    // "Still there?" then abort after grace
}

// ---------- Delivery (core -> mediator) ----------
type DeliveryPolicy  = { mode: "primary"|"all"|"first_available", expires_at?: Instant }
type DeliveryRequest = { dedupe_key: string, account_id: UUID, message: OutboundMessage, policy: DeliveryPolicy, correlation_id: string }
type DeliveryTicket  = { delivery_id: UUID }
type DeliveryStateChanged = { delivery_id, dedupe_key, channel: ChatRef, status: "sent"|"failed"|"suppressed"|"expired", handle?: MessageHandle, error?: VendorError }
interface DeliveryPort {
  deliver(req: DeliveryRequest, tx?: Transaction): DeliveryTicket   // outbox row in caller's tx when available
  onStateChanged(listener: (DeliveryStateChanged) -> void): void
}

// ---------- Adapter contract ----------
type CapabilityProfile = {
  native_commands, buttons, select, modal, ephemeral, edit, delete, bot_reactions, reaction_events,
  transient_ack, reply_keyboard: bool,
  max_text: int, max_choices_per_row: int, callback_budget_bytes?: int, ack_deadline_ms?: int,
  markup: "discord_md"|"telegram_html"|"zulip_md"|"matrix_html"|"plain"
}
type VendorOp = Send{chat, text: string /*rendered*/, controls?: NativeControls, ephemeral: bool, marker: string}
              | Edit{handle, text, controls?} | Delete{handle} | React{handle, emoji, on}
              | Ack{ack: InteractionAck, toast?, defer: bool} | Typing{chat} | RegisterCommands{specs}
type VendorError = RateLimited{retry_after: Duration} | NotFound | Forbidden | TooOld | Unsupported | Transient | Permanent
type VendorResult = Ok{handle?: MessageHandle} | Err{error: VendorError}

interface InboundSink { push(event: InboundEvent): "accepted" | "full" }   // non-blocking, bounded per adapter

interface ChatAdapter {
  vendor(): Vendor
  capabilities(): CapabilityProfile
  start(sink: InboundSink, resume_from?: Cursor): void
  stop(): void
  execute(op: VendorOp): VendorResult              // one Retry-After retry max; never invents fallbacks
  resolveChat(identity: PlatformIdentity): ChatRef | Err(VendorError)
  findRecentByMarker?(chat: ChatRef, marker: string): MessageHandle?   // optional, for duplicate detection
  render(text: RichText): [string]                  // vendor escaping + splitting at max_text
}

// ---------- Mediator internals (for implementers) ----------
interface Renderer  { render(msg: OutboundMessage, profile: CapabilityProfile): ([VendorOp], RenderReport) }  // pure
interface Presentations { remember(handle, [index -> CallbackToken], ttl?): void; resolve(handle, index|label|emoji): CallbackToken? }
interface KeyedExecutor { submit(key: string /* vendor:chat_id */, task): void }   // per-key FIFO, cross-key parallel
interface OutboundScheduler { enqueue(vendor, chat_id, priority, op): Future<VendorResult> }  // token buckets + priorities

// ---------- Conformance ----------
type Scenario = { name, profile?: CapabilityProfile, given: [VendorUpdate|InboundEvent], when: [VendorOp|OutboundMessage], expect: [Assertion] }
interface FakeVendorServer { script(responses): void; inject(update: VendorUpdate): void; calls(): [VendorCall]; clock: FakeClock }
interface FakeAdapter extends ChatAdapter { constructor(profile: CapabilityProfile); sent(): [VendorOp]; failNext(error: VendorError): void }