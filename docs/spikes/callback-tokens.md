# Spike: callback tokens (M0.6)

Status: complete — **verdict: GO**
Date: 2026-09-20
Slice: M0.6 — CallbackCodec and action registry (risk-first, code kept). Decision: [ADR-006](../adr/ADR-006.md). Ack policy: [ADR-013](../adr/ADR-013.md).

## Outcome

All five required properties hold against the implementation in
`core/src/dosecord/core/chat/`. The code and the property tests stay and are
promoted by M0.9. No fallback (shrink the MAC to slot-only payloads) is needed.

## Token layout (34 raw bytes, DESIGN.md section 4.4)

```
byte 0      version (4 bits) | key_id (4 bits)     # version = 1, two active key ids
byte 1      mode: 0 = direct, 1 = slot
bytes 2-3   action: UInt16 from ActionRegistry (opensForm / visibility / requiresSession)
bytes 4-19  subject: 16-byte UUID (occurrence id in direct mode, callback_slots.id in slot mode)
bytes 20-25 value: 6 bytes, big-endian, zero-padded (snooze minutes, choice index, step_seq, enum code)
bytes 26-33 MAC: HMAC-SHA256(key[key_id], bytes 0..25)[:8], compared constant-time
```

Wire form: `dc:` + base64url-no-pad(34 bytes) = `dc:` + 46 chars = 49 chars,
used verbatim as a Discord custom_id or Telegram callback_data. Decode rejects
non-canonical pad bits in the last base64url char, so the wire encoding is not
malleable.

The registry is static (`ActionRegistry` object) with the DESIGN.md section 4.4
ids 1-7, 20, 30-32, 40. One deliberate deviation: `dose.correct` is registered
with `opensForm = false`, because M1.10/M2.2 define correction as a two-choice
button row ("Log as taken: [Now][At scheduled time][Cancel]"), not a modal;
DESIGN.md section 4.4's `dose.correct[opensForm]` annotation is stale and
should be reconciled in the M0.9 documentation pass. (Reconciled in M0.9: the
section 4.4 annotation now reads `dose.correct` with opensForm = false.)

## Key overlap

`CallbackKeys(current, overlap)` models rotation with two active key ids:
encoding always signs with `current`; verification accepts either active id
(selected by the token's key-id nibble). After the old key retires, its tokens
fail with `UnknownKeyId`. `dosecord admin tokens rotate` (M4.3) reuses this
shape.

## Property results (munit-scalacheck, 100 iterations each, all green)

1. Every valid token matches `^dc:[A-Za-z0-9_-]{46}$` — pass.
2. Every valid token is at most 64 UTF-8 bytes and at most 100 chars (always exactly 49) — pass.
3. Encode then decode round-trips mode, action, subject and value — pass.
4. Any single-bit tamper anywhere in the 34 raw bytes is rejected, for tokens
   signed with either active key id, decoded by a codec holding both — pass
   (272 bit positions exercised per sample).
5. A token signed with the previous key verifies during overlap and is rejected
   once that key is retired — pass.

Supporting unit tests pin the byte layout against a fixed fixture, the per-error
outcomes (`Malformed`, `UnsupportedVersion`, `UnknownKeyId`, `BadMac`,
`UnknownMode`, `UnknownAction`), and the registry's id list and flags.

Verification: `./mill core.compile`, `./mill core.test` (13 tests, 0 failed),
`./mill __.compile`, `./mill __.test`, `./mill mill.scalalib.scalafmt/checkFormatAll`,
`./mill __.fix --check` — all green on branch `feature/m0-6-callback-codec`.
