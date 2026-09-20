# Spike note: Outbox dispatcher protocol / idempotency (M0.10)

Slice: M0.10 — Outbox dispatcher protocol (risk-first, code kept). Deps: M0.3, M0.9 (green).
Date: 2026-09-21. Decision records read: ADR-003, ADR-009; docs/DESIGN.md sections 3, 7.6, 8, 9.

## Protocol as implemented

The dispatcher lives in `core/scheduling` (`dosecord.core.scheduling.OutboxDispatcher`) against ports only; the
Postgres claim/record implementation is in `infra/db` (`PgOutboxRepository`, `PgRenderedMessageRepository`); the
Testcontainers proof is in `infra/test`. `core` does not depend on `infra` (module graph enforced by the build).
The V1 schema needed no changes: `send_key UNIQUE`, `lease_until`, `attempted_at`, `ops_done`,
`possible_duplicate`, `attempts`, `next_attempt_at` and the `sending` status were all created by M0.3 exactly as
DESIGN.md section 8 specifies.

One dispatch cycle, per DESIGN.md section 7.6:

1. **tx1 (short claim).** `UPDATE ... FROM (SELECT ... WHERE vendor = ANY(:owned) AND next_attempt_at <= :now
   AND (status IN ('queued','failed_retry') OR (status='sending' AND lease_until < :now)) ORDER BY next_attempt_at
   LIMIT :limit FOR UPDATE SKIP LOCKED) ... RETURNING` — rows flip to `sending` with `lease_until = now + 60 s`,
   `attempted_at = now`, `attempts + 1`. The claim also returns the pre-claim `attempted_at` as
   `previousAttemptedAt`; that value, not the fresh one, feeds the duplicate rule, because the claim itself
   overwrites `attempted_at`.
2. **Vendor call with no lock held.** Send: `adapter.send(chat, rendered, sendKey)` against the real
   `ChatAdapter` trait; reactions are the sub-ops of the numbered (reaction-tier) controls.
3. **Two-phase recording.** Immediately after the primary send and before the first reaction, one transaction
   records the handle (`platform_message_id`, `ops_done` bit 0) and the `rendered_messages` row with its
   `choice_map`, so controls stay resolvable after a crash. Each reaction sub-op then commits its own
   `ops_done` bit; `markSent` is always the last write.
4. **Resume.** A re-claimed row with `ops_done` bit 0 set skips the send and reconstructs the handle from
   `platform_message_id`; completed reaction sub-ops are skipped, the rest are redone with stable per-op txn
   keys (`sendKey:r{i}`; duplicate-annotation errors are success by adapter contract).
5. **Failures.** `RateLimited` reschedules at the vendor's hint; `Retryable`/`TooOld` reschedule with
   exponential backoff (5 s doubling, 30 min cap — jitter and the retry-instant property land in M1.7), and at
   8 attempts the row goes `dead` instead; any other `ChatError` goes `failed_permanent` (channel-fatal
   fallback is wired with delivery channels in M1.7). A `send` retried more than 2 minutes after its previous
   `attempted_at` is sent once more and flagged `possible_duplicate` — the uniform rule of ADR-009, bounded to
   one extra send per crash on every vendor.
6. **Enqueue.** Producers insert with `ON CONFLICT (send_key) DO NOTHING`; the UNIQUE constraint is the
   exactly-one-row guarantee with two writers or a restart mid-loop. Epoch fencing (`occurrences.epochIsStale`)
   and `cancelOlderQueued` land in M1.6/M1.7 with the loop; the epoch column is already carried on the row.

Deliberately minimal, grown in M1.7 (not rewritten): backoff jitter, channel-fatal fallback, per-vendor/per-chat
token buckets, epoch fencing, and structured (JSON) `target`/payload decoding. The standalone `react` op encodes
its emoji as a plain JSON string in `payload` and always reacts `on = true`; reaction-removal fan-out is M1.7.

## Results (all automated, Testcontainers Postgres 18)

| # | Acceptance criterion | Test | Result |
|---|---|---|---|
| 1 | Exactly one row per `send_key` (unique constraint + insert path; concurrent inserts) | `exactly one row per send_key under concurrent inserts` — 8 threads race one key, plus a sequential duplicate: exactly one insert wins, one row exists | PASS |
| 2 | Crash after the fake send → at most one extra send flagged `possible_duplicate`, no extra state change | `crash after the vendor send yields at most one extra send flagged possible_duplicate and no extra state` — process killed between `adapter.send` and the record write; after lease expiry the row is re-claimed, sent exactly once more, `sent` with `possible_duplicate = true`, still one outbox row | PASS |
| 3 | Lease expiry re-claims (clock/advanced time) | `lease expiry re-claims a row stuck in sending` — crash during send leaves `sending` with `attempted_at`/lease set; nothing claimable at the same instant; at +61 s the row is re-claimed and sent | PASS |
| 4 | Two concurrent dispatchers: exactly-once claiming (SKIP LOCKED) | `two concurrent dispatchers claim each row exactly once` — 30 rows, two dispatcher loops in parallel threads, claim limit 3: 30 claims total, every `send_key` sent exactly once, all rows `sent` | PASS |
| 5 | `ops_done` resume of reaction sub-ops | `crash mid reaction sub-ops resumes the remaining ones without redoing completed ones` — crash after the second reaction reached the vendor but before its record: resume does not re-send, does not redo the recorded reaction, redoes the lost one once with the same txn key; `ops_done = 7`, `sent`, not flagged; the `rendered_messages` row with `choice_map` exists from before the first reaction | PASS |

Supporting tests: `retryable failure reschedules without the duplicate flag and a later claim succeeds`
(backoff rung, lease released, no flag on a quick retry), `edit op edits the target handle and marks sent`
(idempotent-by-content edit), and core unit tests for the `ops_done` bitmap, the handle codec, the backoff
ladder and reaction extraction.

## Verdict: GO

Every go condition of the roadmap entry holds as an automated test: one row per `send_key`; a crash after the
send yields at most one extra send flagged `possible_duplicate` with no extra state change; lease expiry
re-claims; two concurrent dispatchers claim exactly once; `ops_done` resumes reaction sub-ops. The code is
production code in its final packages (`core/scheduling`, `core/ports`, `infra/db`) with the real `ChatAdapter`
trait and the M0.3 SQL layer; M1.7 extends it (jitter, fallback, rate limiting, epoch fencing) rather than
rewriting it. No ADR-003/ADR-009 decision changed; no addendum needed.

## Open follow-ups for M1.7 (not defects of this slice)

- Epoch fencing and `cancelOlderQueued` need the occurrences repository (M1.5/M1.6).
- Channel-fatal errors currently land in `failed_permanent`; the channel-dead/fallback path needs
  `delivery_channels` wiring.
- Backoff has no jitter yet; the retry-instant property test belongs to M1.7.
- `target` and `payload` decoding is deliberately shallow (plain JSON strings); the loop's producers define the
  structured payload in M1.6.
