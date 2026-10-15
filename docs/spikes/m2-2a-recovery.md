# M2.2a interaction recovery — local implementation evidence

Issue: #12. Branch/worktree: `feature/m2-2a-recovery` /
`/home/worxbend/.hermes/cache/scratch/dosecord-m2-2a-recovery`.
Base: `71f63c8`. This is local evidence, not integration, a second status table,
provider qualification or a declaration of green. Resumed review-blocker verification
is recorded below against the same uncommitted artifact. The coordinator owns dependency
checkpoint readback, independent reviews, integration and exact-main CI.

## Implemented boundary

- Persistent source replacements enqueue `edit` and the encoded source target.
  The originating ChatRef is persisted as `deliveryChat` in otherwise compatible
  OutboundMessage payloads, so recovery also works before account linking.
- SQL commit acknowledgement loss reports Checking whether this was recorded…
  and performs one durable inbound/handle readback. It never blindly reruns a
  handler or claims Nothing was recorded. Failed readback fails closed.
- Nonfatal `PermanentReason.MissingTarget` permits a stable-key replacement;
  generic permanent/fatal transport failures retain the existing error path.
  Dispatcher records replacement handles before its terminal write and reuses
  them after lease expiry. Stored-reply replay consults the recorded handle.
- Synchronous edits update existing callback maps. Native-private hook results
  never create ordinary safety rows or leak named details into channel recovery.
- Accepted ADR-005/013 addenda and DESIGN describe the scoped refinements.

## Actual RED/GREEN evidence

Logs reside in `/home/worxbend/.hermes/cache/scratch/`.

| Log | Actual observation |
| --- | --- |
| `m2-2a-red.log` | Two executed PostgreSQL tests failed: recovery lost its originating destination; uncertain commit used retry/failure copy instead of reconciliation. |
| `m2-2a-red-missing-fixed.log` | Missing-source synchronous recovery produced zero replacement messages instead of one. |
| `m2-2a-red-dispatch.log` | Dispatcher failed to reach replacement handle/terminal bookkeeping fault boundary. |
| `m2-2a-red-replay.log` | Stored-reply replay edited the vanished original instead of the recorded replacement. |
| `m2-2a-red-readback.log` | An unavailable recorded-handle read still edited the original instead of failing closed. |
| `m2-2a-red-map-fixed.log` | Synchronous edit retained the old callback map. |
| `m2-2a-final-tests-retry.log` | Final `./mill __.test`, normal parallelism: 86 suites, 1,071 executed tests, zero failed, zero ignored. Includes 10 InteractionRecoveryPgSuite tests on disposable PostgreSQL 18. |

The PostgreSQL suite covers accepted source edit/bookkeeping loss, dispatcher
restart, missing-source replacement handle resume, stored-reply replay and failed
readback, callback-map refresh, unlinked destinations, bounded unavailable
reconciliation, private-hook send/bookkeeping failures followed by restart,
eight-attempt edit exhaustion, and a real mood/domain-event commit whose lost
acknowledgement/replay still leaves one mood row and one domain event.

One full-suite attempt exposed an existing asynchronous toast fixture race:
neutral completion was mistaken for the final hook follow-up. Its wait now
checks exact expected content. Another normal-parallel run had four PostgreSQL
container startup log-readiness timeouts; the unchanged command was rerun and
all 1,071 tests executed successfully. These failed attempts are retained in
`m2-2a-full-test.log` and `m2-2a-final-test.log`; they are not green evidence.

## Other verification

- `./mill __.checkFormat + __.fix --check + __.compile`: pass under existing
  `-Werror` options (`m2-2a-gates.log`). New PostgreSQL test source was formatted;
  unrelated legacy test-format churn was reverted before final tests.
- `./mill app.test.runMain dosecord.app.envExampleCheck .env.example`: pass.
  No `.env` or operator secrets read.
- `scripts/license-gate.sh`: pass, 124 dependencies declaring permissive licenses.
- Vendor-symbol boundary, SQL-now gate, doc-lint, UX-plan validator: pass.
  UX-plan negative suite: 44 tests pass. `git diff --check`: pass.
- `./mill core.scoverage.xmlReport` + current `scripts/coverage-gate.sh`: exit 0
  in the existing unarmed scaffold mode. Domain 97.06%; chat 79.34%, **below the
  future enforced 85% requirement**. This is not a claim of final coverage-gate
  qualification (`m2-2a-coverage.log`).
- Docker image build: pass (`m2-2a-docker-build.log`), local tag
  `dosecord:m2-2a-recovery-offline`, config digest
  `sha256:ebf885220483f42cf03c0ddce3fbf48b333fc2eed368b0edb8f72a013226e10c`.

## Blocked / deliberately unclaimed

The initial disposable runtime probe was denied and was not retried until the
owner explicitly renewed authorization. The subsequently executed isolated
probe passed Flyway CLI migration/validation, HTTP 200 health, runtime UID 10001,
idle RSS 128688 kB under the unchanged 200 MB limit, and SIGTERM shutdown in
5.272 seconds with exit 0 (budget 20 seconds). Its image digest was
`sha256:21bf18846d6bee5f6b5241843486f3847d1c6f470219dc472fb190c6ff66cd31`.
Readback verified all owned test containers and network removed; no persistent
test volumes were created. Evidence: scratch
`m2-2a-authorized-container-probe.log`. No live bot or existing records touched.

No live Discord/account data, deployment, remote mutations, commits or pushes
were performed. No product status changed. M2.2b worker ownership, M2.2c complete
multi-page handle bookkeeping, broader menus/drafts/manual-intent/media changes
remain out of scope. Source edits are idempotent by content; newly created
replacement/send messages still inherit ADR-009's bounded provider-nonce window,
not permanent exactly-once delivery. Reconciliation now retains the existing
interaction safety-row ID: successful stored-reply delivery persists its replacement
handle and retires that row without repeating the domain action. Final integration
is blocked on independent reviews, remaining local gates and coordinator exact-main CI.
Under the bounded checkpoint order, M2.2b is next only after M2.2a integration is
independently qualified.

## Review-blocker repairs and resumed verification

- `storedDeliveryPlan` obtains `OutboxRepository.replyId` for the existing
  interaction safety row. Synchronous reconciliation/replay records the delivered
  handle on that row instead of dropping durable bookkeeping with `None`.
  PostgreSQL and all test implementations compile with the added port method.
- Both synchronous and dispatcher missing-target catches require
  `!channelFatal`; a fatal `MissingTarget` cannot send a named replacement.
- `m2-2a-review-red.log`: executed 13 PostgreSQL regressions, exactly three
  failed on the pre-repair implementation: absent durable replacement handle,
  fatal synchronous fallback send, and dispatcher row incorrectly marked sent
  instead of dead. These are behavior failures, not compile/discovery failures.
- `m2-2a-review-green.log`: same suite after repairs, 13 executed, zero failed
  or ignored. The combined uncertain-commit/missing-source regression checks
  durable handle readback, replay a day later, one replacement, one handler call,
  one inbound row and one outbox row; dispatcher recovery has no queued row left.
- Fresh `./mill __.test` at normal parallelism:
  `m2-2a-resumed-full-tests.log`, **86 suites, 1,074 tests, zero failed,
  zero ignored**, including all 13 `InteractionRecoveryPgSuite` tests.
  Counts were parsed from every suite's executed-result line, not inferred.
- Fresh `./mill __.checkFormat + __.fix --check + __.compile`: pass
  (`m2-2a-resumed-gates.log`). Vendor-symbol, SQL-now, doc-lint, UX-plan,
  44 UX-plan negative tests and `git diff --check`: pass
  (`m2-2a-resumed-static.log`). Generated env-example check and license gate
  (124 dependencies): pass (`m2-2a-resumed-envexample.log`,
  `m2-2a-resumed-license.log`).
- Fresh coverage report/scaffold gate: domain 97.06%, chat **79.31%**, still
  below the future enforced 85% threshold (`m2-2a-resumed-coverage.log`).
  Scaffold exit zero is not coverage qualification.
- Fresh repaired-image build: pass (`m2-2a-resumed-docker-build.log`), local
  tag `dosecord:m2-2a-recovery-offline`; image inspect readback:
  `sha256:21bf18846d6bee5f6b5241843486f3847d1c6f470219dc472fb190c6ff66cd31`.
  Build config digest:
  `sha256:50b6633c5368b9ccdfa92d0c816ed13db82f69cbc592d40ec237ed8dc0764310`.
- The runtime probe remained blocked during author verification; after renewed
  owner authorization the coordinator executed and verified it as recorded above.
- Parent independently reran the repaired full suite: 86 suites, 1,074 tests,
  zero failed/ignored (`m2-2a-parent-repaired-tests.log`). Final independent
  correctness/security/UX re-review found no unresolved must-fix findings.
- No live actions or final product qualification are claimed. Coordinator owns
  integration and exact-main CI; record their durable attestation in issue #12.

The local artifact has cleared independent re-review and local runtime checks;
exact-main CI after integration remains mandatory. This is not final live/use
qualification or permission to promote green/in-use.
