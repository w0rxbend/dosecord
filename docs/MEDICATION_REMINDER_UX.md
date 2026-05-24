# Medication Scheduling And Reminder UX

This document defines the production target for creating medication schedules from the bot, triggering background reminders, and tracking whether each scheduled dose was taken, snoozed, skipped, or missed.

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

Scheduled dose occurrence:

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
  -> missed              due_window_end passes

snoozed
  -> due                 snoozed_until arrives
  -> taken
  -> skipped
  -> missed

taken/skipped/missed/cancelled are terminal for the occurrence.
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
taken_on_time       taken_at <= due_window_end
taken_late          taken_at > due_window_end, if manual correction allowed
skipped             user intentionally skipped
missed              no response by cutoff
unknown             data gap, migration, or system outage
```

## Background Worker

The reminder worker runs in the backend service or a separate backend worker process.

Worker loop:

1. Select due occurrences with `scheduled_for <= now` or `snoozed_until <= now`.
2. Lock rows with `FOR UPDATE SKIP LOCKED`.
3. Mark occurrence as `due`.
4. Insert `dose_action_log(action='reminder_sent')`.
5. Emit `dosecord.chat.response_send_requested.v1` to Kafka.
6. Update `last_reminded_at` and `reminder_count`.
7. Schedule next occurrence where needed.

Missed-dose worker:

1. Select `due` or `snoozed` occurrences whose response window expired.
2. Mark `missed`.
3. Insert `dose_action_log(action='auto_marked_missed')`.
4. Optionally emit a gentle missed-dose message.

Use UTC internally. Convert to the user’s timezone only for display and recurrence calculations.

## Nested Bot Menu

Top-level menu:

```text
Dosecord
[Today] [Medications] [Habits]
[Reminders] [Stats] [Account]
```

Medication menu:

```text
Medications
[Today's doses] [Add medication]
[Edit medication] [Pause medication]
[History] [Settings]
```

Add medication flow:

```text
Bot: What medicine or supplement should I track?
User: Vitamin D

Bot: What dose should I show in reminders?
[Skip] or text: 1000 IU

Bot: When do you take it?
[Daily] [Specific days] [Every N hours] [As needed]

Bot: Choose time.
[09:00] [12:00] [18:00] [Custom]

Bot: Timezone is Europe/Kyiv. Use this?
[Yes] [Change]

Bot: Reminder options?
[At dose time] [5 min before] [10 min after if unanswered]

Bot: Confirm:
Vitamin D, 1000 IU
Every day at 09:00 Europe/Kyiv
Actions: Taken, Snooze, Skip
[Create] [Edit] [Cancel]
```

Daily schedule with multiple times:

```text
Bot: Add another time?
[Add time] [Done]
```

Specific days:

```text
Bot: Which days?
[Mon] [Tue] [Wed] [Thu] [Fri] [Sat] [Sun]
[Done]
```

Every N hours:

```text
Bot: How should the interval start?
[Fixed first time] [After first taken dose]

Bot: Interval?
[4h] [6h] [8h] [Custom]

Bot: How many doses per day?
[2] [3] [4] [No daily limit]
```

As needed:

```text
Bot: I will not remind you automatically. You can log it from Today's doses or by saying "I took <name>".
[Create] [Cancel]
```

Reminder message:

```text
Time for Vitamin D, 1000 IU.
[Taken] [Snooze 10m] [Skip]
```

Taken flow:

```text
User taps Taken
Bot: Recorded at 09:03.
[Undo] [Add note]
```

Skip flow:

```text
User taps Skip
Bot: Marked skipped. Want to add a reason?
[Forgot] [Ran out] [Side effects] [Doctor told me] [No note]
```

Snooze flow:

```text
User taps Snooze 10m
Bot: Okay, I’ll remind you again at 09:10.
```

Missed flow:

```text
Bot: I did not get a response for Vitamin D at 09:00, so I marked it missed.
[I took it] [Skip] [Keep missed]
```

Manual correction:

```text
Bot: When did you take it?
[Now] [At scheduled time] [Custom time]
```

## Bot State Machine

The backend stores conversation state so bot restarts and horizontal scaling are safe.

```text
medication.add.name
medication.add.dose
medication.add.schedule_kind
medication.add.days
medication.add.times
medication.add.interval
medication.add.timezone
medication.add.reminder_policy
medication.add.confirm
```

State row:

```text
platform
platform_user_id
conversation_id
state
data jsonb
expires_at
updated_at
```

All button/menu actions should include a compact callback payload:

```text
action=med_add_time
conversation_id=...
value=09:00
```

## Kafka Messages

Commands from bot to backend:

```text
dosecord.medication.create_requested.v1
dosecord.medication.schedule_create_requested.v1
dosecord.medication.schedule_update_requested.v1
dosecord.medication.pause_requested.v1
dosecord.medication.archive_requested.v1
dosecord.medication.intake_mark_taken_requested.v1
dosecord.medication.intake_mark_skipped_requested.v1
dosecord.medication.intake_snooze_requested.v1
dosecord.medication.intake_correct_requested.v1
dosecord.medication.today_requested.v1
dosecord.medication.history_requested.v1
```

Events from backend:

```text
dosecord.medication.created.v1
dosecord.medication.schedule_created.v1
dosecord.medication.schedule_updated.v1
dosecord.medication.dose_due.v1
dosecord.medication.intake_taken.v1
dosecord.medication.intake_skipped.v1
dosecord.medication.intake_snoozed.v1
dosecord.medication.intake_missed.v1
dosecord.medication.intake_corrected.v1
```

Responses to platform adapters:

```text
dosecord.chat.response_send_requested.v1
dosecord.chat.menu_render_requested.v1
dosecord.chat.error_display_requested.v1
```

## Example Command Payload

```json
{
  "type": "dosecord.medication.schedule_create_requested.v1",
  "actor": {
    "platform": "discord",
    "platform_user_id": "123",
    "account_id": "5f7fb597-07de-4d5f-8b1d-0a180a24d401"
  },
  "data": {
    "medication": {
      "name": "Vitamin D",
      "dose_amount": "1000",
      "dose_unit": "IU",
      "instructions": "with breakfast"
    },
    "schedule": {
      "kind": "fixed_times",
      "timezone": "Europe/Kyiv",
      "days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
      "times": ["09:00"],
      "start_date": "2026-05-24",
      "end_date": null
    },
    "reminder_policy": {
      "initial_offset_minutes": 0,
      "snooze_options_minutes": [10, 30, 60],
      "miss_after_minutes": 120,
      "max_reminders": 3,
      "discreet": false
    }
  }
}
```

## Data Model Recommendation

PostgreSQL tables:

```text
medications
medication_schedules
medication_schedule_times
medication_occurrences
medication_intake_actions
reminder_policies
conversation_sessions
outbox_messages
processed_messages
```

Important constraints:

```text
unique(idempotency_key) on processed_messages
index(account_id, scheduled_for, status) on medication_occurrences
index(schedule_id, scheduled_for) on medication_occurrences
```

## Adherence Statistics

MVP stats:

```text
today: due, taken, skipped, missed
7-day adherence percentage
30-day adherence percentage
per-medication taken/skipped/missed counts
average delay from scheduled_for to taken_at
```

Adherence formula:

```text
taken / (taken + skipped + missed)
```

Also show skipped separately. Some skipped doses are medically intentional and should not be shamed.

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

1. Add shared medication command/event contracts.
2. Add backend DB models and Alembic migration for medication tables.
3. Add backend medication service for create schedule, generate occurrences, mark taken/skip/snooze/missed.
4. Add reminder worker with row locking and outbox messages.
5. Add Discord nested menu handlers for add medication and today's doses.
6. Add chat response consumer in Discord bot.
7. Add adherence summary command.
8. Add interval/cycle schedules and refill tracking after fixed schedules are stable.
