# Architecture Decision Records

Records ADR-001 to ADR-013 were accepted on 2026-09-11 as the decisions behind the Dosecord design, now [docs/archive/DESIGN-dosecord.md](../archive/DESIGN-dosecord.md). [ADR-014](ADR-014.md) records the pivot to Dosegram; the architecture that realises the records is [docs/ARCHITECTURE.md](../ARCHITECTURE.md) (section 21) and the delivery order is [docs/ROADMAP.md](../ROADMAP.md) (section 1.7). A changed decision is recorded as an addendum section in the affected ADR (never by editing the original text) or by a new record that supersedes it; only the status line of a withdrawn or superseded record changes.

| ADR | Title | Status |
|---|---|---|
| [ADR-001](ADR-001.md) | Scala 3 direct-style modular monolith on JDK 25 is the implementation language and process model (supersedes the Python decision of revision 1) | Accepted 2026-09-11; addendum 2026-11-13 |
| [ADR-002](ADR-002.md) | PostgreSQL 18 via pgjdbc + HikariCP with hand-written SQL and Flyway migrations | Accepted 2026-09-11 |
| [ADR-003](ADR-003.md) | Kafka is removed; Postgres is the messaging backbone (transactional outbox, inbound dedupe, domain_events, LISTEN/NOTIFY) | Accepted 2026-09-11 |
| [ADR-004](ADR-004.md) | Scheduler = materialised dose_occurrences with one next_action_at column, epoch fencing, schedule revisions and a cross-revision live-slot index; no scheduler library | Accepted 2026-09-11 |
| [ADR-005](ADR-005.md) | Vendor adapters are decoupled from the core by a ChatMediator with a capability-driven renderer in the core | Accepted 2026-09-11; superseded in part by ADR-015 (2026-11-13) |
| [ADR-006](ADR-006.md) | Interaction callbacks are 34-byte HMAC-signed tokens (46 chars base64url, 'dc:' prefixed on the wire) carrying a full UUID subject and a typed action registry | Accepted 2026-09-11; addendum 2026-11-13 |
| [ADR-007](ADR-007.md) | Identity is resolved by the core from platform_identities; production auth uses short-lived HTTPS link challenges with Argon2id, never chat passwords | Accepted 2026-09-11; to be superseded by ADR-016 |
| [ADR-008](ADR-008.md) | Migration is an in-repo greenfield rewrite delivered in thin slices with atomic deletion of the Python services and Kafka; no code or data migration | Accepted 2026-09-11 |
| [ADR-009](ADR-009.md) | Delivery is at-least-once through a vendor-owned outbox dispatcher with typed ops, vendor calls outside transactions, vendor-side idempotency where it exists and a bounded, flagged duplicate everywhere else | Accepted 2026-09-11 |
| [ADR-010](ADR-010.md) | Matrix DMs are end-to-end encrypted from the first Matrix slice using Trixnity 5.x with the vodozemac crypto driver behind a Kotlin shim, with a time-boxed spike and an out-of-process escape hatch | Withdrawn by ADR-014 (2026-11-13) |
| [ADR-011](ADR-011.md) | Time is per-schedule IANA zone with explicit gap/fold policies via java.time, injected clocks, and the JDK tzdb tracked as a metric | Accepted 2026-09-11 |
| [ADR-012](ADR-012.md) | Dose lifecycle semantics — quiet hours, snooze, undo, late logging, chains, unknown vs missed | Accepted 2026-09-11 |
| [ADR-013](ADR-013.md) | Interaction acknowledgement policy — ack before work, declared visibility, no defer for form-opening actions, Telegram's answer is single-shot | Accepted 2026-09-11; to be superseded by ADR-017 |
| [ADR-014](ADR-014.md) | Dosegram pivot — Telegram is the only chat platform, the product is renamed, and Discord, Zulip and Matrix are retired in one step | Accepted 2026-11-13 |
| [ADR-015](ADR-015.md) | Chat boundary for a Telegram-native product — the removed vendor concepts, the product limits on controls and the frozen surface for attachments, link choices, deep-link payloads and received media | Accepted 2026-11-13 |

Records still to be written, each in the slice that first implements its decision (ROADMAP section 1.7): ADR-016 (G1.12), ADR-017 (G1.7), ADR-018 (G2.7), ADR-019 (G1.4), ADR-020 (G3.1), ADR-021 (G2.3), ADR-022 (G1.9), ADR-023 (G5.1), ADR-024 (G4.4), ADR-025 (G7.1), ADR-026 (G8.1), ADR-027 (G10.1), ADR-028 (G1.8), ADR-029 (deferred with billing). The amendments the Dosecord roadmap scheduled for ADR-001, ADR-002, ADR-006, ADR-008, ADR-010 and ADR-013 are closed by their addenda or voided by ADR-014.
