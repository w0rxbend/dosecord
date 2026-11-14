package dosegram.infra.db

import dosegram.core.domain.DoseSnapshot
import dosegram.core.domain.ReminderPolicy
import dosegram.core.domain.Rule
import dosegram.core.ports.KeyService
import dosegram.core.ports.NewScheduleRevision
import dosegram.core.ports.ScheduleRevisionRepository
import dosegram.core.ports.StoredScheduleRevision
import upickle.default.read
import upickle.default.write

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `schedule_revisions` (ADR-004): append-only. The jsonb columns round-trip through the contracts/domain
  * `ReadWriter` derivations, so a rule the enum no longer knows fails to decode here rather than being dropped. The
  * dose snapshot names the medication, so it is sealed with the data key of the schedule's account (V10).
  */
final class PgScheduleRevisionRepository(conn: Connection, keys: KeyService) extends ScheduleRevisionRepository:
  import SealedColumns.RevisionSnapshot

  private given Connection = conn

  private val Select =
    """SELECT r.id, r.schedule_id, r.revision, r.effective_from, r.tz, r.rule, r.reminder_policy, r.dose_snapshot_enc,
              r.created_by, r.reason, r.created_at, s.user_id
       FROM schedule_revisions r JOIN medication_schedules s ON s.id = r.schedule_id"""

  private given RowMapper[StoredScheduleRevision] = rs =>
    val id = rs.uuid("id")
    val snapshot = keys.openText(RevisionSnapshot.forAccount(rs.uuid("user_id"), id), rs.getBytes("dose_snapshot_enc"))
    StoredScheduleRevision(
      id = id,
      scheduleId = rs.uuid("schedule_id"),
      revision = rs.getInt("revision"),
      effectiveFrom = rs.instant("effective_from"),
      tz = ZoneId.of(rs.getString("tz")),
      rule = read[Rule](rs.getString("rule")),
      policy = read[ReminderPolicy](rs.getString("reminder_policy")),
      doseSnapshot = read[DoseSnapshot](snapshot),
      createdBy = rs.getString("created_by"),
      reason = rs.optString("reason"),
      createdAt = rs.instant("created_at")
    )

  override def append(row: NewScheduleRevision, now: Instant): Unit =
    val accountId = sql"SELECT user_id FROM medication_schedules WHERE id = ${row.scheduleId}"
      .queryOne[UUID]()
      .getOrElse(throw IllegalStateException(s"schedule ${row.scheduleId} does not exist"))
    val snapshot = keys.sealText(RevisionSnapshot.forAccount(accountId, row.id), write(row.doseSnapshot))
    sql"""INSERT INTO schedule_revisions
            (id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot_enc,
             created_by, reason, created_at)
          VALUES (${row.id}, ${row.scheduleId}, ${row.revision}, ${row.effectiveFrom}, ${row.tz.getId},
                  ${Jsonb(write(row.rule))}, ${Jsonb(write(row.policy))}, $snapshot,
                  ${row.createdBy}, ${row.reason}, $now)""".execute()
    ()

  override def latest(scheduleId: UUID): Option[StoredScheduleRevision] =
    SqlQuery(s"$Select WHERE r.schedule_id = ? ORDER BY r.revision DESC LIMIT 1", Vector[SqlBind](scheduleId))
      .queryOne[StoredScheduleRevision]()

  override def get(scheduleId: UUID, revision: Int): Option[StoredScheduleRevision] =
    SqlQuery(s"$Select WHERE r.schedule_id = ? AND r.revision = ?", Vector[SqlBind](scheduleId, revision))
      .queryOne[StoredScheduleRevision]()

  override def list(scheduleId: UUID): List[StoredScheduleRevision] =
    SqlQuery(s"$Select WHERE r.schedule_id = ? ORDER BY r.revision", Vector[SqlBind](scheduleId))
      .query[StoredScheduleRevision]()
