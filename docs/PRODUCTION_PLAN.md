# Dosecord Production Implementation Plan

> See [SWARM_RESEARCH_SUMMARY.md](SWARM_RESEARCH_SUMMARY.md) for the consolidated research decisions, source links, and staged implementation roadmap.

## Goals

Dosecord starts as a Discord DM bot, but the backend must be platform-neutral from the first iteration. Discord, Telegram, web, and future API clients should all speak the same Kafka contract and link to the same canonical Dosecord account in PostgreSQL.

The platform bots are adapters. They receive messages, normalize them into commands, publish to Kafka, and render backend responses. The backend owns accounts, authentication, schedules, reminders, habits, mood tracking, statistics, privacy, and persistence.

## Architecture

```text
Discord DM / Telegram / API
        |
        v
Platform adapter service
        |
        | platform-neutral Kafka commands
        v
Backend worker/API
        |
        +--> PostgreSQL source of truth
        +--> Kafka domain events
        +--> chat response commands
```

Recommended service boundaries:

```text
shared/contracts/
  envelope.py
  identity.py
  wellbeing.py
  reminders.py
  chat.py

services/discord-bot/src/
  adapters/discord/
  application/
  kafka/

services/backend/src/
  api/
  application/
  domain/
  infrastructure/db/
  infrastructure/kafka/
  workers/
```

## Kafka Contract

Use a CloudEvents-inspired envelope with typed payloads. This keeps event metadata stable while allowing domain payloads to evolve independently.

Required envelope fields:

```text
id                  unique message/event id
type                namespaced event type, e.g. dosecord.identity.signup_requested.v1
specversion         CloudEvents spec version, "1.0"
source              producing service, e.g. dosecord.discord-bot
subject             optional account/platform/entity subject
time                UTC occurrence time
datacontenttype     application/json
dataschema          schema identifier
schema_version      event-specific version
correlation_id      one user interaction across services
causation_id        parent message id, if any
traceparent         OpenTelemetry trace propagation
actor               platform/canonical account reference
data/payload        domain-specific content
```

Actor shape:

```json
{
  "platform": "discord",
  "platform_user_id": "123456789",
  "platform_username": "oleksandr",
  "account_id": null
}
```

Partition key:

```text
account:{account_id}
platform:{platform}:{platform_user_id}
```

Before signup/linking, partition by platform identity. After backend resolves the canonical account, partition domain events by account id.

## Topic Strategy

MVP:

```text
dosecord.commands
dosecord.events
dosecord.chat.responses
dosecord.dlq
```

Production split:

```text
dosecord.commands.identity
dosecord.commands.wellbeing
dosecord.commands.reminders
dosecord.events.identity
dosecord.events.wellbeing
dosecord.events.reminders
dosecord.chat.responses
dosecord.dlq
```

Commands represent user intent. Events represent accepted facts.

Example command types:

```text
dosecord.identity.signup_requested.v1
dosecord.identity.login_requested.v1
dosecord.identity.restore_requested.v1
dosecord.identity.platform_link_requested.v1
dosecord.medicine.schedule_create_requested.v1
dosecord.medicine.intake_mark_taken_requested.v1
dosecord.reminder.create_requested.v1
dosecord.habit.create_requested.v1
dosecord.habit.checkin_record_requested.v1
dosecord.mood.checkin_record_requested.v1
dosecord.stats.summary_requested.v1
```

Example event types:

```text
dosecord.identity.account_created.v1
dosecord.identity.platform_linked.v1
dosecord.medicine.schedule_created.v1
dosecord.medicine.intake_recorded.v1
dosecord.reminder.created.v1
dosecord.reminder.due.v1
dosecord.habit.created.v1
dosecord.habit.checkin_recorded.v1
dosecord.mood.checkin_recorded.v1
dosecord.achievement.unlocked.v1
```

## Reliability

Backend consumers should use manual offset commits only after successful processing:

1. Decode and validate envelope.
2. Check `processed_messages` by idempotency key.
3. Run application handler in a DB transaction.
4. Save domain state and outbox messages.
5. Commit DB transaction.
6. Commit Kafka offset.
7. Outbox publisher emits responses/events.

Every command needs an idempotency key:

```text
discord:message:{discord_message_id}
telegram:update:{telegram_update_id}
api:request:{request_id}
```

Poison messages go to `dosecord.dlq` with validation error, original topic, partition, offset, and raw payload.

## Identity And Account Linking

Canonical accounts live in PostgreSQL. Platform identities attach to those accounts.

Initial tables:

```text
users
user_credentials
platform_identities
conversation_sessions
auth_challenges
recovery_codes
auth_audit_log
processed_messages
outbox_messages
```

All wellbeing tables reference `users.id`, never Discord or Telegram ids.

Recommended auth flow:

```text
/start
  -> Create new account
  -> Link existing account
  -> Restore access
```

Production-preferred credential flow:

1. User starts in Discord DM.
2. Backend creates a short-lived account setup/link challenge.
3. Bot sends HTTPS setup link or one-time code.
4. User creates username/password or logs in through backend API/web page.
5. Backend links Discord identity and emits a response.

If MVP collects passwords in DM, never log message content, delete password messages when possible, rate-limit attempts, and migrate to web auth before real users.

Password storage:

```text
Argon2id
unique salt generated by library
encoded hash only
rehash on login when parameters change
minimum length and breached-password screening
no brittle composition rules
```

Recovery:

```text
single-use recovery codes
magic links to verified email later
short-lived auth challenges
generic responses for unknown accounts
audit logs for login, link, restore, unlink
```

## Medicine Functionality

See [MEDICATION_REMINDER_UX.md](MEDICATION_REMINDER_UX.md) for the detailed chatbot flow, schedule model, reminder worker behavior, and intake state machine.

MVP:

```text
create medication profile: name, dose, instructions
schedule fixed daily times
timezone-aware reminders
mark taken, snooze, skip, missed
store scheduled_at and actual_taken_at separately
weekly adherence summary
```

Next:

```text
weekly schedules
interval schedules
as-needed medication
refill tracking
missed-dose reason notes
caregiver/sharing with explicit consent
doctor-ready export
```

Do not provide medical advice. For uncertainty, tell users to follow clinician instructions or contact a clinician/pharmacist.

## Habit Tracker Functionality

Habit types:

```text
boolean: done/not done
count: glasses of water, pages read
duration: minutes meditated/read/exercised
measurement: mood, sleep hours, weight
```

Cadence:

```text
daily
selected weekdays
N times per week
N times per month
every N days
pause/archive
```

Check-ins:

```text
done
partial
planned skip
missed
note
```

Stats:

```text
current streak
best streak
completion rate
weekly consistency
7/30/90 day trends
correlation hints with mood/medicine later
```

Design principle: avoid shame-heavy language. Reward recovery after missed days, not only perfect streaks.

## Reminder Worker

Use PostgreSQL as the source of schedule truth. A backend worker queries due reminders, emits `dosecord.reminder.due.v1`, and records delivery attempts.

Scheduling rules:

```text
store timezone per user
store all due instants in UTC
calculate next_due_at transactionally
support quiet hours
support snooze_until
support retries/escalation later
```

## Observability

Every log line and metric should include:

```text
correlation_id
message_id
event_type
platform
platform_user_id or account_id
handler
```

Add OpenTelemetry tracing and propagate `traceparent` through Kafka headers/envelope. Track:

```text
kafka publish latency
kafka consume lag
command success/failure totals
DLQ totals
DB transaction latency
reminders due/sent/missed
auth failures and rate-limit hits
```

## Testing

```text
shared/contracts:
  serialization, schema fixtures, backwards compatibility

discord-bot:
  parser tests, DM-only behavior, command construction

backend/domain:
  account linking, schedule calculation, streak logic, reminder transitions

backend/infrastructure:
  SQLAlchemy repositories with test Postgres

integration:
  Kafka + Postgres through testcontainers
```

## Implementation Order

1. Move event contracts from the Discord service into `shared/contracts`.
2. Add identity command/response models and backend event router.
3. Add PostgreSQL schema with Alembic.
4. Implement `/start`, signup/link/restore conversation state.
5. Add idempotent backend consumer and outbox publisher.
6. Implement medicine schedules and reminder worker.
7. Implement habit creation/check-ins/stats.
8. Implement mood check-ins and weekly summaries.
9. Add Telegram adapter using the same contracts.
10. Add sharing, export/delete, achievements, and advanced statistics.
