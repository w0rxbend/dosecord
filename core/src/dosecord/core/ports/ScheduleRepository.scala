package dosecord.core.ports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The `medication_schedules.status` CHECK values (ADR-004: pause/resume/archive are revisions; the status is the
  * materialiser's claim filter and the list-view mirror).
  */
enum ScheduleStatus(val dbValue: String):
  case Active extends ScheduleStatus("active")
  case Paused extends ScheduleStatus("paused")
  case Archived extends ScheduleStatus("archived")

object ScheduleStatus:
  def fromDbValue(value: String): ScheduleStatus =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown schedule status '$value'"))

/** What a schedule create writes into `medication_schedules`. `current_revision` starts at 1, `status` at `active`,
  * `materialized_through` at NULL.
  */
final case class NewSchedule(
    id: UUID,
    medicationId: UUID,
    accountId: UUID,
    kind: String,
    tz: ZoneId,
    tzFollowsUser: Boolean,
    startDate: LocalDate,
    endDate: Option[LocalDate] = None
)

final case class StoredSchedule(
    id: UUID,
    medicationId: UUID,
    accountId: UUID,
    kind: String,
    status: ScheduleStatus,
    currentRevision: Int,
    tz: ZoneId,
    tzFollowsUser: Boolean,
    startDate: LocalDate,
    endDate: Option[LocalDate],
    materializedThrough: Option[Instant]
)

/** `medication_schedules` (ADR-004). Every write takes `now` as a bind parameter (ADR-011).
  */
trait ScheduleRepository:
  def insert(row: NewSchedule, now: Instant): Unit
  def get(id: UUID): Option[StoredSchedule]

  /** Row lock for the edit transaction (DESIGN.md section 7.1: edits lock the schedule's open rows `FOR UPDATE`, not
    * SKIP LOCKED, so a concurrent tick is waited for).
    */
  def getForUpdate(id: UUID): Option[StoredSchedule]

  def setStatus(id: UUID, status: ScheduleStatus, now: Instant): Unit
  def setTimezone(id: UUID, tz: ZoneId, now: Instant): Unit
  def setCurrentRevision(id: UUID, revision: Int, now: Instant): Unit

  /** Advances `materialized_through` monotonically (never backwards, so a stale materialiser cannot rewind the lag
    * alert of DESIGN.md section 7.2).
    */
  def advanceMaterializedThrough(id: UUID, through: Instant, now: Instant): Unit

  /** Schedules that follow the user's timezone (`tz_follows_user`, R10) and are not archived: active ones get a
    * tz-change revision, paused ones only the row update (their next resume picks the zone up).
    */
  def listFollowingForTzChange(accountId: UUID): List[StoredSchedule]

  /** The materialiser's claim: active schedules whose materialised window ends before `horizonEnd` (`FOR UPDATE SKIP
    * LOCKED`, so concurrent materialisers take disjoint schedules).
    */
  def claimForMaterialisation(horizonEnd: Instant, limit: Int): List[StoredSchedule]

  /** Every schedule of one medication (M1.9's find-or-create update path and the menu's pause/resume/archive toggle;
    * the wizard creates exactly one schedule per medication).
    */
  def listForMedication(medicationId: UUID): List[StoredSchedule]
