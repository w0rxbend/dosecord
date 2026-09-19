# Agentic development process

This process turns the delivery plan in [ROADMAP.md](ROADMAP.md) into small,
reviewable agent tasks. It governs how work is selected and verified; it does
not replace the roadmap or its status table.

## Operating model

One agent owns one roadmap slice at a time. The agent uses a short-lived
branch and, when work runs concurrently, an isolated Git worktree. A slice is
never split between agents unless the roadmap explicitly allows its parts to
interleave. Agents may research or review in parallel, but only the owning
agent changes the slice's implementation and status evidence.

Every product change traces to a roadmap slice. If work does not fit an
existing slice, propose a roadmap amendment before implementation. This rule
does not prevent repository-governance work requested directly by the owner.

## Start a slice

1. Select the first dependency-unblocked slice in the roadmap's Appendix B.
   The current starting slice is M0.0.
2. Open an issue using the roadmap-slice template. Copy the slice's
   acceptance criteria verbatim and list every dependency with its current
   status.
3. Create `feature/m0-0-documentation-cut`-style branch naming and an
   isolated worktree if another slice is active.
4. Read `AGENTS.md`, the slice, the relevant design section, named ADRs, and
   the source/tests in scope. State any unresolved conflict before editing.
5. Define the smallest reviewable increment. A large slice must name the
   roadmap's required intermediate green state in its PR description.

## During implementation

Work only within the selected slice. Preserve unrelated changes in a dirty
worktree. Keep sensitive user data out of source, test fixtures, logs, and PR
text. An ADR change is an addendum to the accepted record, never a rewrite of
its original decision.

Use the acceptance criteria as the test plan. Run focused checks as each
increment lands, then run all required checks for the slice. When a criterion
needs a manual or vendor smoke, record the exact commands, environment, and
observable result in the PR rather than marking it implied.

## Review and merge gate

Before requesting review, the PR must show:

- one roadmap slice and no unapproved scope expansion;
- each dependency is green;
- every acceptance criterion, with a link to the test, command output, or
  smoke transcript that proves it;
- relevant design/ADR/roadmap documentation updated; and
- no secrets or sensitive health data in the diff.

After merge, update Appendix B only when the roadmap's definition of `green`
is satisfied: the change is on `main`, required CI is green, each acceptance
criterion has automated or recorded smoke evidence, and documentation is
updated. `in-use` additionally requires seven days of owner use without a P1
defect, where the roadmap marks it required.

## Current M0.0 checklist

M0.0 is the process bootstrap for the product rewrite. Its owner should
complete only these actions from the roadmap:

- keep `DESIGN.md`, the ADR set, design tracks, and ROADMAP under version
  control;
- delete the retired architecture document;
- archive the swarm research summary;
- replace obsolete Kafka guidance in the production plan with a pointer to
  the design;
- mark the API document planned; reduce README, QUICKSTART, and DEVELOPMENT
  to reset stubs; and
- add `docs/doc-lint.txt`, then prove no forbidden legacy terms appear outside
  the roadmap's allowlist.

When that checklist and the M0.0 acceptance criteria are proven on `main`, its
owner marks M0.0 green. M0.1 then becomes eligible.

## Handoff format

Use this in every issue, PR, or agent handoff:

```text
Slice: Mx.y — name
Dependencies: Mx.y green; ...
Scope: files and behavior changed
Evidence: commands/tests/smokes and their results
Decision records: ADRs read or amended
Next eligible slice: Mx.y
Open risks: none | specific unresolved risk
```
