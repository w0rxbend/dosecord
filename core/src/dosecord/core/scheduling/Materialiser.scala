package dosecord.core.scheduling

import dosecord.core.domain.Evaluator
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ScheduleRevision
import dosecord.core.domain.UnknownReason
import dosecord.core.ports.Clock
import dosecord.core.ports.LoopMetrics
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
  *
  * ROADMAP M1.8 (DESIGN.md section 7.5, "materialiser-first startup"): the expansion window starts at the schedule's
  * `materialized_through` when that lags `now`, so rows the downtime swallowed are materialised after all — and a
  * candidate whose `miss_deadline` already passed is inserted directly as `unknown(outage)`, never replayed as a fresh
  * reminder.
  */
object Materialiser:

  /** The result of one expansion: rows inserted, of which this many were past-deadline `unknown(outage)` inserts. */
  final case class Result(inserted: Int, unknownOutage: Int)

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

  /** Inserts the revision's candidates whose `scheduled_for` falls in `[from, to)`. Candidates whose `miss_deadline`
    * passed before `now` become `unknown(outage)` rows (DESIGN.md section 7.5); the rest are ordinary `pending`
    * materialisations. Returns the counts actually inserted (skipped conflicts are not counted — they are identical
    * rows or live slots another revision already owns, both correct under ADR-004).
    */
  def materializeSchedule(
      tx: Tx,
      schedule: StoredSchedule,
      revision: StoredScheduleRevision,
      from: Instant,
      to: Instant,
      now: Instant
  ): Result =
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
    val (dead, live) = rows.partition(_.state.missDeadline.isBefore(now))
    val unknownRows = dead.map(row =>
      row.copy(state =
        row.state.copy(
          status = OccurrenceStatus.Unknown,
          unknownReason = Some(UnknownReason.Outage),
          nextActionAt = None
        )
      )
    )
    val insertedUnknown = tx.occurrences.insertAll(unknownRows)
    Result(tx.occurrences.insertAll(live) + insertedUnknown, insertedUnknown)

/** The materialiser job (ROADMAP M1.5, DESIGN.md section 7.2): runs every 15 minutes and on demand, extends every due
  * schedule's materialised window to `now + 48 h` and advances `materialized_through` (the
  * `min(materialized_through) < now` lag alert reads it). One transaction per batch; the SKIP LOCKED claim gives
  * concurrent materialisers disjoint schedules and the no-target `ON CONFLICT DO NOTHING` insert makes overlapping runs
  * produce identical row sets (ADR-004).
  *
  * M1.8: the window starts at `materialized_through` when it lags `now` (the outage gap), so a stopped job cannot
  * swallow doses; past-deadline gap rows land as `unknown(outage)` and increment `dosecord_unknown_total{reason}`.
  */
final class Materialiser(uow: UnitOfWork, clock: Clock, metrics: LoopMetrics = LoopMetrics.noop):

  /** One batch; returns the number of schedules claimed. */
  def runOnce(limit: Int = 100): Int =
    val now = clock.now()
    uow.transaction { tx =>
      val horizonEnd = now.plus(Evaluator.MaterialisationHorizon)
      val claimed = tx.schedules.claimForMaterialisation(horizonEnd, limit)
      claimed.foreach { schedule =>
        tx.revisions.latest(schedule.id).foreach { revision =>
          expand(tx, schedule, revision, now, horizonEnd)
        }
      }
      claimed.size
    }

  /** The reminder loop's safety net (DESIGN.md section 7.2, ROADMAP M1.6): schedules whose materialised window ends
    * before `lagThreshold` (the loop passes `now + 4 * TICK`) are extended to the full horizon here, so a stopped
    * 15-minute job cannot silently starve reminders. Returns the number of schedules claimed; zero when nothing lags.
    * This is also the materialiser-first startup path of M1.8: the loop runs it before the first tick.
    */
  def safetyNet(lagThreshold: Instant, limit: Int = 100): Int =
    val now = clock.now()
    uow.transaction { tx =>
      val horizonEnd = now.plus(Evaluator.MaterialisationHorizon)
      val claimed = tx.schedules.claimForMaterialisation(lagThreshold, limit)
      claimed.foreach { schedule =>
        tx.revisions.latest(schedule.id).foreach { revision =>
          expand(tx, schedule, revision, now, horizonEnd)
        }
      }
      claimed.size
    }

  /** One schedule's expansion from its `materialized_through` (the gap fill of M1.8) or `now`, through the shared
    * object-level insert, then the `materialized_through` advance.
    */
  private def expand(
      tx: Tx,
      schedule: StoredSchedule,
      revision: StoredScheduleRevision,
      now: Instant,
      horizonEnd: Instant
  ): Unit =
    val from = schedule.materializedThrough.filter(_.isBefore(now)).getOrElse(now)
    val result = Materialiser.materializeSchedule(tx, schedule, revision, from, horizonEnd, now)
    tx.schedules.advanceMaterializedThrough(schedule.id, horizonEnd, now)
    var marked = result.unknownOutage
    while marked > 0 do
      metrics.unknownMarked(UnknownReason.Outage.dbValue)
      marked -= 1
end Materialiser
