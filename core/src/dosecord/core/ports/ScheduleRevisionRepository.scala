package dosecord.core.ports

import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What one revision application appends to `schedule_revisions` (append-only, ADR-004). `effectiveFrom` is the instant
  * from which the revision governs; the evaluator interprets it as a local date in `tz`.
  */
final case class NewScheduleRevision(
    id: UUID,
    scheduleId: UUID,
    revision: Int,
    effectiveFrom: Instant,
    tz: ZoneId,
    rule: Rule,
    policy: ReminderPolicy,
    doseSnapshot: DoseSnapshot,
    createdBy: String,
    reason: Option[String]
)

final case class StoredScheduleRevision(
    id: UUID,
    scheduleId: UUID,
    revision: Int,
    effectiveFrom: Instant,
    tz: ZoneId,
    rule: Rule,
    policy: ReminderPolicy,
    doseSnapshot: DoseSnapshot,
    createdBy: String,
    reason: Option[String],
    createdAt: Instant
)

/** `schedule_revisions` (ADR-004): append-only; `UNIQUE (schedule_id, revision)` arbitrates the revision numbers, which
  * the lifecycle assigns under the schedule row lock.
  */
trait ScheduleRevisionRepository:
  def append(row: NewScheduleRevision, now: Instant): Unit

  /** The current revision (highest number). */
  def latest(scheduleId: UUID): Option[StoredScheduleRevision]

  def get(scheduleId: UUID, revision: Int): Option[StoredScheduleRevision]
  def list(scheduleId: UUID): List[StoredScheduleRevision]
