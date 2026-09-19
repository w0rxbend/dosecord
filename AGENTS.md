# Dosecord agent instructions

## Mission and source of truth

Dosecord is being rewritten from the Python/Kafka prototype into the Scala
system defined in [docs/DESIGN.md](docs/DESIGN.md). The delivery order and the
only product-slice status table are in [docs/ROADMAP.md](docs/ROADMAP.md).

Read, in this order, before changing product code or product documentation:

1. This file.
2. The selected roadmap slice and its dependencies.
3. The relevant section of `docs/DESIGN.md` and any ADR named by the slice.
4. The relevant product specification, usually
   `docs/MEDICATION_REMINDER_UX.md`.
5. The source and tests that the slice changes.

`docs/ROADMAP.md` wins on delivery order and status. `docs/DESIGN.md` wins on
architecture. ADRs record decisions; amend an ADR instead of silently changing
an accepted decision. The medication UX document remains the product
specification.

## Current entry point

M0.0 is `green`. The current eligible product slice is **M0.1 — Mill build and
module graph**. Its remaining work is exactly the work listed in the M0.1
entry: the `build.mill` module graph (`contracts`, `core`, `infra`,
`adapter-console`, `app`, `tests/conformance`), the pinned toolchain and
quality flags, and the acceptance checks recorded in the roadmap.

## Agent workflow

Use the process in [docs/AGENTIC_DEVELOPMENT.md](docs/AGENTIC_DEVELOPMENT.md).
For every product slice:

1. Create or claim one issue and one short-lived branch/worktree for exactly
   one roadmap slice. State its dependencies and acceptance criteria in the
   issue or PR.
2. Check that all dependencies are `green` in the roadmap before editing.
3. Make the smallest vertical change that satisfies the slice. Do not bundle
   adjacent cleanup, later roadmap work, or a design change.
4. Run the slice's stated verification. Add focused tests when the slice calls
   for behavior; do not replace the stated acceptance criteria with narrower
   checks.
5. Record the command output or smoke transcript in the PR. Update the
   roadmap status only after the change is merged and the roadmap definition
   of `green` is met.

## Boundaries

- Keep `core` and `contracts` vendor-neutral. The forbidden vendor-symbol
  boundary in ROADMAP M0.4 is a release gate, not an optional convention.
- Do not retain executable Python, Kafka, or Poetry implementation work after
  the M0.2 migration slice. Until then, treat the current implementation as
  historical, not a pattern to extend.
- Never log credentials, message bodies, medication names, or notes. Do not
  collect passwords in chat.
- Keep all medication guidance non-clinical. The bot records user actions; it
  must not prescribe or advise doses.
- Do not add a product slice absent from the roadmap. A user-directed
  repository-governance change may be separate, but must not alter the
  roadmap's product status.

## Handoff standard

A handoff must name the roadmap slice, files changed, verification performed,
and the next dependency-unblocked slice. Leave the worktree clean apart from
the intentional change. Do not claim completion without evidence for every
acceptance criterion.
