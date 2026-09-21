package dosecord.infra.db

import dosecord.core.ports.NewSchedule
import dosecord.core.ports.ScheduleRepository
import dosecord.core.ports.ScheduleStatus
import dosecord.core.ports.StoredSchedule

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `medication_schedules` (ADR-004): the row lock behind revision application and the materialiser's SKIP
  * LOCKED claim. Every write takes `now` as a bind parameter (ADR-011).
  */
final class PgScheduleRepository(conn: Connection) extends ScheduleRepository:
  private given Connection = conn

  private given RowMapper[StoredSchedule] = rs =>
    StoredSchedule(
      id = rs.uuid("id"),
      medicationId = rs.uuid("medication_id"),
      accountId = rs.uuid("user_id"),
      kind = rs.getString("kind"),
      status = ScheduleStatus.fromDbValue(rs.getString("status")),
      currentRevision = rs.getInt("current_revision"),
      tz = ZoneId.of(rs.getString("tz")),
      tzFollowsUser = rs.getBoolean("tz_follows_user"),
      startDate = rs.localDate("start_date"),
      endDate = rs.optLocalDate("end_date"),
      materializedThrough = rs.optInstant("materialized_through")
    )

  override def insert(row: NewSchedule, now: Instant): Unit =
    sql"""INSERT INTO medication_schedules
            (id, medication_id, user_id, kind, status, current_revision, tz, tz_follows_user,
             start_date, end_date, created_at, updated_at)
          VALUES (${row.id}, ${row.medicationId}, ${row.accountId}, ${row.kind}, 'active', 1,
                  ${row.tz.getId}, ${row.tzFollowsUser}, ${row.startDate}, ${row.endDate}, $now, $now)"""
      .execute()

  override def get(id: UUID): Option[StoredSchedule] =
    sql"SELECT id, medication_id, user_id, kind, status, current_revision, tz, tz_follows_user, start_date, end_date, materialized_through FROM medication_schedules WHERE id = $id"
      .queryOne[StoredSchedule]()

  override def getForUpdate(id: UUID): Option[StoredSchedule] =
    sql"SELECT id, medication_id, user_id, kind, status, current_revision, tz, tz_follows_user, start_date, end_date, materialized_through FROM medication_schedules WHERE id = $id FOR UPDATE"
      .queryOne[StoredSchedule]()

  override def setStatus(id: UUID, status: ScheduleStatus, now: Instant): Unit =
    val updated =
      sql"UPDATE medication_schedules SET status = ${status.dbValue}, updated_at = $now WHERE id = $id".execute()
    require(updated == 1, s"schedule $id vanished before setStatus")

  override def setTimezone(id: UUID, tz: ZoneId, now: Instant): Unit =
    val updated =
      sql"UPDATE medication_schedules SET tz = ${tz.getId}, updated_at = $now WHERE id = $id".execute()
    require(updated == 1, s"schedule $id vanished before setTimezone")

  override def setCurrentRevision(id: UUID, revision: Int, now: Instant): Unit =
    val updated =
      sql"UPDATE medication_schedules SET current_revision = $revision, updated_at = $now WHERE id = $id".execute()
    require(updated == 1, s"schedule $id vanished before setCurrentRevision")

  override def advanceMaterializedThrough(id: UUID, through: Instant, now: Instant): Unit =
    sql"""UPDATE medication_schedules
          SET materialized_through = $through, updated_at = $now
          WHERE id = $id AND (materialized_through IS NULL OR materialized_through < $through)""".execute()

  override def listFollowingForTzChange(accountId: UUID): List[StoredSchedule] =
    sql"""SELECT id, medication_id, user_id, kind, status, current_revision, tz, tz_follows_user, start_date,
                 end_date, materialized_through
          FROM medication_schedules
          WHERE user_id = $accountId AND tz_follows_user AND status <> 'archived'""".query[StoredSchedule]()

  override def claimForMaterialisation(horizonEnd: Instant, limit: Int): List[StoredSchedule] =
    sql"""SELECT id, medication_id, user_id, kind, status, current_revision, tz, tz_follows_user, start_date,
                 end_date, materialized_through
          FROM medication_schedules
          WHERE status = 'active' AND (materialized_through IS NULL OR materialized_through < $horizonEnd)
          ORDER BY materialized_through NULLS FIRST
          LIMIT $limit
          FOR UPDATE SKIP LOCKED""".query[StoredSchedule]()
