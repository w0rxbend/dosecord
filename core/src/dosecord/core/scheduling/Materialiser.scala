package dosecord.core.scheduling

import dosecord.core.domain.Evaluator
import dosecord.core.domain.ScheduleRevision
import dosecord.core.ports.Clock
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.StoredSchedule
import dosecord.core.ports.StoredScheduleRevision
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork

import java.time.Instant
import java.util.UUID

/** The materialiser of ADR-004 / DESIGN.md section 7.2: expands the current revision of a schedule through the pure
  * `Evaluator` into `dose_occurrences` rows, inserted with no-target `ON CONFLICT DO NOTHING` so every unique index —
  * including the cross-revision live-slot one — arbitrates, and re-materialisation is idempotent. The batch job
  * ([[Materialiser.runOnce]]) claims due schedules `FOR UPDATE SKIP LOCKED`; the per-schedule expansion
  * ([[Materialiser.materializeSchedule]]) is shared with the revision lifecycle, which materialises a fresh revision
  * inside the edit transaction.
  */
object Materialiser:

  /** The evaluator view of a stored revision (M1.2's `ScheduleRevision`): the validity window intersected with the
    * schedule's `start_date`/`end_date` (`end_date` inclusive). `effective_from` is stored as an instant but governs at
    * local-date granularity in the revision's zone (DESIGN.md section 7.1's next-local-midnight rule).
    */
  def toEvaluator(schedule: StoredSchedule, revision: StoredScheduleRevision): ScheduleRevision =
    val effectiveDate = revision.effectiveFrom.atZone(revision.tz).toLocalDate
    val start = if schedule.startDate.isAfter(effectiveDate) then schedule.startDate else effectiveDate
    ScheduleRevision(
      rule = revision.rule,
      zone = revision.tz,
      policy = revision.policy,
      effectiveFrom = start,
      effectiveUntil = schedule.endDate.map(_.plusDays(1))
    )

  /** Inserts the revision's candidates whose `scheduled_for` falls in `[from, to)`. Returns the number of rows actually
    * inserted (skipped conflicts are not counted — they are identical rows or live slots another revision already owns,
    * both correct under ADR-004).
    */
  def materializeSchedule(
      tx: Tx,
      schedule: StoredSchedule,
      revision: StoredScheduleRevision,
      from: Instant,
      to: Instant
  ): Int =
    val candidates = Evaluator.occurrences(toEvaluator(schedule, revision), from, to)
    val rows = candidates.map(candidate =>
      NewOccurrence.materialized(
        id = UUID.randomUUID(),
        accountId = schedule.accountId,
        medicationId = schedule.medicationId,
        scheduleId = schedule.id,
        revision = revision.revision,
        candidate = candidate,
        tz = revision.tz,
        policy = revision.policy,
        snapshot = revision.doseSnapshot
      )
    )
    tx.occurrences.insertAll(rows)

/** The materialiser job (ROADMAP M1.5, DESIGN.md section 7.2): runs every 15 minutes and on demand, extends every due
  * schedule's materialised window to `now + 48 h` and advances `materialized_through` (the
  * `min(materialized_through) < now` lag alert reads it). One transaction per batch; the SKIP LOCKED claim gives
  * concurrent materialisers disjoint schedules and the no-target `ON CONFLICT DO NOTHING` insert makes overlapping runs
  * produce identical row sets (ADR-004).
  */
final class Materialiser(uow: UnitOfWork, clock: Clock):

  /** One batch; returns the number of schedules claimed. */
  def runOnce(limit: Int = 100): Int =
    val now = clock.now()
    uow.transaction { tx =>
      val horizonEnd = now.plus(Evaluator.MaterialisationHorizon)
      val claimed = tx.schedules.claimForMaterialisation(horizonEnd, limit)
      claimed.foreach { schedule =>
        tx.revisions.latest(schedule.id).foreach { revision =>
          Materialiser.materializeSchedule(tx, schedule, revision, now, horizonEnd)
          tx.schedules.advanceMaterializedThrough(schedule.id, horizonEnd, now)
        }
      }
      claimed.size
    }
