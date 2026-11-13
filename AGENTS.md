# Dosegram agent instructions

## Mission and source of truth

Dosegram is a Telegram-native personal health record keeper: a private-chat
bot for the daily loop and a Telegram Mini App for configuration and
exploration, on one Scala 3 backend with PostgreSQL. It grew out of Dosecord, a
Discord medication-reminder bot; [ADR-014](docs/adr/ADR-014.md) records the
pivot. The delivery order and the only product-slice status table are in
[docs/ROADMAP.md](docs/ROADMAP.md) (Appendix B).

Read, in this order, before changing product code or product documentation:

1. This file.
2. The selected roadmap slice, its dependencies and its milestone gate
   (ROADMAP sections 0.5 and 3, Appendix B).
3. The requirement ids the slice names, in the document that owns them, and
   any ADR the slice names.
4. The source and tests that the slice changes.

Precedence (ROADMAP section 0.1):

| Topic | Winning document |
|---|---|
| Delivery order, slice status | [docs/ROADMAP.md](docs/ROADMAP.md) |
| Product behaviour, defaults, wave and tier of a capability | [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) |
| Module structure, data model, API conventions, security architecture | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Chat flows, copy keys, message operations | [docs/BOT_UX.md](docs/BOT_UX.md) |
| Mini App screens, frontend stack, client budgets | [docs/MINI_APP.md](docs/MINI_APP.md) |
| Visual identity, image catalogue, metric and insight definitions | [docs/VISUALS_AND_ANALYTICS.md](docs/VISUALS_AND_ANALYTICS.md) |
| A recorded decision | The ADR in [docs/adr/](docs/adr/README.md). An accepted ADR is amended by an addendum, never rewritten |

[docs/MEDICATION_REMINDER_UX.md](docs/MEDICATION_REMINDER_UX.md) remains the
detailed medication specification, subordinate to `PRODUCT_SPEC.md`. The
research briefs in `docs/research/` are evidence, not requirements. Everything
under `docs/archive/` is history: the Dosecord roadmap and design keep the
evidence of the inherited slices and the detailed reference for the kept
engine, and they govern nothing.

## Current entry point

State of `main` on 2026-11-13:

- G0.3 (`d6a033d`): Discord, Zulip and Matrix are deleted. The only adapter
  is `console`. `telegram` exists as a capability profile and a setting; its
  adapter is not built (G1.4, G1.5).
- G0.4 (`b6530a3`): one product name, `dosegram`, in packages, build,
  container, CI, scripts and the Flyway baseline.
- G0.5 (`fb6b317`): `dosegram run` starts the wake listener, the materialiser,
  the reminder loop, the outbox dispatcher and the session sweeper.
- Mini App scaffold (`00bbb31`, towards G3.2): `miniapp/` holds a Svelte 5
  shell and first screens against a fake `TelegramPort` and an in-memory mock
  backend. No API exists behind it yet.
- G0.2: this governance cut-over. ROADMAP Appendix B holds its status.

Not done: G0.1 (the traceability matrix and `scripts/traceability-check.sh`
do not exist) and every owner action from OA-2 on. No Telegram bot token
exists. Everything is built and tested against a fake wire and recorded
fixtures; there are no live sends.

Eligible next, by dependency: G0.8 (secret scanning and pinned CI actions),
G2.6 (English catalogue, a pull-forward) and the spike G1.2 (render pipeline
in the production container). G1.4 and the rest of G1 wait for G0.8, the
fixtures of G1.1 and gate CP0-b; G1.1 and G1.3 need a staging bot (OA-4).

## Agent workflow

Use the process in [docs/AGENTIC_DEVELOPMENT.md](docs/AGENTIC_DEVELOPMENT.md).
For every product slice:

1. Take exactly one roadmap slice. State its dependencies and acceptance
   criteria before editing.
2. Check the dependencies and the milestone gate in Appendix B. Where a slice
   needs a live check or an owner action, build everything up to it and record
   the remaining live step; never mark it passed.
3. Make the smallest vertical change that satisfies the slice. Do not bundle
   adjacent cleanup, later roadmap work, or a design change.
4. Write the test first for behaviour. Run the slice's stated verification and
   the standard gate of ROADMAP section 0.6, each command separately, and
   record the executed test count per module. Do not replace the stated
   acceptance criteria with narrower checks, and do not weaken or skip a test
   to get a green run.
5. Owner's standing instruction (2026-11-13): work is committed directly to
   `main`, in small conventional commits, each after a green standard gate. A
   change that cannot be made green is not committed; report the decisive
   output instead. Pushing, live sends, secret access and deployments need the
   owner's separate authorisation.
6. Update Appendix B only when the roadmap's definition of the status is met.

## Boundaries

- Keep `core` and `contracts` vendor-neutral. `scripts/check-vendor-symbols.sh`
  is a release gate, not an optional convention; it also keeps the retired
  vendor and product names out of code and configuration.
- The Python prototype, its broker-based compose stack, the Discord adapter
  and the Zulip and Matrix profiles are deleted and must not return. Treat
  them as git history, not as a pattern to extend.
- Never log credentials, the bot token, `initData`, request URLs, message
  bodies, medication names, photos, or notes of any kind (dose, food, mood,
  sleep). Do not collect passwords in chat. Do not read or print `.env` or any
  secret file.
- Keep all copy and insights non-clinical and non-causal. The product records
  what the user did and reminds the user of what the user configured; it must
  not prescribe, advise a dose, interpret a value or compute a health score.
- Every generated image has a text equivalent.
- Do not add a product slice absent from the roadmap. A user-directed
  repository-governance change may be separate, but must not alter the
  roadmap's product status.

## Handoff standard

A handoff must name the roadmap slice, files changed, verification performed,
and the next dependency-unblocked slice. Leave the worktree clean apart from
the intentional change. Do not claim completion without evidence for every
acceptance criterion.
