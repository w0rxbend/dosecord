# Spike note: JDA DM delivery and user-install context (M0.8)

Status: complete — **verdict: GO** (live autocomplete round trip not observed; deferred to M2.1, see below)
Date: 2026-09-23
Slice: M0.8 — Spike C — JDA DM delivery and user-install context (throwaway).
Deps: M0.1 (green). Decision records read: ADR-001, ADR-013; docs/DESIGN.md
section 5.

## Environment

Throwaway spike project outside the repo (never committed, deleted after this
note): standalone Mill 1.1.9 build, Scala 3.8.4, JDA 6.6.0, JDK 25 (temurin).
The program registered one global slash command, `/spike`, with options
`medication` (STRING, `autocomplete: true`) and `modal` (BOOLEAN), using
`IntegrationType.USER_INSTALL` and contexts `BOT_DM, PRIVATE_CHANNEL` only.
The application's `integration_types_config` already carried a user-install
entry (`"1"` with the `applications.commands` scope), so no developer-portal
change was needed. Gateway intents: JDA defaults — no message-content intent,
nothing privileged; DM interactions need no further intent.

## Machine-verified facts (no human)

| Fact | Evidence | Result |
|---|---|---|
| Command registration carries USER_INSTALL and the bot-DM context | Raw REST `GET /applications/{id}/commands`: `"integration_types":[1]`, `"contexts":[1,2]`, `medication` option `"autocomplete":true` | PASS |
| `setNonce` sends `enforce_nonce: true` | Local mock-REST fixture: a real `sendMessage(...).setNonce(...)` through JDA 6.6.0 with the REST base pointed at the mock; the captured request body is below | PASS |
| Bot login | Gateway READY (session `session_ready`, ping 169 ms), idle-stable | PASS |

Captured JSON body (exact bytes JDA 6.6.0 POSTs for a nonce-bearing message;
nonce per DESIGN.md section 5, `base64url(sha256(sendKey)).take(22)`):

```json
{"components":[],"tts":false,"flags":0,"allowed_mentions":{"parse":["users","roles","everyone"],"replied_user":true},"poll":null,"embeds":[],"enforce_nonce":true,"nonce":"HAbBu49zpG9ob0IYsXhHMJ","content":"M0.8 nonce fixture"}
```

## Live facts (owner run, 2026-09-23)

Evidence: redacted JSONL observation log — event names, ack latencies,
interaction snowflakes and ids only; no credentials, no message bodies, no
medication names (the submitted modal value was never logged).

| Fact | Evidence | Result |
|---|---|---|
| Proactive DM from a user-installed app with no shared guild | `proactive_dm_sent`, nonce `KGsWXmhvKwhH53iADlDIoV`, on the owner's first DM interaction (`channel_type: PRIVATE` throughout; the app was installed to the owner's account, not to any guild); owner confirmed receipt | PASS — the go criterion |
| Button round trip | `slash_replied_with_button` ×2; `button_acked_edit` ×5 (edit as the single ack) | PASS |
| Modal on an un-acked interaction | `modal_opened` — `replyModal` was the first and only ack (129 ms); `modal_submit_replied` (111 ms) | PASS |
| Ack under 1.5 s from the snowflake | `ack_latency_ms` 102–185 across every acked interaction (slash, button, modal open, modal submit) | PASS |
| Slash option autocomplete round trip | Handler implemented (`CommandAutoCompleteInteractionEvent` → `replyChoices` over the fixture list); option registered with `autocomplete: true` (machine check above). The live round trip was NOT observed: no `autocomplete_replied` event reached the bot from the owner's client despite the owner's attempt. | NOT OBSERVED live — deferred to M2.1 |

## Verdict: GO

The roadmap's go criterion is "proactive DM works without a shared guild" —
confirmed live: the bot DM'd the owner proactively from a user-installed app
with no shared guild, with a nonce-bearing message. Per the roadmap entry:

- The home-guild setup step is dropped.
- The ADR-001/ADR-013 addenda (in `docs/adr/ADR-001.md` and
  `docs/adr/ADR-013.md`) record that Dosecord registers `USER_INSTALL` only.
  DESIGN.md section 5 currently lists both install types
  (`IntegrationType.USER_INSTALL` and `GUILD_INSTALL`); the addendum amends
  that listing.
- The partial remedy (`canInitiateDm = SharedGuildOrInstall`, `GUILD_INSTALL`
  kept, home-guild step documented) and the no-go remedy (reopen ADR-001's
  Discord-library lens before M2) are not needed.

The verdict is unaffected by the unobserved autocomplete round trip: the go
criterion is the proactive DM without a shared guild, and autocomplete is not
part of it.

Autocomplete caveat: the live autocomplete round trip is the one fact this
spike could not evidence — the handler and registration are verified, but no
`autocomplete_replied` event reached the bot from the owner's client. This is
a hard requirement on M2.1, not a conditional: M2.1's acceptance criteria
already require "an autocomplete request returns the seeded medication
names", and M2.1 must verify the round trip there before relying on it.

## Inputs for M2 (owner directive, 2026-09-23)

The owner requires Zephyr to be a per-user, DM-only personal app: all tracking
communication happens in the bot DM. If a command is invoked in a server or a
group DM, the only reply must be visible to the sender alone — an ephemeral
notice along the lines of "Zephyr works in direct messages only — please open
Zephyr's DM." This aligns with the M2.1 roadmap entry (guild-context
interactions refused with an ephemeral notice as defence in depth) and extends
it to group-DM (`PRIVATE_CHANNEL`) contexts. Privacy caveat for M2.1: Discord
shows the invoking user's typed option values on hover of the "X used
/command" line in shared channels, so free-text options (e.g. a medication
name) can leak even when the reply is ephemeral; M2.1 should weigh
registering sensitive commands with the `BOT_DM` context only (command
invisible outside DMs) against the redirect UX.

## Facts covered

R45, R47, R53, C23, K1, K13 (per the roadmap entry): user-install DM
reachability without a shared guild, send idempotency via
`enforce_nonce: true`, interaction acknowledgement inside the 1.5 s budget
measured from the interaction snowflake, modal-on-un-acked-interaction, and
the USER_INSTALL-only registration decision.

## Addendum (recorded in docs/adr/ADR-001.md and docs/adr/ADR-013.md)

See the `## Addendum (2026-09-23)` sections of ADR-001 and ADR-013: JDA 6.6.0
delivers proactive DMs from a user-installed app with no shared guild
(verified 2026-09-23, M0.8), so Dosecord registers global commands with
`IntegrationType.USER_INSTALL` only and the home-guild setup step of
DESIGN.md section 5 is dropped; DESIGN.md section 5's both-install-types
listing is amended accordingly. ADR-013's ack policy is confirmed against
observed latencies (102–185 ms against the 1.5 s Discord watchdog).

## Verification performed

- `./mill spike.compile` — green.
- `./mill spike.run fixture` — FIXTURE PASS: `enforce_nonce: true` asserted in
  the captured JSON body (above); exactly one POST observed.
- `./mill spike.run bot` — gateway READY; `registration_verify` PASS on the
  raw REST JSON; bot idle-stable serving interactions.
- Owner live run — the ten observation-log events tabled above.
- Spike code deleted after this note; this note and the two ADR addenda are
  the only committed artifacts.
