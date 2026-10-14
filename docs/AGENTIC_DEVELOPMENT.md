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
   UX-00 (issue #2) is the governance adoption entry; after its verified merge,
   M2.2 is next. ROADMAP allocates M2.2a-h before M2.3; packet IDs are not
   multi-slice implementation ownership.
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

## Autonomous execution and evidence boundaries

The owner authorizes the normal issue/branch/worktree/commit/PR/CI/merge
lifecycle without routine human approval. Existing repository permissions and
branch protections still apply; never relax them or grant token scopes.
Coordinator owns remote transitions and reads back exact issue/PR/main/CI
state. Delegated owners obey their explicit scope (including no-commit or
local-only tasks). Serialize shared contracts/migrations after interface
freeze; every official split has its own issue/branch and owner.

Before merge, independent UX and security/correctness reviewers inspect the
actual diff, graph, transcripts and artifacts; independent QA verifies fresh
command outputs and nonzero executed counts, not the author's assertions.
Missing reviewer, unavailable required tooling, unresolved must-fix findings,
failed/absent required CI or unproven acceptance **blocks** merge and status
promotion, except the explicit M2.2 implementation checkpoint in ROADMAP.
That checkpoint requires all automated acceptance, local canonical checks and
independent reviews before integration, then required CI on the exact main
commit; it does not qualify live criteria, promote green/in-use or unblock
slices that depend on M2.2 green. Fix, rerun and re-review findings; bounded repeated failure leaves
a durable blocked issue/evidence and permits unrelated unblocked work.
Review must be genuinely independent; self-review is insufficient.

For UX graph changes run `python3 scripts/check-bot-ux-plan.py`,
`python3 scripts/test-check-bot-ux-plan.py`, `scripts/doc-lint.sh` and
`git diff --check`; required CI enforces the first three in doc-lint. Product
slices additionally run their full existing acceptance suites: structural
validation cannot prove route implementation or runtime correctness. Record
criterion, exact command, executed/ignored counts, environment/revision,
artifact/hash and pass/fail/blocked. Never substitute zero-tests or synthetic
vendor responses for real acceptance evidence.

Secret access, live Discord login/sends, deployments, real health records and
destructive operations require separate explicit authorization and established
backup/deployment policy. Use synthetic accounts and disposable infrastructure
for tests. Never use normal-user tokens/self-bots or forge provider events as
live evidence. Unavailable live/device observations remain blocked; automated
technical attestations do not waive mobile push, subjective usability or
seven-day in-use milestone requirements. User Create/Archive/Delete
confirmations remain data-safety controls, not development approval gates.

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
