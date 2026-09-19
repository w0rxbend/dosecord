# Swarm Research Summary

This is the consolidated result of the architecture, Kafka-contract, identity, and chatbot-UX swarm.

## Final Direction

Dosecord should be built as a platform-neutral backend with thin chat adapters.

```text
Discord / future Telegram / future API
        |
        v
platform adapter
        |
        | Kafka command
        v
backend command worker
        |
        +--> PostgreSQL source of truth
        +--> outbox messages
        |
        v
outbox publisher
        |
        +--> Kafka domain events
        +--> Kafka chat responses
```

Discord is the first frontend, but it must not leak into the domain model. The backend must use a canonical `users.id`; Discord and Telegram identities are links to that account.

## Key Decisions

1. **Kafka messages use one flexible envelope.**
   Use a CloudEvents-style envelope with `id`, `type`, `source`, `subject`, `time`, `correlation_id`, `causation_id`, `actor`, and typed `data`.

2. **Adapters publish commands, not final domain events.**
   Discord should emit `dosecord.medication.schedule_create_requested.v1`, not `medicine.schedule_created`.

3. **Backend owns facts and replies.**
   Backend persists state, emits facts such as `dosecord.medication.schedule_created.v1`, and emits chat responses such as `dosecord.chat.response_send_requested.v1`.

4. **PostgreSQL is the source of truth.**
   Kafka transports commands/events. It does not replace users, schedules, reminders, histories, or auth state.

5. **Use idempotency and outbox from the first real backend slice.**
   Store `processed_messages` and `outbox_messages` before adding complex feature logic.

6. **Do not collect passwords in Discord/Telegram DMs for production.**
   Preferred flow: bot sends a short-lived HTTPS challenge link. User creates/logs into account through backend web/API flow. DM password entry is an MVP-only fallback.

7. **Medication UX must be conservative.**
   Track and remind, but never advise dose changes. For missed doses, tell users to follow the label or contact a clinician/pharmacist.

## Kafka Contract

Envelope:

```json
{
  "specversion": "1.0",
  "id": "uuid",
  "type": "dosecord.medication.schedule_create_requested.v1",
  "source": "dosecord.discord-bot",
  "subject": "platform:discord:123456789",
  "time": "2026-05-24T12:00:00Z",
  "datacontenttype": "application/json",
  "dataschema": "dosecord://schemas/medication/schedule-create-requested/v1",
  "schema_version": 1,
  "correlation_id": "uuid",
  "causation_id": null,
  "reply_to": "dosecord.chat.responses",
  "traceparent": null,
  "idempotency_key": "discord:message:1122334455",
  "actor": {
    "platform": "discord",
    "platform_user_id": "123456789",
    "platform_username": "user",
    "account_id": null
  },
  "data": {}
}
```

Partition key:

```text
platform:{platform}:{platform_user_id}
account:{account_id}
```

Start with topics:

```text
dosecord.commands
dosecord.events
dosecord.chat.responses
dosecord.dlq
```

Split by domain later:

```text
dosecord.commands.identity
dosecord.commands.medication
dosecord.commands.wellbeing
dosecord.commands.reminders
dosecord.events.identity
dosecord.events.medication
dosecord.events.wellbeing
dosecord.events.reminders
```

Python layout:

```text
shared/contracts/
  envelope.py
  actors.py
  registry.py
  identity.py
  medication.py
  wellbeing.py
  chat.py
```

Payload fields should live inside `data`, not as top-level envelope fields.

## Identity And Account Linking

Core tables:

```text
users
user_credentials
platform_identities
auth_challenges
conversation_sessions
recovery_codes
auth_audit_log
processed_messages
outbox_messages
```

Recommended `/start` flow:

```text
Welcome to Dosecord
[Create account] [Link existing] [Restore access]
```

Production account setup:

```text
1. User sends /start in Discord DM.
2. Backend creates auth_challenge.
3. Bot sends short-lived HTTPS setup/link URL.
4. User creates account or logs in.
5. Backend links Discord identity to users.id.
6. Bot confirms setup.
```

Later Telegram linking uses the same flow and inserts another `platform_identities` row for the same `users.id`.

Security baseline:

```text
Argon2id password hashes
single-use recovery codes
short-lived auth challenges
rate limits by platform identity/user/IP
generic restore/login responses
auth audit logs
no passwords or raw tokens in logs
```

## Medication, Reminder, And Habit UX

Top-level menu:

```text
Main Menu
[Today] [Medications] [Habits]
[Reminders] [Insights] [Settings]
```

Medication reminder:

```text
Time for Metformin 500 mg. With food.
[Taken] [Snooze] [Skip] [Details]
```

No response:

```text
Still need to log Metformin?
[Taken] [Snooze] [Skip]
```

Expired window:

```text
I marked this dose as missed because it was not logged.
[Log late] [Mark skipped] [Leave missed]
```

Habit setup:

```text
What habit do you want to track?
What is the goal? [Check-off] [Count] [Duration] [Amount] [Avoid]
How often? [Daily] [Weekdays] [3x/week] [Custom]
Streak policy? [Flexible] [Strict] [No streaks]
```

Default habit behavior should be forgiving: support rest days, planned skips, partial progress, and recovery-focused achievements.

## Backend Workers

Separate entrypoints:

```text
command-worker
outbox-publisher
reminder-worker
summary-worker
api
```

Reminder worker:

```text
1. Select due occurrences.
2. Lock rows with FOR UPDATE SKIP LOCKED.
3. Mark due/reminded.
4. Insert action log.
5. Write chat response to outbox.
6. Commit transaction.
7. Outbox publisher emits Kafka response.
```

## Implementation Roadmap

1. Move contracts from `services/discord-bot/src/models.py` to `shared/contracts`.
2. Convert payload fields to typed `data` models.
3. Add backend DB layer: SQLAlchemy async, Alembic, users/platform identities, processed messages, outbox.
4. Change backend consumer to manual offset commits.
5. Convert Discord bot to publish `*_requested.v1` commands.
6. Implement one vertical slice: mood command -> Postgres -> outbox -> chat response.
7. Implement account setup/link/restore flow.
8. Implement medication profiles, schedules, occurrence lifecycle, reminder worker.
9. Implement generic reminders and habit tracker.
10. Add stats, achievements, privacy export/delete, and Telegram adapter.

## Sources

- CloudEvents spec for flexible event envelope concepts: https://github.com/cloudevents/spec
- OWASP Password Storage Cheat Sheet for Argon2id guidance: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- CDC medication safety guidance: https://www.cdc.gov/medication-safety/about/index.html
- Apple Health medication logging UX: https://support.apple.com/en-us/HT213351
- NIST Privacy Framework for data minimization and user control: https://www.nist.gov/privacy-framework
