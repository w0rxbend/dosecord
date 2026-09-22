package dosecord.core.ports

import dosecord.contracts.HhMm
import dosecord.core.domain.CancelReason
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceCandidate
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The `dose_occurrences.origin` CHECK values: materialised from a revision, created by a chain (M7.2), or logged
  * manually (as-needed, M1.10).
  */
enum OccurrenceOrigin(val dbValue: String):
  case Scheduled extends OccurrenceOrigin("scheduled")
  case Chain extends OccurrenceOrigin("chain")
  case Manual extends OccurrenceOrigin("manual")

object OccurrenceOrigin:
  def fromDbValue(value: String): OccurrenceOrigin =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown occurrence origin '$value'"))

/** One `dose_occurrences` insert: the natural key `(schedule_id, revision, local_date, slot_key)` plus the FSM state
  * ([[Occurrence]]) and the dose snapshot copied from the revision.
  */
final case class NewOccurrence(
    id: UUID,
    accountId: UUID,
    medicationId: UUID,
    scheduleId: Option[UUID],
    revision: Option[Int],
    origin: OccurrenceOrigin,
    localDate: LocalDate,
    localTime: Option[HhMm],
    slotKey: String,
    tz: ZoneId,
    dstKind: DstKind,
    doseSnapshot: DoseSnapshot,
    state: Occurrence
)

object NewOccurrence:

  /** A materialised scheduled occurrence of `revision`: `pending` with the DESIGN.md section 7.1 derived instants.
    */
  def materialized(
      id: UUID,
      accountId: UUID,
      medicationId: UUID,
      scheduleId: UUID,
      revision: Int,
      candidate: OccurrenceCandidate,
      tz: ZoneId,
      policy: ReminderPolicy,
      snapshot: DoseSnapshot
  ): NewOccurrence =
    NewOccurrence(
      id = id,
      accountId = accountId,
      medicationId = medicationId,
      scheduleId = Some(scheduleId),
      revision = Some(revision),
      origin = OccurrenceOrigin.Scheduled,
      localDate = candidate.localDate,
      localTime = Some(candidate.localTime),
      slotKey = candidate.slotKey,
      tz = tz,
      dstKind = candidate.dstKind,
      doseSnapshot = snapshot,
      state = Occurrence.scheduled(candidate.scheduledFor, policy)
    )

/** A `dose_occurrences` row as read back. `state` is the pure FSM projection `Decide.decide` consumes; identity fields,
  * `cancelReason` and the optimistic-lock `version` stay outside it.
  */
final case class StoredOccurrence(
    id: UUID,
    accountId: UUID,
    medicationId: UUID,
    scheduleId: Option[UUID],
    revision: Option[Int],
    origin: OccurrenceOrigin,
    localDate: LocalDate,
    localTime: Option[LocalTime],
    slotKey: String,
    tz: ZoneId,
    dstKind: DstKind,
    cancelReason: Option[CancelReason],
    version: Int,
    state: Occurrence
):
  def scheduledFor: Instant = state.scheduledFor
  def status: OccurrenceStatus = state.status

/** `dose_occurrences` (ADR-004): idempotent inserts (no-target `ON CONFLICT DO NOTHING`, so every unique index —
  * including the cross-revision live-slot one — arbitrates), the edit transaction's `FOR UPDATE` lock, and revision
  * reconciliation. Every query takes `now` as a bind parameter (ADR-011).
  */
trait OccurrenceRepository:

  /** Batch insert with `ON CONFLICT DO NOTHING` (no target); returns how many rows were actually inserted. */
  def insertAll(rows: List[NewOccurrence]): Int

  def get(id: UUID): Option[StoredOccurrence]
  def listBySchedule(scheduleId: UUID): List[StoredOccurrence]

  /** The edit transaction's lock on the schedule's open rows (`FOR UPDATE`, not SKIP LOCKED: a concurrent tick is
    * waited for, DESIGN.md section 7.1).
    */
  def lockOpenRows(scheduleId: UUID): List[StoredOccurrence]

  /** Reconciliation cancel: open rows become `cancelled` with `cancel_reason`, `next_action_at` cleared, epoch and
    * version bumped. Returns the number of rows cancelled.
    */
  def cancel(ids: List[UUID], reason: CancelReason, now: Instant): Int

  /** The `effective_from` default of DESIGN.md section 7.1: whether any of the schedule's slots on `localDate` is
    * already resolved, due (incl. snoozed), or pending past its due window — in which case an edit defaults to the next
    * local midnight instead of "now".
    */
  def hasResolvedOrDueOn(scheduleId: UUID, localDate: LocalDate, now: Instant): Boolean

  /** The reminder loop's claim (DESIGN.md section 7.4, ADR-004): open rows with `next_action_at <= now`, oldest first,
    * `FOR UPDATE SKIP LOCKED` so concurrent loops take disjoint rows. Quarantined rows (`error_count >=
    * [[QuarantineErrorThreshold]]) stay out of the claim set until an operator resets them (M2.4).
    */
  def claimDue(now: Instant, limit: Int): List[StoredOccurrence]

  /** The fenced write of a decided transition (epoch fencing, DESIGN.md sections 7.3/7.4): applies `newState` (with
    * `epoch` and `version` bumped) only when the row still carries the epoch and version the decider read; returns
    * false when a user action landed mid-tick, in which case the tick's writes for the row are skipped entirely.
    */
  def applyTransition(id: UUID, expectedVersion: Int, expectedEpoch: Int, newState: Occurrence, now: Instant): Boolean

  /** Poison-row isolation (ADR-004): increments `error_count` and postpones the row to `retryAt` (`now + 5 min` in the
    * loop). At `error_count >= [[QuarantineErrorThreshold]]` the row leaves the claim set.
    */
  def quarantine(id: UUID, retryAt: Instant, now: Instant): Unit

  /** The next open occurrence of the same schedule after `after` (DESIGN.md section 7.3: bounds snooze and the
    * quiet-defer fallback).
    */
  def nextScheduledAfter(scheduleId: UUID, after: Instant): Option[Instant]

object OccurrenceRepository:

  /** The quarantine threshold of ROADMAP M1.6 (`error_count >= 3`): at or above it a row is no longer claimed. */
  val QuarantineErrorThreshold = 3
