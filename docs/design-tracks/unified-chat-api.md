Note: this is the language-agnostic input (Track A, unified chat API and mediator) to docs/DESIGN.md; the Scala 3 realisation lives in DESIGN.md sections 3-6 and ADR-005, ADR-006 and ADR-013.
Note: reference material, not a normative spec on its own; where this file and DESIGN.md differ, DESIGN.md wins.

# Dosecord Track B: Scheduling, Reminder Delivery and Intake Recording

Language-agnostic. Assumes a relational store with row locks (PostgreSQL semantics are used for concreteness), a worker process, and a vendor-neutral `Dispatcher` interface owned by the bot core (Track A). Nothing here depends on Kafka; the "outbox" is a table, and whatever publishes it is pluggable.

## 0. What is kept from docs/MEDICATION_REMINDER_UX.md and what changed

Kept verbatim in spirit: backend is the source of truth; every dose is a first-class occurrence with `scheduled_for` separate from `taken_at`; the pending/due/snoozed/taken/skipped/missed/cancelled vocabulary; adherence = taken/(taken+skipped+missed) with skipped shown separately; `unknown` as a category; the reminder policy fields and defaults (offset 0, snooze [10,30,60], miss_after 120, max_reminders 3, discreet); FOR UPDATE SKIP LOCKED; the safety copy; the menu/callback shape.

Changed, with reasons:

1. **One loop, one column.** The doc has a reminder worker and a missed-dose worker selecting on different predicates. Replaced by a single `next_action_at` column and one claim query; the action to perform is derived from state. Two loops racing over the same rows is the classic source of "reminded after being marked missed".
2. **Occurrence natural key.** `(schedule_id, revision, local_date, slot_key)` is UNIQUE. Materialization becomes `INSERT ... ON CONFLICT DO NOTHING`, so any worker, any number of times, produces the same rows. The doc had no dedupe key, which is where duplicate reminders come from.
3. **Schedule kinds normalized.** `weekly` and `fixed_times` merged (weekly is fixed_times with a day set); weekend times are just per-day slot groups; the two interval variants become one `chain` kind with an anchor mode; taper wraps any rule in dated phases. Fewer kinds, one evaluator.
4. **`unknown` is a real status, not a footnote.** Occurrences whose whole window elapsed while the system was down and no reminder was ever sent become `unknown`, not `missed`. Missed means "we asked and got no answer". This is both fairer and keeps outage days out of the adherence denominator.
5. **Action log is the truth; status is a projection.** Terminal states are correctable (undo, late log, correction) by appending actions; the occurrence row is rebuilt from the log. The doc's lifecycle had no undo edge.
6. **Snooze rules defined**: bounded by the next occurrence of the same schedule, capped by `max_snoozes`, extends the miss deadline, does not consume `max_reminders`.
7. **Quiet hours semantics defined** (the doc listed the field but no behavior).
8. **Schedule edits create revisions** (append-only) instead of mutating the rule in place; pending occurrences of the superseded revision are cancelled with a reason.
9. **Dispatch layer with `send_key`** and per-channel delivery attempts, supporting several linked platforms with primary/fallback.
10. **Reminder policy grew** `repeat_every_minutes`, `max_snoozes`, `late_log_window_minutes`, `quiet_hours_mode`, `tz_follows_user`.
11. **As-needed logs create manual-origin occurrences** so history and stats have one query path.

## 1. Schedule representation and evaluation

### 1.1 Rule model

A schedule is `(medication_id, tz, start_date, end_date?, rule, reminder_policy, revision)`. `rule` is a tagged union (see key types). All wall-clock values are local to `tz` (IANA). Two families:

**Slot rules** (calendar-driven; occurrences are known in advance):

| kind | parameters | active-day predicate | local times |
|---|---|---|---|
| `fixed_times` | `slot_groups: [{days: {mon..sun}, times: [HH:MM]}]` | day in any group's `days` | union of matching groups' `times` |
| `every_n_days` | `period_days`, `times` | `(local_date - start_date) mod period_days == 0` | `times` |
| `every_n_weeks` | `period_weeks`, `days`, `times` | `weeks_between(start_week, local_week) mod period_weeks == 0 AND weekday in days` | `times` |
| `cycle` | `on_days`, `off_days`, `times`, `phase_offset` | `((local_date - start_date) + phase_offset) mod (on+off) < on_days` | `times` |
| `interval_fixed_start` | `anchor_time`, `interval_minutes`, `max_per_day?`, `active_days` | day in active_days | anchor, anchor+N, ... (see 1.3) |
| `taper` | `phases: [{from_date, to_date, dose, rule}]` | delegate to the phase whose range contains local_date | delegate; occurrence stores the phase dose snapshot |

"Daily", "multiple daily", "selected weekdays" and "different weekend times" are all `fixed_times` with one or more slot groups. Slot keys are `"HH:MM"` (slot kinds) or `"i:<n>"` (interval index within the day).

**Chain rules** (event-driven; later occurrences depend on user behavior):

| kind | parameters | behavior |
|---|---|---|
| `chain` with `anchor=first_taken` | `interval_minutes`, `doses_per_day`, `day_start`, `day_cutoff`, `first_dose_prompt_time?` | No occurrence exists until the user logs the first dose of the local day (optional gentle prompt at `first_dose_prompt_time`). On that intake, occurrences 2..N are materialized at `first_taken_at + k*interval`, truncated at `day_cutoff`. Slot keys `c:1..N`. |
| `chain` with `anchor=each_taken` | same | Same, but only the *next* occurrence is materialized, at `taken_at + interval`; when it is taken, the next is created, until `doses_per_day` or `day_cutoff`. If a chain dose is skipped or missed, the next is anchored at that occurrence's `scheduled_for` (so the chain does not stall). |
| `as_needed` | `max_per_day?`, `min_gap_minutes?` | Never materialized. Manual log creates an occurrence with `origin=manual`, `status=taken`, `scheduled_for=taken_at`. Limits, if set, are shown as information ("last logged 2h ago"), never as advice. |

### 1.2 Evaluation

One pure function: `occurrences(rule, tz, revision, from_utc, to_utc) -> [(local_date, slot_key, scheduled_for_utc, dose_snapshot)]`. Algorithm for slot kinds: iterate local dates from `local_date(from_utc - 1 day)` to `local_date(to_utc + 1 day)` (the padding handles UTC/local day misalignment), apply the active-day predicate, expand times, resolve each `(local_date, HH:MM)` to an instant with the disambiguation policy below, keep those within `[from_utc, to_utc)`. Deterministic: same inputs, same output, regardless of when it runs. That determinism is what makes reconciliation and tests cheap.

### 1.3 DST and timezone edge cases

- **Nonexistent local time (spring-forward gap)**: add the gap length, i.e. 02:30 on a 02:00->03:00 day becomes 03:30 local. This matches the default of Java `ZonedDateTime.of`, Python `zoneinfo` (fold=0) and JS `Temporal` `disambiguation:'compatible'`, so any implementation language agrees with the stored value.
- **Ambiguous local time (fall-back fold)**: pick the *earlier* instant (first occurrence, pre-transition offset), again the common default. Exactly one occurrence per `(local_date, slot_key)` by construction; the UNIQUE key makes it impossible to double-materialize the repeated hour.
- **Interval and chain kinds** compute in absolute time from a resolved anchor: "every 8 h" across a 25-hour day yields the extra dose; across a 23-hour day one fewer. Their `slot_key` is the index, so keys stay unique even if wall-clock times collide.
- **Windows and snoozes** are absolute durations added to instants; they never touch wall clocks.
- **User changes timezone**: `users.timezone` updates; every schedule with `tz_follows_user=true` gets a new revision with the new tz (same rule), which triggers the reconciliation in section 2. Occurrences already `due`/`snoozed` are left alone. Schedules with an explicit tz (e.g. a clinic-time regimen) are untouched, and the bot asks which behavior the user wants at the moment of change.
- **tzdata updates** (2026c is current; governments change rules with weeks of notice): the rolling horizon is short (48 h), and a nightly verify pass recomputes `scheduled_for` for every pending occurrence from its natural key and rewrites it if different (logged as `system:tz_rules_changed`). Deploy tzdata as an explicitly pinned, upgradable dependency, not whatever the base image has.
- **Local date column**: every occurrence stores `local_date` and `tz` at generation so stats and "Today" never re-derive dates in SQL.

## 2. Materialization and reconciliation

**Strategy: rolling horizon for slot rules, reactive for chains, on-demand for previews.** Rows exist for `now .. now + 48h` (config `HORIZON`). Anything further ("show next week") is computed by the same pure function without persistence. Rationale: the reminder loop needs an indexed table of instants; previews do not; a long horizon only multiplies the rows to cancel on every edit.

Materializer (runs every 15 min and on demand): for each `active` schedule with `materialized_through < now + HORIZON`, compute occurrences in `[materialized_through, now + HORIZON)`, insert with `ON CONFLICT DO NOTHING`, set `materialized_through`. Each inserted row gets `status=pending`, `due_window_start = scheduled_for + initial_offset`, `miss_deadline = scheduled_for + miss_after`, `next_action_at = due_window_start`. If `miss_deadline` is already in the past at insert time (see section 5), the row is created as `unknown`.

Triggers for immediate materialization: schedule create/resume/revision, tz change, chain intake.

**Reconciliation on revision** (edit, tz change): in one transaction, insert the new `schedule_revisions` row, cancel (`status=cancelled, cancel_reason=superseded`) all `pending` occurrences of the old revision with `scheduled_for >= revision.effective_from`, reset `materialized_through = effective_from`, materialize. `due`/`snoozed` occurrences keep living under the old revision until resolved; the reminder message already exists and its buttons must keep working. Nothing is deleted.

**Pause**: cancel `pending` with reason `paused`; `due`/`snoozed` stay (user may still answer); chain days stop. **Archive**: additionally cancel `due`/`snoozed` with reason `archived` and edit their messages to remove buttons. **Resume**: new revision (`effective_from=now`), materialize. Cancelled occurrences are excluded from all statistics.

## 3. Reminder loop

**Polling with a claim query, not a timer wheel.** Tick every `TICK` (5 s default). The DB is the only state; there is no in-memory schedule to rebuild after a crash, no leader election, and horizontal scale is free via SKIP LOCKED. A timer wheel only buys sub-second precision, which a chat reminder does not need. Optional wake-up on `LISTEN/NOTIFY` when a snooze or immediate action lands, to shorten latency without shrinking `TICK`.

Claim (one statement per batch):

```
SELECT ... FROM dose_occurrences
WHERE next_action_at <= $now AND status IN ('pending','due','snoozed')
ORDER BY next_action_at LIMIT 100 FOR UPDATE SKIP LOCKED
```

`$now` is passed from the injected clock, never `now()` in SQL, so tests can time-travel. For each row the action is derived:

| status | condition | transition | new next_action_at |
|---|---|---|---|
| pending | now >= due_window_start | -> due, reminder_seq=1, dispatch reminder | min(now+repeat_every, miss_deadline) if seq < max_reminders else miss_deadline |
| snoozed | now >= snoozed_until | -> due, dispatch snooze reminder (not counted in max_reminders) | as above |
| due | now < miss_deadline and reminder_seq < max_reminders | dispatch repeat, reminder_seq+1 | as above |
| due / snoozed | now >= miss_deadline | -> missed, dispatch missed notice (subject to quiet hours) | NULL |

Transition, action-log row, dispatch row and `next_action_at` are written in the same transaction, so a crash either did everything or nothing; the row is re-claimed on the next tick. `reminder_seq` counts reminder messages for this occurrence (initial + repeats). Quiet hours (below) may replace "dispatch" with "defer": the state still changes, `next_action_at` moves to the quiet-hours end.

**Exactly-once effect, at-least-once send.** State transitions are exactly-once by construction (row lock + single transaction). Sends go through `reminder_dispatches` with `send_key = "occ:{occurrence_id}:s{reminder_seq}:k{kind}:c{channel_id}"` (kind in reminder|snooze|missed|digest|update), UNIQUE. A dispatch worker claims `queued` dispatches, marks `sending` with `attempt_id`, calls the vendor, stores `platform_message_id` and marks `sent`. If the vendor call succeeds but the process dies before the commit, the retry may send twice: this residual window is accepted and made harmless: callbacks carry `occurrence_id` and are idempotent, and after any resolution the worker enqueues an `update` dispatch that edits every sent message for that occurrence to remove buttons (where the vendor allows editing). Retries use exponential backoff with `max_attempts`; permanent vendor errors (DM closed, user blocked bot) mark the dispatch `failed_permanent` and trigger channel fallback (section 6).

**Snooze.** Allowed transitions: `due -> snoozed`. `snoozed_until = now + m`, where `m` must be in `snooze_options`; the renderer only offers options for which `snoozed_until < min(next_occurrence_of_schedule.scheduled_for - 1 min, scheduled_for + max_late_minutes)` and `snooze_count < max_snoozes` (default 3). `miss_deadline := max(miss_deadline, snoozed_until + repeat_every)`. The snooze reminder does not consume `max_reminders`. Snoozing a `pending` occurrence (early snooze from Today view) is allowed and equals a shifted due time.

**Quiet hours.** Stored per user (`[start, end)` local, may cross midnight), overridable per schedule. Modes: `deliver` (default for medication: the first reminder of a dose explicitly scheduled inside quiet hours is delivered, because the user chose that time), `defer` (first reminder moves to quiet end if that is before `miss_deadline`, otherwise delivered), `silent`. In every mode, repeat reminders and missed notices inside quiet hours are deferred to quiet end, and the state transition happens on time. `nextDeliverableAt(t, quiet)` is the single pure helper.

**Discreet mode.** A rendering flag, not a delivery flag. The dispatch payload carries `discreet=true` and `display_alias` (user-chosen, e.g. "morning pills"); the renderer omits name, dose and instructions and adds a [Details] control whose reply is ephemeral where the vendor supports it, otherwise a normal DM. Buttons and callbacks are unchanged.

## 4. Missed detection, late log, corrections, undo, action log

**Missed** = `miss_deadline` passed while `due` or `snoozed` (so at least one reminder was sent or deliberately deferred by quiet hours). The notice offers [I took it] [Skip] [Keep missed], with the clinician/pharmacist safety line.

**Action log** (`dose_actions`) is append-only (DB role has INSERT only; a trigger rejects UPDATE/DELETE). Every user or system change is one row: `seq` (per-occurrence monotonic), `action`, `actor_type`, `platform`, `platform_identity_id`, `platform_message_id`, `interaction_id`, `idempotency_key` (UNIQUE), `occurred_at` (when the user acted), `recorded_at`, `prior_status`, `new_status`, `effective_at` (the taken_at/skipped_at this action asserts), `reason_code`, `note`, `metadata`, `correlation_id`, `catch_up` flag. The occurrence row's `status`, `taken_at`, `skipped_at`, `missed_at`, `snooze_count`, `reminder_seq` are a projection and can always be rebuilt by folding the log.

**Command idempotency.** Two layers. Transport layer: `idempotency_key` from the vendor interaction id (`discord:interaction:{id}`, `telegram:callback:{id}`, `zulip:message:{id}`, `matrix:event:{id}`); a duplicate key returns the stored result. Semantic layer: applying `taken` to an already-taken occurrence is a no-op that returns the current state ("Already recorded at 09:03") without a new log row. Both are needed: the same human can press the button on two duplicate messages.

**Late log** (`missed -> taken`): allowed always, tagged `taken_late` if `taken_at > due_window_end`. Within `late_log_window_minutes` (default 24 h) the flow is one tap; beyond it the user goes through Correct with an explicit time. The user supplies `effective_at` via [Now] [At scheduled time] [Custom]; custom must satisfy `scheduled_for - 12h <= t <= now`.

**Correction** (`correct(occurrence, new_status in {taken, skipped, missed}, effective_at, note)`) is allowed from any resolved status and logs `manually_corrected` with prior/new. Corrections of `unknown` are how a user repairs an outage gap.

**Undo** applies only to the latest action of an occurrence and within `undo_window_minutes` (15). It appends `undone` with `undoes_seq = prior.seq` and restores `prior_status` and the prior projection fields captured in that row. Undoing a `taken` that had been logged after the deadline returns the occurrence to `missed`; undoing before the deadline returns it to `due` with `next_action_at = min(now + repeat_every, miss_deadline)`. Chains: undoing a `taken` that spawned chain occurrences cancels those with reason `undone_anchor`.

**Skip reasons** are `reason_code` values (forgot, ran_out, side_effects, clinician_advised, other) plus free `note`; adding a note later appends `note_added` and does not change status.

## 5. Catch-up after downtime

Nothing special is needed for correctness because the loop is state-driven: on restart, every row with `next_action_at <= now` is claimed in order. The problem is *behavior*: replaying 6 hours of ticks naively would send three stale reminders and a missed notice per dose. Rules:

1. **Materializer first.** Run materialization before the reminder loop starts claiming. Occurrences created whose `miss_deadline < now` are inserted as `unknown` (never reminded). Occurrences whose `due_window_start < now <= miss_deadline` are inserted `pending` and handled by rule 2.
2. **Collapse, do not replay.** For a claimed row, compute the target state as of `now` directly: if `now >= miss_deadline` and `reminder_seq == 0` -> `unknown`; if `now >= miss_deadline` and `reminder_seq > 0` -> `missed`; else -> `due` with a single reminder (set `reminder_seq` to what it would be, but send one message marked "late reminder"). All such transitions are logged with `catch_up=true`.
3. **Digest per account.** Sends produced during catch-up (`now - next_action_at > CATCHUP_THRESHOLD`, 10 min) are not sent individually. They are folded into one `digest` dispatch per account: "I was offline for 6 h. 2 doses passed without a reminder (marked unknown): Vitamin D 09:00, Iron 12:00. 1 dose is due now: ..." with a [Review] control that opens the correction flow. Digest dispatches share the send_key scheme, so retries are idempotent.
4. **Rate limit** catch-up dispatching (per vendor, per account) so a long outage does not hit Discord/Telegram limits.
5. **Observability.** Emit `reminder.catch_up.duration`, counts of `unknown` created, and a warning if `now - min(next_action_at)` exceeds 2 x TICK during normal operation (loop is lagging).

## 6. Multi-channel delivery

An account owns N `delivery_channels` (one per linked platform identity) with `role in {primary, fallback, off}`, `priority`, `state in {healthy, degraded, dead}`, and `last_ack_at`. Policy per account: `primary_then_fallback` (default) or `broadcast`.

- The reminder loop creates one dispatch for the primary channel. If it ends `failed_permanent`, or has not reached `sent` within `fallback_after` (2 min, vendor ack, not user response), the dispatcher creates the next channel's dispatch (new send_key, different `channel_id`). The occurrence is not touched.
- **Dedupe of user responses**: the first action wins, subsequent ones are semantic no-ops (section 4). The action row records which platform answered.
- **Post-resolution fan-out**: on any resolution the worker enqueues `update` dispatches for every `sent` reminder of that occurrence on other channels: edit to "Recorded via Telegram at 09:03" and remove controls where editing exists; on Zulip/Matrix send a one-line follow-up in the same thread/reply chain; cancel any `queued` fallback dispatches.
- Channel health: permanent failures move a channel to `dead` and notify the user on a healthy channel; success resets to `healthy`.
- Stale messages always work: callbacks resolve by `occurrence_id`, never by message age, and the reply is the current state.

## 7. Adherence statistics

All windows are ranges of `local_date` in the user's timezone, using the stored `local_date` column. Only `origin in (scheduled, chain)` occurrences count toward adherence; `origin=manual` (as-needed) is reported as counts.

Definitions per window and optionally per medication:
- `taken_on_time`: status taken and `taken_at <= due_window_end`.
- `taken_late`: status taken and `taken_at > due_window_end`.
- `skipped`, `missed` as stored; `unknown`, `cancelled`, `pending/due/snoozed` are excluded from the denominator.
- `adherence = (taken_on_time + taken_late) / (taken_on_time + taken_late + skipped + missed)`, undefined (shown as "no data") when the denominator is 0.
- `on_time_rate = taken_on_time / (taken_on_time + taken_late)`.
- `median_delay_minutes` and p90 of `taken_at - scheduled_for` over taken occurrences (median, not mean: one 9-hour late log should not dominate).
- `today`: counts by status including due/snoozed, plus the next occurrence.
- `streak_days`: consecutive local days up to yesterday (today counts only when all of today's doses are resolved) on which every scheduled occurrence is taken; days with no scheduled occurrences are neutral. Shown as "recovery" friendly copy per the product principles.

Query design: index `(account_id, local_date, status)` and `(account_id, medication_id, local_date)`. For 7/30-day windows a direct aggregate over these indexes is fine for years of single-user data. For heavier use, `adherence_daily(account_id, medication_id, local_date, taken_on_time, taken_late, skipped, missed, unknown, cancelled)` is a rollup recomputed for the single affected `(account, medication, local_date)` inside the same transaction as every action; it is idempotent (full recompute of one row), so corrections and undo keep it exact. Stats are read-only queries; no event stream is required.

## 8. Data model

Types: `id` = UUID, instants = `timestamptz` (UTC), local dates = `date`, local times = `time` or `"HH:MM"` text, JSON = `jsonb`.

**users** (existing) + `timezone` (IANA, NOT NULL), `quiet_hours jsonb null`, `discreet_default bool`, `delivery_policy text`.

**medications**: id, account_id FK, name, display_alias, dose_amount, dose_unit, instructions, discreet bool, status (active|paused|archived), created_at, updated_at. UNIQUE `(account_id, lower(name)) WHERE status <> 'archived'`.

**medication_schedules**: id, medication_id FK, account_id, kind, status (active|paused|archived), current_revision int, tz, tz_follows_user bool, start_date, end_date null, materialized_through timestamptz, created_at, updated_at. Index `(status, materialized_through)`.

**schedule_revisions** (append-only): id, schedule_id FK, revision int, effective_from timestamptz, tz, rule jsonb, reminder_policy jsonb, dose_snapshot jsonb, created_by (user|system), reason, created_at. UNIQUE `(schedule_id, revision)`. CHECK rule kind is in the enum; CHECK policy invariants (`miss_after_minutes > 0`, `max_reminders >= 1`, snooze options positive, `repeat_every_minutes > 0`).

**dose_occurrences**: id, account_id, medication_id, schedule_id null (manual), revision null, origin (scheduled|chain|manual), local_date, slot_key, tz, scheduled_for, due_window_start, due_window_end (= miss_deadline at creation; miss_deadline is the mutable copy), miss_deadline, status (pending|due|snoozed|taken|skipped|missed|unknown|cancelled), cancel_reason null, reminder_seq int default 0, snooze_count int default 0, snoozed_until null, last_reminded_at null, taken_at null, skipped_at null, missed_at null, next_action_at null, dose_snapshot jsonb, anchor_occurrence_id null (chains), created_at, updated_at, version int (optimistic lock).
Constraints: UNIQUE `(schedule_id, revision, local_date, slot_key)`; CHECK `status = 'taken' -> taken_at IS NOT NULL`; CHECK `status = 'snoozed' -> snoozed_until IS NOT NULL`; CHECK `status IN ('pending','due','snoozed') = (next_action_at IS NOT NULL)`; CHECK `due_window_start <= miss_deadline`.
Indexes: partial `(next_action_at) WHERE status IN ('pending','due','snoozed')` (the loop's only hot index); `(account_id, local_date, status)`; `(account_id, medication_id, local_date)`; `(schedule_id, scheduled_for)`.

**dose_actions** (append-only): id, occurrence_id FK, account_id, seq int, action (reminder_sent|reminder_deferred|taken|skipped|snoozed|auto_marked_missed|marked_unknown|manually_corrected|undone|note_added|cancelled|catch_up_collapsed), actor_type (user|system), platform null, platform_identity_id null, platform_message_id null, interaction_id null, idempotency_key UNIQUE null, occurred_at, recorded_at default now, prior_status, new_status, effective_at null, reason_code null, note null, undoes_seq null, catch_up bool, correlation_id, metadata jsonb. UNIQUE `(occurrence_id, seq)`. Trigger: reject UPDATE/DELETE.

**reminder_dispatches**: id, send_key UNIQUE, occurrence_id null (digest), account_id, channel_id FK, kind (reminder|snooze|missed|digest|update), payload jsonb (vendor-neutral render model), status (queued|sending|sent|failed_retry|failed_permanent|cancelled), attempts int, next_attempt_at, attempt_id null, platform_message_id null, last_error null, created_at, sent_at. Partial index `(next_attempt_at) WHERE status IN ('queued','failed_retry')`. Index `(occurrence_id)`.

**delivery_channels**: id, account_id, platform_identity_id FK UNIQUE, role, priority, state, last_ack_at, last_error, updated_at.

**adherence_daily** (optional rollup) as in section 7, PK `(account_id, medication_id, local_date)`.

Invariants (enforced by code and, where possible, constraints): exactly one active revision per schedule; an occurrence's projection equals the fold of its actions; `reminder_seq <= max_reminders`; `snooze_count <= max_snoozes`; no two occurrences of the same schedule/revision share `(local_date, slot_key)`; every status change has exactly one action row; `next_action_at` is NULL iff status is resolved; dispatches are never created outside a transaction that also wrote the action row that justifies them.

## 9. Failure modes

| Failure | Effect without mitigation | Mitigation |
|---|---|---|
| Worker crash mid-tick | Half-applied transition | Single transaction per row; re-claim next tick |
| Two workers | Double reminder | SKIP LOCKED claim; UNIQUE send_key; UNIQUE occurrence key |
| Vendor send ok, ack lost | Duplicate message | Idempotent callbacks; post-resolution message edit; bounded retries |
| Vendor down | Reminders silently lost | Dispatch retries with backoff; fallback channel; digest on recovery |
| DM closed / bot blocked | Permanent failure | `failed_permanent` -> channel dead -> notify via other channel |
| 6 h downtime | Burst of stale reminders | Collapse rules + digest + `unknown` status |
| DST gap/fold | Skipped or doubled dose | Deterministic disambiguation; natural key; golden tests |
| User changes tz | Reminders at wrong time | Revision + reconcile; explicit prompt for non-following schedules |
| tzdata rule change | Stale UTC instants in horizon | 48 h horizon + nightly verify pass |
| Schedule edit while due | Buttons on old message break | Old revision's due rows keep living; callbacks resolve by occurrence_id |
| Duplicate button press | Double log rows | Transport idempotency key + semantic no-op |
| Stale message tapped days later | Wrong occurrence changed | Callback carries occurrence_id; reply shows current state; late-log rules apply |
| Snooze past next dose | Overlapping reminders | Snooze options bounded by next occurrence |
| Clock skew between workers/DB | Early/late firing | All loop logic uses one injected clock; DB is not queried with `now()`; alert on lag |
| Loop lag (large batch) | Reminders late | `ORDER BY next_action_at`, batch size, lag metric, more workers |
| Vendor rate limit | Dropped sends | Per-vendor token bucket in dispatcher; retry on 429 with Retry-After |
| Chain intake undone | Orphan chain doses | Cancel with `undone_anchor` |
| Log table tampering | Audit gap | INSERT-only role, trigger, periodic fold-vs-projection check |
| Materializer never runs | No reminders at all | Reminder loop also materializes for schedules with `materialized_through < now + TICK*4`; alert on `min(materialized_through) < now` |

## 10. Test plan

**Clock injection.** Every component takes a `Clock` (`now()`), and every query takes `now` as a parameter. Tests use a `VirtualClock` with `advance(d)` and `set(t)`. The dispatcher is replaced by a `RecordingDispatcher` that captures `(send_key, channel, payload)`.

**Unit (pure evaluator).** Golden tables per kind over fixed date ranges in Europe/Kyiv (2026-03-29 gap, 2026-10-25 fold), America/New_York, Australia/Sydney (southern hemisphere, opposite months), Asia/Kolkata (+05:30, no DST), Pacific/Apia (far east of the date line), America/Sao_Paulo (no DST since 2019). Explicit cases: slot at 03:30 on the gap day resolves to 04:30 local; slot at 03:30 on the fold day yields one occurrence at the earlier instant; every-8h across both days yields 4 and 3 doses respectively.

**Property tests** (generate random rules, tz, revisions, ranges, and for chains random intake sequences):
1. Determinism: `occurrences(r, tz, a, b)` is a pure function; two calls are equal.
2. Composability: `occ(a,c) == occ(a,b) ++ occ(b,c)` for a<b<c (no boundary loss/duplication).
3. Uniqueness of `(local_date, slot_key)` and strictly increasing `scheduled_for` within a day.
4. For slot kinds outside DST transition days, `local_time(scheduled_for) == slot`.
5. For interval kinds, consecutive gaps equal `interval_minutes` exactly.
6. Materialize twice (or concurrently) -> identical row set.
7. Loop equivalence: running ticks every 5 s from t0 to t1 versus a single jump to t1 yields the same final occurrence statuses (sends differ only by digest collapsing); this is the catch-up contract.
8. Idempotency: any action applied twice with the same key equals once; any sequence of actions followed by their undos within the window restores the projection.
9. Projection = fold(actions) after any random action sequence.
10. Snooze options offered never exceed the next occurrence of the schedule.
11. Adherence invariants: 0 <= adherence <= 1; counts sum to total resolved; `unknown`/`cancelled` never change adherence.

**Integration (real database, Testcontainers or equivalent).** UNIQUE and CHECK constraints reject duplicates; append-only trigger; two worker processes claiming the same backlog produce exactly one dispatch per send_key; crash injection (kill after vendor call, before commit) yields at most one extra message and no extra state change; revision reconciliation cancels exactly the expected rows; tz change flow.

**Scenario/simulation.** A deterministic harness drives the virtual clock through 30 days for a set of personas (daily at 09:00, twice daily, Mon/Wed/Fri, every 8 h fixed, chain after first dose, 21/7 cycle, taper, as-needed) with scripted user behavior (answer late, snooze twice, ignore, undo, travel across zones, 6 h outage on day 12) and asserts the full dispatch transcript and final stats against snapshots. Snapshot diffs are the review artifact for any change to the loop.

**Vendor adapter contract tests** (shared with Track A): every adapter must map a reminder render model to a message, return a stable message id, report permanent vs transient failures, and route a callback to `(occurrence_id, action, interaction_id)`.

## Libraries (verification status as of September 2026)

The design needs only: IANA tz resolution with explicit gap/fold policy, and row locking. Verified: JS `Temporal` is Stage 4 and unflagged in Node 26 (`ZonedDateTime` with `disambiguation`); Rust `jiff` 0.2.x is active and TZ-aware, 1.0 still pending; Java `java.time` (built in) and Python `zoneinfo` (stdlib) need no third party; tzdata 2026c is the current release; PostgreSQL SKIP LOCKED remains the standard queue primitive. Recurrence libraries (rrule ports) were deliberately not chosen: the rule set here is small and the DST policy must be explicit, which rrule implementations handle inconsistently.

## Capability matrix
| Capability needed by reminder delivery | Discord | Telegram | Zulip | Matrix | Fallback strategy in the core |
|---|---|---|---|---|---|
| Push a DM without prior user message | Yes (DM channel; user must share a server or have DMs open) | Yes (user must have /start-ed the bot) | Yes (private message to user) | Yes (DM room, bot must be joined/invited) | Channel marked `dead` on permanent failure; user told on another channel; setup flow verifies a test DM |
| One-tap actions (Taken/Snooze/Skip) | Buttons (components, custom_id <= 100 chars) | Inline keyboard, callback_data <= 64 bytes | None native | None native (MSC3381 polls in some clients) | Render model `Actions[]`; Zulip/Matrix render numbered choices + short text commands (`t`, `s10`, `k`) and emoji reactions mapped to actions; the core accepts reactions/replies as callbacks |
| Callback carries occurrence id | custom_id (opaque, fits `a:occ:<uuid>:taken`) | callback_data too small for uuid+action: use a short dispatch token (base62 id of dispatch row, 8 chars) | Message id of reminder resolves occurrence via dispatch table | Reply-to event id or reaction target event id resolves via dispatch table | All vendors resolve `(platform_message_id) -> dispatch -> occurrence`; custom_id/callback_data is an optimization, never the only path |
| Stable message id returned on send | Yes | Yes | Yes | Yes (event_id) | Required by contract; stored on dispatch for edits and callback resolution |
| Edit message / remove buttons after resolution | Yes | Yes (editMessageReplyMarkup/editMessageText) | Yes (edit own message, config-dependent) | Yes (m.replace edit) | If edit fails or unsupported: send one-line follow-up "Recorded at 09:03"; stale taps remain idempotent |
| Ephemeral reply (discreet Details) | Yes (interaction ephemeral) | No (but chat is a DM); answerCallbackQuery alert popup works for short text | No | No | Details sent as normal DM; discreet mode still hides name in the reminder itself and in notification previews |
| Interaction idempotency key | interaction.id | callback_query.id / update_id | message id of the user's command | event_id | All map to `platform:kind:id`; unique in dose_actions |
| Permanent vs transient failure signal | 403 Cannot send messages to this user vs 5xx/429 | 403 bot was blocked / chat not found vs 5xx/429 | 400 user not found vs 5xx | M_FORBIDDEN vs M_LIMIT_EXCEEDED/5xx | Adapter classifies; core retries transient with backoff, marks channel dead on permanent |
| Rate limiting | Per-route buckets, 429 with Retry-After | ~30 msg/s global, 1 msg/s per chat, 429 retry_after | Server-configured | 429 M_LIMIT_EXCEEDED with retry_after_ms | Dispatcher token bucket per vendor; catch-up digests reduce volume |
| Delivery/read receipts | No read receipts | No | Yes (read flags, limited) | Yes (m.receipt) | Not relied on; "ack" means vendor accepted the send |
| Reactions as input | Yes (reaction add events) | Yes (message_reaction updates, bot must be admin in groups; fine in DM) | Yes (emoji reactions events) | Yes (m.reaction) | Core maps configured emoji to actions on Zulip/Matrix as the primary one-tap path; optional elsewhere |
| Threads / reply chains for follow-ups | Reply reference | reply_to_message_id | Topic per medication or DM | m.relates_to in_reply_to | Post-resolution note references the reminder message where possible |
| Slash / command menu | Slash commands | Bot commands menu | Text commands only | Text commands only | Text commands `today`, `took <name>`, `skip <name>` exist on every vendor |

## Key types
// ---------- Clock and time primitives ----------
interface Clock { now(): Instant }                 // injected everywhere; tests use VirtualClock
type Instant   = UTC timestamp (ms precision)
type LocalDate = ISO date in a named zone
type LocalTime = "HH:MM"
type Tz        = IANA zone id, e.g. "Europe/Kyiv"
enum GapPolicy  { ADD_GAP_LENGTH }                 // 02:30 -> 03:30 on spring-forward
enum FoldPolicy { EARLIER }                        // first occurrence on fall-back
fun resolveLocal(date: LocalDate, time: LocalTime, tz: Tz): Instant   // applies both policies

// ---------- Schedule rules (tagged union stored as JSON) ----------
type Weekday = mon|tue|wed|thu|fri|sat|sun

type Rule =
  | FixedTimes      { kind:"fixed_times", slot_groups: [{days:Set<Weekday>, times:[LocalTime]}] }
  | EveryNDays      { kind:"every_n_days", period_days:int>=1, times:[LocalTime] }
  | EveryNWeeks     { kind:"every_n_weeks", period_weeks:int>=1, days:Set<Weekday>, times:[LocalTime] }
  | Cycle           { kind:"cycle", on_days:int>=1, off_days:int>=0, times:[LocalTime], phase_offset:int=0 }
  | IntervalFixed   { kind:"interval_fixed_start", anchor_time:LocalTime, interval_minutes:int>=30,
                      max_per_day?:int, active_days:Set<Weekday> }
  | Chain           { kind:"chain", anchor:"first_taken"|"each_taken", interval_minutes:int,
                      doses_per_day:int, day_start:LocalTime, day_cutoff:LocalTime,
                      first_dose_prompt_time?:LocalTime }
  | Taper           { kind:"taper", phases:[{from_date:LocalDate, to_date:LocalDate,
                      dose:DoseSnapshot, rule:Rule /* non-taper, non-chain */}] }
  | AsNeeded        { kind:"as_needed", max_per_day?:int, min_gap_minutes?:int }

type DoseSnapshot = { name, display_alias?, dose_amount?, dose_unit?, instructions? }

type ReminderPolicy = {
  initial_offset_minutes: int = 0,        // negative = before scheduled_for
  repeat_every_minutes:   int = 10,
  max_reminders:          int = 3,        // initial + repeats
  miss_after_minutes:     int = 120,
  snooze_options_minutes: [int] = [10,30,60],
  max_snoozes:            int = 3,
  max_late_minutes:       int = 360,      // hard cap for snoozed_until relative to scheduled_for
  late_log_window_minutes:int = 1440,
  undo_window_minutes:    int = 15,
  quiet_hours_mode:       "deliver"|"defer"|"silent" = "deliver",
  discreet:               bool = false
}

type QuietHours = { start: LocalTime, end: LocalTime }   // may cross midnight

type Schedule = {
  id, medication_id, account_id, kind, status: active|paused|archived,
  current_revision: int, tz: Tz, tz_follows_user: bool,
  start_date: LocalDate, end_date?: LocalDate, materialized_through: Instant
}
type ScheduleRevision = { schedule_id, revision, effective_from: Instant, tz, rule: Rule,
                          reminder_policy: ReminderPolicy, dose_snapshot: DoseSnapshot, reason }

// ---------- Pure evaluator ----------
type Candidate = { local_date: LocalDate, slot_key: string, scheduled_for: Instant, dose: DoseSnapshot }
fun occurrences(rev: ScheduleRevision, from: Instant, to: Instant): [Candidate]   // pure, deterministic
fun nextDeliverableAt(t: Instant, tz: Tz, quiet?: QuietHours): Instant             // pure

// ---------- Occurrence (projection) ----------
enum OccStatus { pending, due, snoozed, taken, skipped, missed, unknown, cancelled }
enum Origin    { scheduled, chain, manual }
type Occurrence = {
  id, account_id, medication_id, schedule_id?, revision?, origin: Origin,
  local_date: LocalDate, slot_key: string, tz: Tz,
  scheduled_for: Instant, due_window_start: Instant, due_window_end: Instant, miss_deadline: Instant,
  status: OccStatus, cancel_reason?: string,
  reminder_seq: int, snooze_count: int, snoozed_until?: Instant, last_reminded_at?: Instant,
  taken_at?: Instant, skipped_at?: Instant, missed_at?: Instant,
  next_action_at?: Instant,                     // NULL iff status is resolved
  dose_snapshot: DoseSnapshot, anchor_occurrence_id?: id, version: int
}

// ---------- Append-only action log ----------
enum ActionKind { reminder_sent, reminder_deferred, taken, skipped, snoozed, auto_marked_missed,
                  marked_unknown, manually_corrected, undone, note_added, cancelled, catch_up_collapsed }
type DoseAction = {
  id, occurrence_id, account_id, seq: int, action: ActionKind, actor_type: user|system,
  platform?, platform_identity_id?, platform_message_id?, interaction_id?,
  idempotency_key?: string /*UNIQUE*/, occurred_at: Instant, recorded_at: Instant,
  prior_status: OccStatus, new_status: OccStatus, effective_at?: Instant,
  reason_code?: forgot|ran_out|side_effects|clinician_advised|other, note?, undoes_seq?: int,
  catch_up: bool, correlation_id, metadata: json
}
fun fold(actions: [DoseAction], seed: Occurrence): Occurrence   // projection = fold(log)

// ---------- Commands (from bot core, idempotent) ----------
type IntakeCommand =
  | MarkTaken   { occurrence_id, effective_at: Instant|"now"|"scheduled", note?, actor: ActorRef }
  | MarkSkipped { occurrence_id, reason_code?, note?, actor }
  | Snooze      { occurrence_id, minutes: int, actor }
  | Correct     { occurrence_id, new_status: taken|skipped|missed, effective_at: Instant, note?, actor }
  | Undo        { occurrence_id, actor }
  | AddNote     { occurrence_id, note, actor }
  | LogAsNeeded { medication_id, taken_at: Instant, note?, actor }
type ActorRef = { account_id, platform, platform_identity_id, interaction_id /* -> idempotency_key */ }
type CommandResult = { occurrence: Occurrence, applied: bool /*false = semantic no-op*/, message: RenderModel }

// ---------- Reminder loop ----------
interface OccurrenceRepo {
  claimDue(now: Instant, limit: int): [Occurrence]          // FOR UPDATE SKIP LOCKED
  materialize(candidates: [Candidate], rev, now): int        // INSERT ... ON CONFLICT DO NOTHING
  cancelPending(schedule_id, revision, from: Instant, reason): int
}
fun tick(now: Instant, repo, dispatcher, policyOf: (Occurrence)->ReminderPolicy, quietOf: (account)->QuietHours)
  // for each claimed row: decide(row, now) -> {new_status, next_action_at, action_row, dispatch?}; commit per row

// ---------- Dispatch (vendor-neutral) ----------
type SendKey = "occ:{occurrence_id}:s{seq}:k{kind}:c{channel_id}" | "digest:{account_id}:{bucket}"
type Dispatch = { id, send_key: SendKey /*UNIQUE*/, occurrence_id?, account_id, channel_id,
                  kind: reminder|snooze|missed|digest|update, payload: RenderModel,
                  status: queued|sending|sent|failed_retry|failed_permanent|cancelled,
                  attempts: int, next_attempt_at?, attempt_id?, platform_message_id?, last_error? }
type RenderModel = { text_plain: string, discreet: bool, actions: [{id, label, style}],
                     ref: {occurrence_id, dispatch_id}, edit_of?: platform_message_id }
interface Dispatcher {                                    // implemented by Track A adapters
  send(channel: DeliveryChannel, model: RenderModel): SendOutcome
  edit(channel, platform_message_id, model): SendOutcome
}
type SendOutcome = Sent{platform_message_id} | Transient{retry_after?} | Permanent{reason}
type DeliveryChannel = { id, account_id, platform_identity_id, role: primary|fallback|off,
                         priority: int, state: healthy|degraded|dead }

// ---------- Stats ----------
type AdherenceSummary = { window: {from: LocalDate, to: LocalDate}, taken_on_time, taken_late, skipped,
  missed, unknown, adherence?: float, on_time_rate?: float, median_delay_min?, p90_delay_min?,
  streak_days: int, per_medication: [{medication_id, ...same counts}], as_needed_logs: int }
fun adherence(account_id, from: LocalDate, to: LocalDate, medication_id?): AdherenceSummary