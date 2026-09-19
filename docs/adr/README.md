# Architecture Decision Records

All thirteen records are Accepted on 2026-09-11 and are the decisions behind docs/DESIGN.md; the delivery order that realises them is docs/ROADMAP.md. A changed decision is recorded as an addendum section in the affected ADR (never by editing the original text); every ROADMAP spike that changes a decision names the ADR it amends.

| ADR | Title | Status |
|---|---|---|
| [ADR-001](ADR-001.md) | Scala 3 direct-style modular monolith on JDK 25 is the implementation language and process model (supersedes the Python decision of revision 1) | Accepted 2026-09-11 |
| [ADR-002](ADR-002.md) | PostgreSQL 18 via pgjdbc + HikariCP with hand-written SQL and Flyway migrations | Accepted 2026-09-11 |
| [ADR-003](ADR-003.md) | Kafka is removed; Postgres is the messaging backbone (transactional outbox, inbound dedupe, domain_events, LISTEN/NOTIFY) | Accepted 2026-09-11 |
| [ADR-004](ADR-004.md) | Scheduler = materialised dose_occurrences with one next_action_at column, epoch fencing, schedule revisions and a cross-revision live-slot index; no scheduler library | Accepted 2026-09-11 |
| [ADR-005](ADR-005.md) | Vendor adapters are decoupled from the core by a ChatMediator with a capability-driven renderer in the core | Accepted 2026-09-11 |
| [ADR-006](ADR-006.md) | Interaction callbacks are 34-byte HMAC-signed tokens (46 chars base64url, 'dc:' prefixed on the wire) carrying a full UUID subject and a typed action registry | Accepted 2026-09-11 |
| [ADR-007](ADR-007.md) | Identity is resolved by the core from platform_identities; production auth uses short-lived HTTPS link challenges with Argon2id, never chat passwords | Accepted 2026-09-11 |
| [ADR-008](ADR-008.md) | Migration is an in-repo greenfield rewrite delivered in thin slices with atomic deletion of the Python services and Kafka; no code or data migration | Accepted 2026-09-11 |
| [ADR-009](ADR-009.md) | Delivery is at-least-once through a vendor-owned outbox dispatcher with typed ops, vendor calls outside transactions, vendor-side idempotency where it exists and a bounded, flagged duplicate everywhere else | Accepted 2026-09-11 |
| [ADR-010](ADR-010.md) | Matrix DMs are end-to-end encrypted from the first Matrix slice using Trixnity 5.x with the vodozemac crypto driver behind a Kotlin shim, with a time-boxed spike and an out-of-process escape hatch | Accepted 2026-09-11 |
| [ADR-011](ADR-011.md) | Time is per-schedule IANA zone with explicit gap/fold policies via java.time, injected clocks, and the JDK tzdb tracked as a metric | Accepted 2026-09-11 |
| [ADR-012](ADR-012.md) | Dose lifecycle semantics — quiet hours, snooze, undo, late logging, chains, unknown vs missed | Accepted 2026-09-11 |
| [ADR-013](ADR-013.md) | Interaction acknowledgement policy — ack before work, declared visibility, no defer for form-opening actions, Telegram's answer is single-shot | Accepted 2026-09-11 |

Amendments scheduled by the roadmap: ADR-008 (deletion timing, ROADMAP M0.2); ADR-001/ADR-013 (Discord registration scope, after Spike C, ROADMAP M0.8); ADR-006 (token layout, only if Spike A returns no-go, ROADMAP M0.6); ADR-002 (erasure-gated DELETE on append-only tables, ROADMAP M4.4); ADR-010 (Matrix verdict, ROADMAP M6.0).
