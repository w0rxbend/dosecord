# Dosecord: agent-driven bot UX and reporting plan

Status: UX specification under ROADMAP UX-00 adoption; no shipped-functionality claim.
Baseline: `cc14d7b` on `main`; prepared on `docs/agent-driven-bot-ux-plan`.
Scope: message interface, communication graph, medication/health records, notification reliability and JVM statistics infographics.

## 1. Decision and authority

Build on the existing Scala/JDA system; do not rewrite it. Optimize the bot for recording an action while distracted: one obvious action, immediate acknowledgement, a truthful outcome and a recovery path. Menus configure the system; reminder cards record actions; statistics explain records without advising treatment.

`ROADMAP.md` remains the only product-slice status and delivery-order source. `DESIGN.md` and accepted ADRs remain authoritative architecture. Its UX adoption amendment maps these specification packets to official slices (including split M2.2a-h and M3.4a-d), dependencies and acceptance. Consult Appendix B, not this document or graph, for status. UX-00 adoption requires verified merge/readback before product work; M2.2 remains the next existing eligible product slice.

This is a specification, not authorization to deploy, access secrets, send live Discord messages or change real health records. The owner separately authorizes the normal autonomous issue/branch/commit/PR/CI/merge workflow; delegated local-only scopes still bind. All examples and probes use synthetic data. Development decisions, implementation, review and qualification are designed to run without routine human approval. User-facing confirmations such as Create, Archive and Delete remain essential data-safety interactions; removing human development gates does not remove the user's control over their own records.

### Assumptions chosen without a clarification loop

- Personal, DM-only bot: USER_INSTALL and BOT_DM; no shared-channel health data.
- Preserve the accepted six-category root: Today, Medications, Habits, Reminders, Stats, Account. Unimplemented branches stay invisible. Mood is a quick action on Today and remains accessible by `/mood`, not a seventh top-level category.
- Keep Scala 3.8.4, JDK 25, Mill 1.1.9, Ox, JDA 6.6.0 and PostgreSQL 18. Use deterministic local rendering, not an external image service.
- Initial health breadth means existing mood, medication, planned habits/measurements and generic reminders. Symptom diagnosis, dose recommendation, emergency triage, caregiver sharing and wearable integration are not added implicitly.
- An automated technical release can be qualified without human review. It cannot prove subjective usability, actual phone push receipt or seven days of the owner's regimen use.

## 2. What the repository actually provides

### Keep these foundations

- Pure dose FSM, schedule revisions, DST materialization, adherence/projection functions, transactional action history and outbox protocol. Relevant implementations: `core/src/dosecord/core/domain/Decide.scala`, `Evaluator.scala`, `Adherence.scala`; `core/src/dosecord/core/scheduling/`.
- Identity-stamped, per-chat ordered mediator with callback verification, dedupe and persistent wizard/form sessions: `core/src/dosecord/core/chat/ChatMediator.scala:74-119`, `WizardEngine.scala:60-80`.
- Timezone-only onboarding and a three-step medication wizard, including as-needed schedules and day selection: `core/src/dosecord/core/application/identity/AccountCreateFlow.scala:95-135`; `medication/AddMedicationFlow.scala:154-267`.
- Neutral reminder/outcome copy and Undo/Correct semantics: `core/src/dosecord/core/domain/copy/ReminderCopy.scala:10-80`.
- Real JDA boundary plus a fake wire, USER_INSTALL/BOT_DM registration and acknowledgement measurement: `adapter-discord/src/dosecord/adapter/discord/DiscordAdapter.scala:120-175`; `app/src/dosecord/app/Adapters.scala:38-59,83-88`.

The current reachable root is Today / Medications / Account. Stats, Habits, Reminders and Edit are not shipped (`core/src/dosecord/core/application/menu/MenuHandler.scala:38-47,219-227`). Medication settings are a read-only policy listing, not an editor (`MenuHandler.scala:480-506`). The current command registry has start, today, history, taken, snooze, skip, log, mood, menu, cancel and help (`core/src/dosecord/core/application/CommandRegistry.scala:107`).

### Prioritized gaps: evidence, not speculative modernization

| Priority | Finding | Evidence | Required result |
|---|---|---|---|
| P0 | Executable starts health/adapters but not reminder/materializer/outbox/sweeper services | `app/src/dosecord/app/Main.scala:49-63`; scoped `app/src` symbol search; `Adapters.scala:30-62` | Launch-path integration test proves automatic due delivery, retries, catch-up, expiry and drain; manually starting workers in a test is insufficient |
| P0 | Deferred replies are treated as not-live and normal output uses channel sends rather than completing the interaction | `core/src/dosecord/core/chat/ChatMediator.scala:310-354` | Complete deferred slash replies, edit source cards correctly, preserve declared privacy and test modal deadline fallback |
| P0 | All text chunks share one enforced nonce; durable pagination discards or conflates pages | `DiscordAdapter.scala:300-318`; `core/src/dosecord/core/scheduling/CatalogueOutboxRenderer.scala:328-340`; `ChatMediator.scala:313-338` | Each logical page/chunk has a durable distinct key and recorded handle, stable across retries |
| P0 | Renderer counts buttons, not actual action rows | `core/src/dosecord/core/chat/Renderer.scala:299-345`; `MenuHandler.scala:386-412` | Payload respects five rows including navigation; all overflow remains actionable |
| P1 | Repeated bare Pause/Archive controls have no clear visible medication referent | `MenuHandler.scala:283-316` | Select medication first, then a scoped detail card and actions |
| P1 | Review card can display changed dose/zone while existing-medication Update retains them | `medication/AddMedicationFlow.scala:288-302,343-352` | Review equals persisted fields; unsupported edits cannot appear accepted |
| P1 | `/menu` silently deletes an active draft | `core/src/dosecord/core/chat/WizardEngine.scala:301-304` | Navigation suspends; only explicit discard or documented expiry deletes draft |
| P1 | Two distinct manual-log taps can create two occurrences | `dose/DoseIntakeHandler.scala:380-440` | A single displayed log-action instance commits at most once; Log another is a fresh intent |
| P1 | Sensitive DEBUG records are allowed despite the repository's unconditional privacy rule | `infra/src/dosecord/infra/Telemetry.scala:47-64`; `AGENTS.md` boundaries | No health content or secrets at any log level, including exception/HTTP paths |
| P1 | M1 exit asks for coverage enforcement, but CI is still explicitly unarmed | `.github/workflows/ci.yml:127-145`; `ROADMAP.md:94` | Measure real execution/coverage and arm the existing gate; never equate exit zero with tests executed |
| P2 | No image/attachment model, media store, renderer or report-delivery path | `contracts/src/dosecord/contracts/Chat.scala:143-149,208-230`; `Adapter.scala:75-95` | Approved vendor-neutral media extension and truthful text fallback before image buttons |

These are source-derived risks unless a reproduction is named. Do not change existing green cells retroactively on this analysis alone; record follow-up acceptance evidence and governance amendments. Also reconcile stale sections of `MEDICATION_REMINDER_UX.md` with ADR-012/013 and the current three-step wizard rather than copying its obsolete worker/long-wizard descriptions.

### Execution performed for this analysis

Executed separately through the pinned launcher:

| Command | Observed result |
|---|---|
| `./mill --no-server -j 1 adapter-discord.test` | 36 successful MUnit results, zero failed/ignored; the conformance wrapper contains its declared fake-wire scenarios |
| `./mill --no-server -j 1 core.test` | 755 successful MUnit results, zero failed/ignored |
| `./mill --no-server -j 1 tests.conformance.test` | 5 successful MUnit results, zero failed/ignored |
| `./mill --no-server -j 1 app.test` | 9 successful MUnit results, zero failed/ignored |
| `scripts/check-vendor-symbols.sh` | Clean |
| `scripts/check-sql-now.sh` | Clean |
| Standalone Java2D + XChart 4.0.4 headless fixture | Deterministic 1200x900 PNG, 54,541 bytes; decode/dimensions, empty/single input and NaN rejection passed |

Counts were aggregated from Mill result JSON, not test declarations. An initial multi-selector invocation accidentally supplied a test filter and skipped every core suite; it was rejected as evidence and rerun correctly. Use separate commands or Mill's `+` separator and check nonzero executed counts. Runtime/library warnings were emitted; no compiler failure occurred.

Not performed: full repository/infra integration suite, coverage measurement, final-image font/render test, live gateway smoke, live sends, phone notification checks or visual approval of the sample. The rendering fixture is not a production statistics query and contains no real account data.

## 3. UX contract: simple at the surface, explicit underneath

### Message anatomy and economy

A routine card has a short heading/context, the record or question, then controls. Default prompts: no more than two short paragraphs and 160 characters of fixed copy. Outcome copy: at most 80 characters. User content/instructions are exceptions, with explicit validated length limits and a Details route; do not truncate data silently. Use text hierarchy before decorative embeds. No repeated introduction, chatbot chatter, unsolicited advice, streak pressure or generic motivational filler.

Navigation edits one active navigation card. Setup updates one setup card. A reminder remains its own independent actionable record; navigation never replaces it. Committed actions update/finalize the associated reminder card, rather than posting duplicate toast and persistent success messages. If an edit target disappears, send one useful replacement and record the new handle. Older dose controls still refer to the original occurrence; old menu controls recover to the current view without changing records.

Routine navigation, setup, outcomes, history and requested reports are non-notifying. A due/repeat/snooze reminder follows the persisted notification policy. Operational alerts are silent, rate-limited and kept away from the reminder/outcome exchange. Read-only screens never create dose actions.

### Navigation rules

- Every nested screen: Back returns to its parent or saved navigation context; Menu returns to root. Previous/Next change a cursor page, not the parent. Use one reserved navigation row.
- Show at most four actionable dose rows plus the navigation row on Discord as a starting layout. Derive the true bound from rendered controls: a select consumes one whole row; labels/components and rows are separate budgets. Other layouts may pack multiple controls only if referents remain obvious.
- Every visible action carries a record-scoped target. Never make users map several identical buttons to a distant list. A dose-detail card names its medication/time; a medication-detail card owns Pause/Resume/Archive.
- Choose lists with selects where helpful; menus with many records get cursor pagination and search/autocomplete. Preserve filters on Back. No hidden actionable overflow routed only to read-only history.
- Hide unavailable branches at the source. Enabling a flag is permitted only when every emitted route, permission and recovery edge has an implemented handler.
- Avoid latest-record guessing for ambiguous `/taken` or `/skip`: show a picker when multiple candidates are plausible. One-tap reminder buttons remain direct and unambiguous.

### Interaction budgets

Count from a named starting state; form typing is not counted as a tap.

| Task | Budget |
|---|---|
| Taken, Skip, each offered Snooze, Undo on visible reminder | One tap |
| Correct from outcome with Now/At scheduled time | Two taps |
| Log as-needed dose from Today or `/log` picker | At most two taps |
| Open Today from root | One tap |
| Add scheduled medication from Medications | Open form, submit form, pick cadence, confirm; custom days/zone are explicit branches |
| Routine nested destination | At most three menu transitions from root, excluding record selection/pages |
| Switch stats period | One tap, same navigation card |

Initial interaction acknowledgement targets p95 below 1.5 seconds measured from creation. A modal-opening action must not be deferred; a missed deadline uses a persisted FormRunner and increments degradation telemetry. A modal submission acknowledges independently. Database/render/upload work must not occur on the JDA listener or acknowledgement critical path.

## 4. Communication graph and concrete cards

Machine-readable target: [design-tracks/bot-ux-graph.json](design-tracks/bot-ux-graph.json). It declares nodes, edges, feature gates and work-packet dependencies. It is a target UX contract mapped by ROADMAP, not a claim that every route exists. Future implementation must reconcile emitted controls with these edges and actual handlers.

### Target overview

```text
/start -> Create account -> Timezone -> Confirm local time -> Root
Linked /start or /menu -> Root
Root -> Today | Medications | Habits* | Reminders* | Stats* | Account

Today -> Due page -> Dose detail -> Taken / Skip / Snooze -> Outcome
      -> Completed page -> Dose detail -> Undo / Correct / Add note*
      -> Log dose -> Medication picker -> Outcome -> Log another
      -> Log mood -> Mood level -> Optional note/tags -> Outcome
      -> Habit check-in*

Medications -> Picker -> Medication detail
   Detail -> Log | History | Reminder settings | Pause/Resume
          -> Edit* | Archive confirmation
   Add -> Text modal -> Cadence -> Custom days(optional) -> Review -> Saved

Stats* -> 7-day overview -> 30-day | By medication | Timing
       -> Infographic* | Text view
Habits* -> Picker -> Check-in | History | Settings | Add
Reminders* -> Picker -> Detail -> Done | Snooze | Settings | Add
Account -> Timezone | Notifications* | Privacy* | Export/Delete*

All nested views -> Back or Menu
Active draft -> Menu -> Suspended -> Continue setup -> Exact saved step
Any direct dose tap -> Dose outcome; draft unchanged
Stale wizard tap -> Notice + current step
Explicit Cancel -> Draft discarded, no partial medication
Downtime -> One catch-up digest -> Original dose details
```

Asterisk denotes a gated branch. Existing-account `/start` becomes a route to root, not a second-create error; backend duplicate-create refusal remains intact.

### Example cards: proposed, synthetic copy

Root:

```text
Today · Tue 14 Oct
2 doses open · next 21:00
[Today] [Medications] [Habits]
[Reminders] [Stats] [Account]
[Continue setup]                 only while a draft exists
```

Hide unshipped categories and omit counters not supported by the current snapshot. Do not invent a status just to fill the header.

Due card:

```text
Time for Vitamin D, 1000 IU.
With breakfast.                 only user-supplied instructions
[Taken] [Snooze 10m] [Skip]
[Snooze 30m] [Snooze 1h]
```

Offer only policy-valid snoozes. Do not replace direct buttons with an extra snooze menu just to make the card smaller. Once taken: `Recorded at 09:03.` [Undo] [Correct]; Add note appears only when M3.1 is qualified. Once snoozed: `Snoozed until 09:30.` Missed: `No response recorded for the 09:00 dose.` [I took it] [Skip] [Keep missed]. Preserve recorded state and correction/late-window semantics; this copy is not dose advice.

Medication detail:

```text
Vitamin D · 1000 IU
Every day · 09:00 · Europe/Kyiv
[Log dose] [History] [Reminder settings]
[Pause] [Edit] [Archive]
[Back] [Menu]
```

Only expose Edit when profile/schedule editing exists. Pause is tracking/notification suspension, never a recommendation to stop treatment. Archive confirmation explicitly says schedule stops and history stays. For multiple schedules, offer schedule selection rather than taking the first silently.

Setup review:

```text
Vitamin D · 1000 IU
Every day · 09:00 · Europe/Kyiv
With breakfast.
[Create] [Change days] [Change timezone]
[Back] [Cancel]
```

For an existing medication, show exactly the values that will persist. Until full editing exists, label the action Update schedule and state that dose/instructions/zone are unchanged; do not show edited values that will be discarded.

Schedule-specific Reminder settings remains available under its baseline gate. UX-04 explicitly adds its missing policy editor: timing offset (accepted presets or validated value), offered snooze minutes and response/miss window. Copy the current policy, preserve unedited fields, review exact values and persist a version-checked schedule revision on Save. Invalid input preserves answers and writes nothing. These are tracking/notification preferences, never recommended clinical timing. Saved: `Medication saved.` [Open medication] [Today]. Onboarding completion: `Account ready.` [Add medication] [Open Today].

### Drafts, errors and action safety

Taken on an original reminder or dose detail obeys the persisted late-log window: eligible current records commit; an already-taken record returns its existing outcome; a skipped or too-old record opens Correct without mutation; cancelled records are refused. A three-day-old original reminder must take the correction path, not a direct new Taken action.

Menu/navigation suspends, does not discard. Root offers Continue setup; answers and step are preserved through restart. Starting a different setup presents [Discard draft] [Continue setup], not Yes/No. Explicit Cancel discards; retain the 30-minute idle + 30-minute grace policy unless UX-00 records a change. Explain expiry without scolding; a stale submit cannot recreate a deleted draft. Stale-step recovery retains session/source context, posts one short notice and re-renders the exact current step; generic invalid-control recovery is separate. Uncertain commits enter an explicit reconciliation state and only replay a readback-confirmed outcome or permit a same-intent retry after confirmed noncommit.

Scheduled actions remain occurrence-scoped and semantically idempotent. Manual-log button intents become single-use action instances. A rapid second tap shows the existing record; Log another creates a new instance. Do not dedupe legitimate distinct entries by a time heuristic. Retry UI work must not duplicate medical actions.

Errors say what is known and offer recovery: `Couldn't save. Nothing was recorded. [Try again]` only when noncommit is established. If the commit outcome is uncertain: `Checking whether this was recorded…`; reconcile the idempotency record before retry. Never tell users an action failed just because the reply send failed. Wrong-account or invalid controls reveal no names/notes and mutate nothing.

## 5. Health and notification behavior

### Today is the primary daily surface

Put open records first, sorted by relevant local time. Provide Completed, Log dose, Log mood and available habit check-ins without turning Today into a settings dashboard. Keep medication adherence and mood distinct. Completed rows are a collapsed/pageable section. Next/Previous exposes all open records; every record remains actionable. Baseline seven-day `/history` remains available from M1.9 through daily-driver qualification. M3.2 gates only expanded paging/filter/correction controls. History starts newest first and preserves available day/medication filters on drill-down.

Mood retains `/mood` for power users; add a simple 1–10 picker and an optional note/tags path. One quick check-in does not force a note. Store exact selected numeric values and account-local dates; no emotion diagnosis or clinical interpretation. Existing habit taxonomy (boolean/count/duration/measurement/avoid) comes from M7.4, not new unbounded health-tracker scope.

### Notification contract

- Initial due/repeat/snooze behavior follows the existing persisted policy and accepted limits, not a new UX heuristic. Keep the accepted default maximum three reminders and time bounds; test each state/cadence.
- Quiet hours retain deliver/defer/silent semantics from ADR-012. Do not equate a deliberately deferred reminder with delivery failure or missed intake.
- Explicit snooze intent retains its established quiet-hour behavior. Navigation/report jobs never delay due delivery.
- A confirmed delivery with no response and an outage/undelivered interval remain different: missed versus unknown. Render them differently and exclude unknown from adherence.
- Recovery collapses stale pushes into one digest per accepted bucket. Digest links to original records with epoch revalidation. No replay storm after downtime.
- Optional mood/habit nudges and weekly summaries are separately opt-out-aware and quiet-hour-aware; no default flood. Summary delivery is silent. Keep ops recipient support separate from personal health content.
- Non-BOT_DM invocations receive only the redirect. Discreet covers content, labels, attachments, alt text and filenames. A spoiler is preview control, not encryption. Private detail requests cannot silently degrade into persistent named details.

## 6. Statistics and beautiful, truthful infographics

Ship useful text stats before image infrastructure. No composite health score. No suggested medication changes, causal mood/medication conclusions or shame for skipped records.

### One immutable snapshot, all representations

`StatsSnapshot` (proposed plain model) contains account scope, local period/zone, as-of/source revision, resolved/open/unknown counts, numerator/denominator, optional adherence/on-time rates, optional median/p90 delay and samples, and daily buckets. Text, alt text and PNG use exactly this snapshot. Queries must equal the existing pure `Adherence` oracle (`Adherence.scala:85-117,125-173`).

Unknown/cancelled/manual/open rows stay excluded from adherence denominator; show exclusion counts/coverage separately. Corrected doses remain in appropriate record counts but are excluded from delay samples. Denominator zero is No data, not 0%. Mood gaps are gaps, not zero scores. Keep resolution/effective-time attribution from the authoritative domain rather than inventing chart-only mathematics.

Suggested initial stats card:

```text
Last 7 days · Europe/Kyiv
Taken 12 / 14 resolved · Skipped 1 · Missed 1
2 records unknown and excluded
[7 days] [30 days] [By medication]
[Infographic] [Text view]
[Back] [Menu]
```

The Infographic control stays hidden until UX-15 qualification, which transitively requires durable preparation and qualified attachment transport. Creating an asset in UX-13 does not expose an unfinished user route.

These values are illustrative, never fallback data. Display timing statistics only when samples exist. An empty period explains absence and links to Today/Add medication.

### Visual contract

Original restrained dark/light themes, not a generic chart-library screenshot: a concise title/period, one dominant plot, factual coverage summary and a small legend/footer. Use a deliberately bundled licensed font with tabular numerals, consistent spacing, measured wrapping, neutral surfaces and one accent. No decorative gauges, 3D, rainbow states or glossy gradients. Unknown is neutral; skipped is not a failure-red category.

Initial images: medication status calendar/stacked counts, period adherence counts and timing distribution; mood trend only after its query is qualified, habit panels only after M7.4. Prefer small multiples for unlike units. Cap displayed series; paginate filters rather than squeeze names. Use 1200x900 or a portrait variant as a starting canvas; review downsampled at 360 CSS-pixel-equivalent width. Normal text contrast >=4.5:1 and meaningful graphics >=3:1; labels/shapes distinguish status without color. If dense text is unreadable, increase font/reduce ticks or split the report, not just increase resolution.

Use XChart 4.0.4 plot primitives with Java2D composition outside core/contracts. The standalone smoke proves local feasibility only. Final-image font/native-library support, licenses and production resource limits are a go/no-go spike. The existing permissive license gate makes adopting other chart libraries a deliberate decision, not a hidden exception.

### Proposed media/report architecture

- Add a separately owned `report-renderer` Mill module; plain snapshots in, bounded PNG bytes out. No adapter/infra dependency. Fresh chart/Graphics2D per job, disposed resources, no external fonts/URLs and no arbitrary user images/markup.
- Extend serializable messages with default-empty `AttachmentRef` descriptors: opaque asset ID, safe filename, validated MIME/size/dimensions/hash and bounded redacted alt text. No JDA/AWT/streams/local paths/CDN URLs in contracts. Explicit capability support; unsupported vendors retain the full useful text summary.
- Add a bounded durable report-preparation queue and transient PostgreSQL bytea assets. Render after acknowledgement and outside DB locks. Publish immutable bytes and enqueue send atomically; a queued asset reference must always hydrate to its own account's bytes.
- Inject asset hydration from app/infra into the transport boundary. Fresh JDA FileUpload per attempt, local PNG multipart attachment, description <=1024 characters and safe names. Ordinary persistent DM reports first; restart cannot preserve an ephemeral interaction promise. Optional live ephemeral image replies are a separate acceptance surface.
- First report: one bounded text chunk plus one PNG. Stable request/destination/page keys and asset bytes on retry. Do not store signed provider URLs as durable identities. Attachment edits explicitly retain/replace/remove files.
- Proposed resource defaults: one concurrent render, queue of 32 preparations, <=2 MB PNG, maximum 30-day initial image period, 24-hour request deadline. Treat these as budgets to measure, not achieved facts. A render over budget returns accurate text; it never blocks intake or downgrades a medication channel to dead.
- Terminal assets/snapshots purge within one hour; queued assets remain pinned only until request deadline. GC checks references transactionally. Account erasure cancels pending requests/outbox and invalidates hydration before purge. Backup retention/restore purge must be specified; database deletion alone does not erase physical backups/WAL or Discord copies. Vendor deletion is best effort, not revocation of downloaded images.

## 7. Agent-only development operating model

No routine human review or design-selection loop. Use a coordinator, one owner per official slice, independent UX/security review and independent QA/evidence verification. Research/review can run in parallel; code changes sharing contracts/migrations are serialized after interface freeze.

### Technical implementation order

The owner's UX/images priority is recorded in ROADMAP's **UX/media
implementation-order checkpoint amendment**. That section alone specifies the
bounded existing-slice allowlist, sequential dependency selection and durable
exact-main technical attestations. The matching `AGENTS.md` workflow exception
reconciles technical prerequisites only; activate the order only after its
independent review, adoption/readback and exact-main CI gates pass.
Do not change statuses, packet dependencies, feature visibility, release/live/use
criteria or the asset/transport/visual gates to make the sequence appear eligible.
The normal slice lifecycle below remains binding until activation; afterward
only its implementation-order prerequisite and checkpoint integration use the
ROADMAP exception. Independent reviews, QA and all other gates remain binding.

### Lifecycle

1. Coordinator reads current Appendix B and dependency statuses, then selects one unblocked official slice. UX-00 first maps the proposed packets into that table and records design/ADR/process amendments.
2. Agent creates/claims an issue and isolated short-lived branch/worktree; copies exact criteria and dependency evidence. It owns only that slice. Existing GitHub permissions and branch protections remain authoritative.
3. Owner writes red-capable focused tests and makes the smallest vertical implementation. For graph tasks, test emitted callback routes, payload budgets, tap counts, draft persistence and privacy, not just copy snapshots.
4. Independent UX reviewer inspects actual transcripts/cards/PNG artifacts and checks the graph/meaning. Independent security/correctness reviewer checks ownership, action semantics, log hygiene, retries and schema changes. Neither review is replaced by the author's self-report.
5. QA runs fresh relevant tests plus required CI; aggregates pass/ignored counts; attaches exact commands, hashes, logs and image artifacts. Deliberate seeded failures prove new gates fail closed.
6. Coordinator fixes findings, reruns gates and opens a PR. Merge/auto-merge only through authorized repository automation and required checks. This plan does not grant new token scopes or relax branch protection; remote writes remain with the authorized coordinator, not local-only delegates.
7. After merge, coordinator reads exact main commit and CI results before updating Appendix B. No optimistic green. Append factual release attestation separately from product status.
8. Pick the next dependency-unblocked slice automatically. Failures get bounded attempts, then a durable blocked issue with evidence; continue independent work rather than fabricate completion or request routine human input.

Use issue/PR/CI as durable execution state. Finite subagents are appropriate for bounded research/review, not an overnight orchestration daemon. Any continuing executor must have explicit lifecycle, locks, restart/resume and a durable task store; scheduling/deployment of that executor is not part of this planning deliverable.

### Release semantics: required governance change

ROADMAP adopts separate automated technical attestations, not another status table. Keep `green` (merged, CI, criteria, docs) and `in-use` truthful. Attestations record build/image digest, synthetic evidence and independent review with zero blocking findings. They do not waive existing live/device/use milestone exits: phone receipt, subjective usability and seven days of actual owner use remain unmeasured until observed. No agent pretends to be a user.

An unattended live harness, if already authorized/provisioned, may establish real gateway/DM send/readback and selected actual interaction behavior. Never automate a normal user token/self-bot or forge provider-originated interactions as live evidence. If no permitted harness can produce a live interaction, the gate is blocked; mocks are not relabeled live. Device push and human ease remain unmeasured. Test accounts must contain synthetic records; no acceptance test may alter the owner's regimen. No production deployment or data deletion occurs without its established deployment/backup policy.

## 8. Dependency-aware implementation packets

Packet IDs are specification references. ROADMAP’s UX adoption mapping allocates official slice IDs, acceptance criteria and dependencies; its Appendix B is authoritative. UX-00 must merge before any follow-up product code. M2.2 explicitly includes only the vendor-neutral contract/mediator repairs required for its retained end-to-end interaction criteria; runtime and broader recovery work remain separately owned. Do not defer a required M2.2 repair to its dependent M2.2a. Each mapped official slice requires its own issue/branch and owner; a packet grouping never permits a multi-slice PR. Listed paths are verified anchors, not promises that each packet touches only those files.

| Packet | Goal / roadmap mapping | Depends on | Source/test anchors and acceptance |
|---|---|---|---|
| UX-00 | Governance/spec reconciliation; user-directed repository governance | — | `ROADMAP.md`, `DESIGN.md`, ADR-005/009/012/013 addenda as needed, `AGENTIC_DEVELOPMENT.md`, product UX. Introduce runtime/menu/media follow-ups and automated qualification explicitly; no current green cells changed by assumption |
| UX-01 | M2.2 complete interaction vertical then M2.2a recovery hardening | UX-00 | `ChatMediator.scala`, `DiscordAdapter.scala`, `JdaTransport.scala`; mediator/event/wiring suites. Deferred hook completed; source edit honored; modal deadlines/submissions, mismatched visibility and fallback counted; raw nonce fixture; every acceptance criterion of M2.2 retained |
| UX-02 | Executable runtime integration follow-up before M2.3 | UX-01 | `app/Main.scala`, `Adapters.scala`, existing scheduling/ports/infra; assembled-app integration harness. Real startup owns materializer/loop/outbox/sweeper/wake, recovery, in-flight drain and existing role policy; no direct test-only worker startup |
| UX-03 | Row-safe rendering and durable multi-part delivery follow-up | UX-01 | `Renderer.scala`, `CatalogueOutboxRenderer.scala`, `ChatMediator.scala`, adapter sends and suites. Actual row limits; distinct keys/nonces/handles; crash resume each page; no discarded pages or ambiguous referents |
| UX-04 | Scoped navigation and Today/History discoverability follow-up | UX-03 | `MenuHandler.scala`, `MenuCopy.scala`, `MenuSuite.scala`, renderer goldens. Medication picker/detail, actionable Next/Previous, completed disclosure, quick mood picker over the existing recording handler, schedule-specific policy edit/review/save (offset, snoozes, miss window with other values retained), baseline history retained, exact Back/Menu/filter edges; no dead flag-on routes |
| UX-05 | Draft preservation and truthful setup follow-up | UX-04 | `WizardEngine.scala`, onboarding/add flows and wizard suites. Suspend/resume, explicit discard, completion CTAs; review equals stored profile/timezone; restart/stale/expiry tests at every step |
| UX-06 | Manual-log intent and privacy hardening follow-up | UX-01 | `DoseIntakeHandler.scala`, `LogDosePicker.scala`, `Telemetry.scala`, dose/telemetry suites. Distinct rapid taps at most one record per action instance; intentional Log another allowed; sensitive canaries absent at every log level |
| UX-07 | M2.3 conformance/automated daily-driver qualification | UX-02, UX-03, UX-04, UX-05, UX-06 | Shared suites, executable lifecycle, 30-day simulation and CI coverage gate. Exact tap budgets/transcripts; actual launch delivery; fault/load/privacy gates; authorized live criteria or explicit blocked evidence |
| UX-08 | M2.4 operational minimum and M2.5 docs, independently owned slices | UX-07 | Health/metrics, settings, compose/admin/restore; readiness matches workers, bounded silent ops alerts, restore/rollback drill, accurate setup docs. Do not collapse the two official slices into one PR |
| UX-09 | Existing M3.1/M3.2 record correction and editing UX | UX-07 | Intake/menu/flows/revisions; custom-time/notes/reasons, paged history, effective-date/timezone reconciliation. Split the official slices and retain their dependencies/criteria |
| UX-10 | Existing M3.3 quiet/discreet preferences | UX-07 | Policy/menu/renderer/settings; persisted preferences, no name leakage in all surfaces, explicit snooze semantics and tests. Independent of record-editor work where roadmap permits |
| UX-11 | M3.4 text statistics / immutable snapshot sub-slice | UX-09 | `Adherence.scala`, queries/menu/copy and Testcontainers tests. Query/text equal domain oracle; 7/30-day/per-medication, empty/excluded counts; event invalidation; no new image dependency |
| UX-12 | M3.4a attachment/render-container spike | UX-11 | `contracts/Chat.scala`, `Adapter.scala`, renderer/profile goldens, proposed `report-renderer`, Docker/font/license gates. Pure refs/backward JSON; headless final-image proof and explicit go/no-go |
| UX-13 | M3.4b durable report assets/preparation | UX-12, UX-08 | Report ports/infra migration, coordinator/outbox/wake. Atomic publication, bounds, stable retries, retention/GC/erasure races and text fallback |
| UX-14 | M3.4c JDA media transport/conformance | UX-13, UX-07 | JDA transport/fake HTTP fixture. Real multipart bytes/MIME/name/alt/flags/nonce; fresh uploads, size limit, edits, cross-account rejection and expiry behavior |
| UX-15 | M3.4d infographic UX qualification | UX-14, UX-10 | Stats screens, renderer fixtures and agent artifact review. Truthful same-snapshot cards, discreet/empty/mobile/Unicode visuals, overload isolation and launch-path end-to-end report |
| UX-16 | Existing M3.5 weekly summary integration | UX-11 | Generic reminder kind; one silent opt-out-aware text summary per account/week, restart idempotency. Image enhancement additionally depends on UX-15 and is separately gated; a rendering no-go cannot block text/habit/reminder delivery |
| UX-17 | Existing M7.4 habits/measurements, then M7.5 mood breadth | UX-16 | Respective official flow/query/stats/NLU slices. Exact taxonomy, rest-day neutrality, optional notes, bounded simple Today check-ins; no health causality claims |
| UX-18 | Existing M7.6 generic reminder menu UX | UX-16 | Reminder create/detail/Done/Snooze, one action per occurrence, simple date/recurrence setup, Back/Cancel and history |

M4 security/identity/export/erasure and later vendor work retain their official dependencies and can interleave where currently allowed. Privacy/export/delete menu branches remain gated by M4; neither the graph nor image work grants those handlers early. Do not drag all breadth into the daily-driver release. The machine-readable packet dependencies describe this initiative, not a replacement for the roadmap's complete graph.

## 9. Required verification matrix

Every official slice issue must copy its existing acceptance criteria AND the relevant additions below. New commands/suites are proposed until actually created; use the current launcher selectors, not invented targets.

### Behavioral and graph gates

- Every emitted control maps to exactly one authorized handler/action and a documented outcome; every nested view has an escape; unavailable flags emit nothing. Test feature combinations, long content and deleted records.
- Actual JDA-model serialization: <=5 rows, <=5 buttons/row, valid select bounds, <=2000-character chunks, callback budgets and modal limits. Test zero, one, four, five, eight and many records, not only the old eight-row cap on Console.
- Generated shortest-path/tap assertions from graph plus scenario transcripts. Verify action referents in visible copy; token identity alone is insufficient.
- Restart at every form/step, stale/back/cancel/menu handling, draft expiry, conflicting flows, 3-day-old reminders, duplicate vendor delivery and distinct simultaneous taps.
- Assembled executable against disposable Postgres/fake vendor: normal account/schedule path, automatic claim/send, retry, clock jump/digest, sweeper and SIGTERM within 20 seconds. Test shutdown during DB/vendor/render work. Do not manually wire missing services only in the test.

### Correctness, privacy and fault gates

- Projection/fold and SQL/query versus domain oracle; exact effective-time/date attribution; unknown/cancelled/manual/correction exclusions; zero denominator and DST/zone-travel cases.
- Crash before/after vendor accept/handle record and every durable page; bounded flagged duplicate semantics, no duplicate domain actions. Outbox/retry and attachment failure never corrupt intake records.
- Foreign-account/tampered/stale controls, unsafe asset IDs/MIME/dimensions/hash, missing assets and privacy-version changes cannot disclose health values. No arbitrary remote resource fetching.
- Sensitive canary capture across DEBUG/INFO/WARN/ERROR, libraries, SQL binds, exceptions and spans; graph/report metrics contain no names/notes. User passwords remain outside chat.
- Measure core coverage, arm existing 85% gate, verify new seeded failures make CI fail. Never improve coverage by deleting scenarios or blind golden regeneration.

### Visual, container and load gates

- Same snapshot produces text/alt/chart; all labels/units/timezone/range are explicit. Empty, all-unknown, all-skipped, single point, missing dates, long Unicode labels and no-data samples have named fixtures.
- PNG decode/dimensions/byte budget and layout geometry/text bounds; pinned JDK/fonts for goldens, deliberate tolerances for cross-runtime pixels. Contrast and noncolor-only semantics are machine checked.
- Independent image-capable agent checks real rendered outputs at phone width in both themes; if image tooling is unavailable, mark visual qualification blocked, not passed by text inspection. Agent review is a design gate, not a claim of human usability.
- Final non-root container renders with headless mode and no network/display, correct glyphs, licensed fonts and no ImageIO disk leak. No OOM under current Xmx256m; proposed <=300 MB peak RSS, existing <=200 MB idle and SIGTERM <=20 seconds. These are pass budgets, not measurements from this plan.
- Proposed warm render p95 <=2 seconds over 100 bounded samples. Report-flood test keeps ack p95 <1.5 seconds and synthetic due-to-vendor dispatch p95 <10 seconds. Actual mobile push latency is a separate unmeasured claim.
- Queue/rate limits and cancellation tested; a saturated report path returns useful text and leaves reminders/intake responsive. Failing budgets produces a no-go or explicitly amended resource decision.

### Canonical existing checks

```sh
./mill --no-server -j 1 __.checkFormat
./mill --no-server -j 1 __.fix --check
./mill --no-server -j 1 __.compile
./mill --no-server -j 1 __.test
./mill --no-server -j 1 app.test.runMain dosecord.app.envExampleCheck .env.example
scripts/flyway-validate.sh
scripts/check-vendor-symbols.sh
scripts/check-sql-now.sh
scripts/doc-lint.sh
scripts/license-gate.sh
./mill --no-server -j 1 core.scoverage.xmlReport
COVERAGE_GATE_ARMED=true scripts/coverage-gate.sh
docker build -f docker/Dockerfile -t dosecord:ci .
```

Run integration/container checks only against disposable infrastructure under the existing CI conventions. Add real launch, graph/media/font/visual/latency checks through approved packets; they do not exist merely because this plan lists them.

## 10. Evidence contract and immediate next action

For each packet/slice, record criterion ID, exact test/scenario, command, executed/ignored counts, artifact path/hash, environment/build revision and pass/fail/blocked. Reviewers read actual outputs; the coordinator verifies remote issue/PR/CI state before claiming a transition. Missing tools/credentials are blocked criteria, never synthesized evidence. No claim of best-in-class or fully usable follows from attractive screenshots alone.

Immediate action: land UX-00's governance/spec amendment, then complete M2.2 and the explicitly allocated runtime/row/delivery/menu safety follow-ups before M2.3. Build text stats before media and keep later health breadth in its existing slices. This plan and graph have no separate completion-status table.

Installed default-profile skills supporting execution:

- `discord-jda-development`: repository-fit JDA interactions, lifetimes, menu/delivery and attachment fixtures.
- `jvm-chart-infographics`: deterministic JVM chart composition, resource/privacy/visual qualification; executable synthetic Java fixture.
- `data-visualization` from Anthropic's knowledge-work repository: chart selection/design reference only; use JVM implementations in Dosecord.

Existing `direct-style-scala`, `visualizing-data`, `claude-design`, UX-copy, BDD and review skills complement them. Keep task-specific lessons there, not in global memory.

## 11. Primary references

- [Roadmap](ROADMAP.md), [design](DESIGN.md), [product UX](MEDICATION_REMINDER_UX.md), [agent process](AGENTIC_DEVELOPMENT.md), [ADR-005](adr/ADR-005.md), [ADR-009](adr/ADR-009.md), [ADR-012](adr/ADR-012.md), [ADR-013](adr/ADR-013.md).
- Discord interactions: https://docs.discord.com/developers/interactions/receiving-and-responding — initial response and interaction lifetime; do not confuse provider hooks with durable destinations.
- Discord messages: https://docs.discord.com/developers/resources/message — nonce, multipart attachments, descriptions and edits.
- JDA FileUpload: https://docs.jda.wiki/net/dv8tion/jda/api/utils/FileUpload.html; use pinned 6.6.0 sources when current Javadocs differ.
- XChart pinned artifact/POM: https://repo.maven.apache.org/maven2/org/knowm/xchart/xchart/4.0.4/; source https://github.com/knowm/XChart. Moving branch examples are not proof of pinned APIs.
- JDK Java2D API: https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Graphics2D.html.
- Contrast: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html.
