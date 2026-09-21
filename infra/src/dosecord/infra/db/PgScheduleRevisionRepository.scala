package dosecord.infra.db

import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.ports.NewScheduleRevision
import dosecord.core.ports.ScheduleRevisionRepository
import dosecord.core.ports.StoredScheduleRevision
import upickle.default.read
import upickle.default.write

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `schedule_revisions` (ADR-004): append-only. The jsonb columns round-trip through the contracts/domain
  * `ReadWriter` derivations, so a rule the enum no longer knows fails to decode here rather than being dropped.
  */
final class PgScheduleRevisionRepository(conn: Connection) extends ScheduleRevisionRepository:
  private given Connection = conn

  private given RowMapper[StoredScheduleRevision] = rs =>
    StoredScheduleRevision(
      id = rs.uuid("id"),
      scheduleId = rs.uuid("schedule_id"),
      revision = rs.getInt("revision"),
      effectiveFrom = rs.instant("effective_from"),
      tz = ZoneId.of(rs.getString("tz")),
      rule = read[Rule](rs.getString("rule")),
      policy = read[ReminderPolicy](rs.getString("reminder_policy")),
      doseSnapshot = read[DoseSnapshot](rs.getString("dose_snapshot")),
      createdBy = rs.getString("created_by"),
      reason = rs.optString("reason"),
      createdAt = rs.instant("created_at")
    )

  override def append(row: NewScheduleRevision, now: Instant): Unit =
    sql"""INSERT INTO schedule_revisions
            (id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot,
             created_by, reason, created_at)
          VALUES (${row.id}, ${row.scheduleId}, ${row.revision}, ${row.effectiveFrom}, ${row.tz.getId},
                  ${Jsonb(write(row.rule))}, ${Jsonb(write(row.policy))}, ${Jsonb(write(row.doseSnapshot))},
                  ${row.createdBy}, ${row.reason}, $now)""".execute()

  override def latest(scheduleId: UUID): Option[StoredScheduleRevision] =
    sql"""SELECT id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot,
                 created_by, reason, created_at
          FROM schedule_revisions
          WHERE schedule_id = $scheduleId
          ORDER BY revision DESC
          LIMIT 1""".queryOne[StoredScheduleRevision]()

  override def get(scheduleId: UUID, revision: Int): Option[StoredScheduleRevision] =
    sql"""SELECT id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot,
                 created_by, reason, created_at
          FROM schedule_revisions
          WHERE schedule_id = $scheduleId AND revision = $revision""".queryOne[StoredScheduleRevision]()

  override def list(scheduleId: UUID): List[StoredScheduleRevision] =
    sql"""SELECT id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot,
                 created_by, reason, created_at
          FROM schedule_revisions
          WHERE schedule_id = $scheduleId
          ORDER BY revision""".query[StoredScheduleRevision]()
