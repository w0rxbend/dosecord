# Agentic development process

This process turns the delivery plan in [ROADMAP.md](ROADMAP.md) into small,
reviewable agent tasks. It governs how work is selected and verified; it does
not replace the roadmap or its status table.

## Operating model

One agent owns one roadmap slice at a time. By the owner's standing
instruction of 2026-11-13 the agent commits directly to `main`, in small
conventional commits, each after a green standard gate (ROADMAP section 0.6);
when work runs concurrently it uses an isolated Git worktree and a short-lived
branch that is merged the same way. An issue and a pull request per slice are
optional while the owner is the only reviewer of record; the evidence they
would carry goes into the commit message and the handoff. A slice is
never split between agents unless the roadmap explicitly allows its parts to
interleave. Agents may research or review in parallel, but only the owning
agent changes the slice's implementation and status evidence.

Every product change traces to a roadmap slice. If work does not fit an
existing slice, propose a roadmap amendment before implementation. This rule
does not prevent repository-governance work requested directly by the owner.

## Start a slice

1. Select a dependency-unblocked slice in the roadmap's Appendix B: every
   dependency is `green` (or `inherited` and re-verified) and the milestone
   gate named in the "Depends on" column is closed, unless the slice is a
   pull-forward of ROADMAP section 0.5. `AGENTS.md` names the current entry
   point.
2. Copy the slice's acceptance criteria verbatim into the issue, when one is
   opened with the roadmap-slice template, or into the working notes, and list
   every dependency with its current status.
3. When a branch is used, name it after the slice (`feat/g1-4-telegram-client`)
   and use an isolated worktree if another slice is active.
4. Read `AGENTS.md`, the slice, the requirement ids it names in the document
   that owns them, named ADRs, and the source/tests in scope. State any
   unresolved conflict before editing.
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

Before a commit lands on `main`, the commit message or the pull request must
show:

- one roadmap slice and no unapproved scope expansion;
- each dependency is green;
- every acceptance criterion, with a link to the test, command output, or
  smoke transcript that proves it;
- relevant specification/ADR/roadmap documentation updated; and
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
failed/absent required CI or unproven acceptance **blocks** status promotion.
Fix, rerun and re-review findings; bounded repeated failure leaves a durable
blocked issue/evidence and permits unrelated unblocked work. Review must be
genuinely independent; self-review is insufficient.

Checkpoints follow ROADMAP section 4.1. A checkpoint of type "gate" blocks
every slice that depends on the slices before it and carries a decision only
the owner can make; a checkpoint of type "review" does not block. A checkpoint
does not replace the independent review.

Documentation changes run `scripts/doc-lint.sh`, `scripts/test-doc-lint.sh`
and `git diff --check`; required CI enforces the first two in the `doc-lint`
job. Product slices run the standard gate of ROADMAP section 0.6 and their
full acceptance suites, and changes under `miniapp/` the frontend gate. Record
criterion, exact command, executed/ignored counts, environment/revision,
artifact/hash and pass/fail/blocked. Never substitute zero-tests or synthetic
vendor responses for real acceptance evidence.

Secret access, live Telegram login/sends, deployments, real health records and
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
Slice: Gx.y name
Dependencies: Gx.y green; gate CPx-y closed; ...
Scope: files and behavior changed
Evidence: commands/tests/smokes and their results
Decision records: ADRs read or amended
Next eligible slice: Gx.y
Open risks: none | specific unresolved risk
```
