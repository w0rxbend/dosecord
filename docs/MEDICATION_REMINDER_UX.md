# Medication Scheduling And Reminder UX

This document defines the product target, not shipped status. ROADMAP Appendix B owns delivery/status; DESIGN and ADR-012 own architecture and precise lifecycle semantics. BOT_UX_PLAN and its machine-readable graph specify target cards/routes under ROADMAP’s UX adoption mapping. No graph edge proves an implemented handler.

## Product Principles

Medication tracking must be fast at the moment of reminder and careful during setup.

Core principles:

- The bot is a conversational UI, not the source of truth.
- The backend owns schedules, due dose generation, state transitions, adherence statistics, and audit history.
- Every scheduled dose has its own lifecycle and timestamps.
- The user can do all configuration from the bot through nested menus.
- The bot must never give clinical dosing advice. If a user is unsure what to do after a missed/late dose, it should advise following clinician/pharmacist instructions.
- Medication names and notes are sensitive. Keep reminders DM-only and support a discreet reminder mode later.

## Core Concepts

Medication profile:

```text
id
account_id
name
dose_amount
dose_unit
instructions
timezone
status: active | paused | archived
created_at
updated_at
```

Schedule:

```text
id
medication_id
schedule_kind:
  fixed_times
  interval_after_first_dose
  interval_after_each_dose
  weekly
  cycle
  as_needed
start_date
end_date
timezone
quiet_hours
status
```

Scheduled dose occurrence (conceptual fields; Scala/Flyway define exact storage):

```text
id
schedule_id
medication_id
account_id
scheduled_for
due_window_start
due_window_end
status:
  pending
  due
  snoozed
  taken
  skipped
  missed
  unknown
  cancelled
last_reminded_at
reminder_count
snoozed_until
taken_at
skipped_at
missed_at
created_at
updated_at
```

Dose action log:

```text
id
dose_occurrence_id
action:
  reminder_sent
  taken
  skipped
  snoozed
  auto_marked_missed
  manually_corrected
actor_type: user | system
platform
platform_message_id
occurred_at
note
metadata
```

Keep `scheduled_for` and `taken_at` separate. This is the difference between adherence and history: a dose can be scheduled for 09:00 and taken at 09:17.

## Supported Schedule Types

MVP:

- Daily fixed times: “every day at 9:00”.
- Multiple daily fixed times: “9:00 and 21:00”.
- Selected weekdays: “Mon/Wed/Fri at 10:00”.
- As-needed medication: no automatic reminder, manual logging only.

Strong next iteration:

- Every N hours from a fixed start time.
- Every N hours after first dose of the day.
- Every N days/weeks.
- Cycle schedules: “21 days on, 7 days off”.
- Different weekend times.
- Taper schedules: changing dose over date ranges.
- Refill tracking and low-stock reminders.

The schedule parser should accept natural language eventually, but the reliable MVP should use guided menus with explicit confirmation before creating anything.

## Dose Lifecycle

```text
pending
  -> due                 when worker reaches scheduled_for
  -> cancelled           when schedule is paused/deleted before due

due
  -> taken               user taps Taken
  -> skipped             user taps Skip
  -> snoozed             user taps Snooze
  -> missed              miss_deadline passes with sent evidence
  -> unknown             miss_deadline passes without delivery evidence

snoozed
  -> due                 snoozed_until arrives
  -> taken
  -> skipped
  -> missed

taken/skipped/missed/unknown/cancelled end automatic reminders. Undo and explicit
correction follow ADR-012; terminal does not mean immutable history.
```

Required timestamps:

```text
scheduled_for
last_reminded_at
snoozed_until
taken_at
skipped_at
missed_at
```

Adherence categories:

```text
taken_on_time       effective_at <= due_window_end for eligible resolved rows
taken_late          effective_at > due_window_end within allowed late logging
skipped             user intentionally skipped
missed              no response by cutoff
unknown             data gap, migration, or system outage
```

## Background Worker

Use one direct-style Ox reminder loop over next_action_at, revision-aware
materialization and a vendor-owned transactional outbox (DESIGN section 7).
The claim/decide/action/outbox transaction holds no vendor call; sent evidence
is recorded after delivery. No broker or independent missed-dose process.
Missed requires sent evidence; outage/undelivered yields unknown, excluded
from adherence. Quiet defer shifts the window and never creates unknown.
Startup, roles, wake/sweeper and shutdown require M2.2b launch-path evidence.
UTC storage, per-schedule IANA zone recurrence/display and DST rules remain.

Taken is direct only inside the persisted late-log window; too-old/skipped
records open Correct without mutation, cancelled records refuse and an
already-recorded tap returns its outcome. A three-day-old reminder opens
Now / At scheduled time correction; custom time/notes remain M3.1-gated.
Undo within 15 minutes restores due, extends deadlines and clears action
fields per ADR-012. Snooze is bounded by next occurrence/maxLate/chain and
filters invalid options; explicit snooze bypasses quiet defer but is silent
inside quiet hours. max_reminders includes the first reminder. Corrections
use explicit effective_at and are excluded from delay samples.

## Nested Bot Menu

Root keeps six categories: Today, Medications, Habits, Reminders, Stats,
Account. Hide unimplemented branches; Mood is a Today quick action plus
`/mood`, not a seventh category. Linked `/start` routes to root.

```text
Today -> Open page -> Dose detail -> Taken | Skip | valid Snooze -> Outcome
      -> Completed | Log dose -> Picker -> Outcome -> Log another
      -> Log mood -> 1–10 picker -> optional note/tags -> Outcome
Medications -> Picker -> Detail -> Log | History | Reminder settings
                             -> Pause/Resume | Archive confirmation | Edit*
Add -> Text form -> Cadence/days -> Review -> Create -> Saved
Stats* -> 7/30 days | By medication | Timing | Text view | Infographic**
Account -> Timezone | Notifications* | Privacy* | Export/Delete*
Every nested view -> Back (saved parent/filter) | Menu
```

`*` means official roadmap gate; `**` requires UX-15 / M3.4d qualification,
not just a rendered asset. Existing seven-day history stays available before
M3.2; expanded paging/filter/correction is separately gated. Open dose pages
remain actionable; do not hide overflow only in read-only history. Use a
medication-scoped detail before Pause/Archive rather than repeated bare
buttons. Discord reserves one navigation row and fits actual rendered rows
(select consumes a row), initially at most four actionable rows plus nav.

Routine fixed prompts fit two short paragraphs/160 characters; outcomes fit
80 characters. User data has validated limits and Details, never silent
truncation. Navigation edits one card, setup one card, reminders keep their
own occurrence-scoped cards. Read-only views create no dose actions.

Add uses the accepted three-step wizard: text form (name, optional dose,
HH:MM times, instructions; no times means as-needed), cadence/day selection
when scheduled, then exact review/Create. Custom days/zone are explicit
branches. Unsupported kinds stay hidden. As-needed creates no automatic
reminder. Review must equal persisted fields; until full edit exists,
Update schedule explicitly retains existing dose/instructions/zone.
Schedule Reminder settings copies the current policy; edit timing offset,
snooze options and miss window, review then version-checked Save preserving
other fields. Invalid input preserves answers and writes nothing.

```text
Time for Vitamin D, 1000 IU.
With breakfast.                 only user-provided instructions
[Taken] [Snooze 10m] [Skip]
[Snooze 30m] [Snooze 1h]         only valid options

Recorded at 09:03.
[Undo] [Correct]                Add note only from M3.1
```

Taken/Skip/each offered Snooze/Undo take one tap on the visible reminder;
Correct Now/At scheduled time takes two. Log as-needed from Today or picker
takes at most two. One displayed manual-log intent commits at most once even
on distinct rapid taps; Log another explicitly allocates a fresh intent.

Routine navigation/setup/outcomes/history/requested reports are silent.
Due/repeat/snooze follow persisted policy, not a UX heuristic. Catch-up sends
one digest per accepted bucket linking original records, never stale push
storms. Summaries are silent and opt-out-aware; ops alerts are separate,
bounded and never interrupt reminder/outcome exchanges. DM-only redirect
outside BOT_DM reveals no health data. Discreet covers names in labels,
previews, text, filenames and alt text; spoilers are not encryption.

## Bot State Machine

Persistent wizard/form sessions use signed opaque callbacks, owner/session/
step/expiry validation and the existing 30-minute idle plus 30-minute grace.
Menu suspends, root offers Continue setup at the exact saved step; switching
flows asks Discard draft / Continue setup. Only explicit Cancel/discard or
expiry deletes the draft. Restart/stale-step recovery retains answers and
source context, emits one notice and re-renders the current prompt. A stale
submit cannot recreate an expired/discarded draft. Dose actions do not change
the setup draft. Back retains filters/context; invalid foreign controls reveal
no names and mutate nothing.

Ack targets p95 <1.5 s from interaction creation, before DB/render/upload;
modal opening cannot defer, modal submit acknowledges independently. Missed
modal deadline persists FormRunner and counts degradation. Deferred replies
must actually complete and respect source/declared visibility.

Saved means the core confirmed persistence, not merely that the interaction
was acknowledged. Failed reply send is not failed commit. On uncertain
commit show Checking whether this was recorded… and reconcile durable
idempotency/readback before replay or same-intent retry. Say Nothing was
recorded only after confirmed noncommit. Missing edit target produces one
useful replacement with a recorded handle, not duplicate success toasts.

Account ready offers Add medication / Open Today; Medication saved offers
Open medication / Today. Archive/Delete confirmations name their targets.
These user data-safety confirmations survive autonomous development.

The retired event/topic payload sketches and worker stack are not executable
contracts. Current Scala contracts, Flyway schema and DESIGN are authoritative;
Python product services and the retired broker stack must not return.

## Adherence Statistics

M3.4 ships text first: today/7/30-day and per-medication resolved counts,
optional adherence/on-time rates and median/p90 delay. One immutable snapshot
holds account/range/zone/as-of/source revision and all represented values.
Text, bounded redacted alt text and eventual PNG share that snapshot; query
results must equal the existing pure Adherence oracle.

Adherence = taken / (taken + skipped + missed) over eligible resolved rows.
Unknown/cancelled/manual/open rows are excluded; show counts/coverage, never
convert unknown to missed. Denominator zero is No data, not 0%. Corrected
records count appropriately but are excluded from delay; effective-time/date
attribution follows the domain. Mood gaps are gaps, not zeros. Skipped is
separate, not failure. No composite health score, dose advice, causality or
shaming streaks; any streak uses recovery copy.

Images stay hidden until M3.4d: durable assets, qualified transport,
final-container/fonts/resource tests and independent phone-width dark/light
visual review. Empty/all-unknown/single-point/Unicode/discreet cases need
fixtures. Render/queue/upload failure retains useful truthful text and never
blocks intake or reminders. Text summaries/breadth do not depend on rendering
go. See DESIGN section 15 for accepted asset/privacy/retention boundaries.

## Safety Copy

Use neutral text:

```text
Recorded.
Marked skipped.
I marked this missed because there was no response.
If you are unsure whether to take a late or missed dose, follow your clinician/pharmacist instructions.
```

Avoid:

```text
You failed.
You should take it now.
Double your next dose.
```

## Implementation Path

Follow ROADMAP's UX adoption allocation and Appendix B, one official slice
per issue/branch. M2.2 then M2.2a-h before M2.3; M3.4 text before M3.4a-d media.
Existing criteria, live/device evidence and in-use milestone gates remain;
this specification does not promote any product status.
