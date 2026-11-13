package dosegram.infra.db

import dosegram.core.domain.QuietHours
import dosegram.core.domain.QuietHoursContext
import dosegram.core.domain.ReminderPolicy
import dosegram.core.ports.PolicyRepository
import dosegram.core.ports.StoredOccurrence
import upickle.default.read

import java.sql.Connection
import scala.language.implicitConversions

/** Postgres decision-context read (DESIGN.md section 7.4's `tx.policies.forOccurrence`): the reminder policy of the
  * occurrence's materialising revision, and the account's quiet hours (`users.quiet_hours`) interpreted in the
  * occurrence's zone.
  */
final class PgPolicyRepository(conn: Connection) extends PolicyRepository:
  private given Connection = conn
  import PgPolicyRepository.given

  override def forOccurrence(occ: StoredOccurrence): (ReminderPolicy, QuietHoursContext) =
    val policy = (occ.scheduleId, occ.revision) match
      case (Some(scheduleId), Some(revision)) =>
        sql"""SELECT reminder_policy FROM schedule_revisions
              WHERE schedule_id = $scheduleId AND revision = $revision"""
          .queryOne[Jsonb]()
          .map(json => read[ReminderPolicy](json.value))
          .getOrElse(
            throw new NoSuchElementException(
              s"occurrence ${occ.id} references missing revision $revision of schedule $scheduleId"
            )
          )
      case _ => ReminderPolicy.Default // manual rows (M1.10) carry no revision
    val quiet =
      sql"SELECT quiet_hours FROM users WHERE id = ${occ.accountId}"
        .queryOne[Option[Jsonb]]()
        .flatten
        .map(json => read[QuietHours](json.value))
    (policy, QuietHoursContext(quiet, occ.tz))

object PgPolicyRepository:
  given RowMapper[Option[Jsonb]] = rs => Option(rs.getString(1)).map(Jsonb(_))
