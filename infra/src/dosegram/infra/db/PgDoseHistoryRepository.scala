package dosegram.infra.db

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.insights.DoseRecord
import dosegram.core.ports.DoseHistoryRepository
import dosegram.core.ports.OccurrenceOrigin

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** The read query behind the medication statistics. It returns rows only: every count and every rate is computed by the
  * pure functions of `core.domain.insights`, so the query and the oracle cannot drift (VISUALS VA-ME-01).
  *
  * `corrected` is "the last action that set the outcome was `manually_corrected`" (ADR-012): such a dose keeps its
  * place in adherence and leaves the delay statistics.
  */
final class PgDoseHistoryRepository(conn: Connection) extends DoseHistoryRepository:
  private given Connection = conn

  private given RowMapper[DoseRecord] = rs =>
    DoseRecord(
      medicationId = rs.uuid("medication_id"),
      input = AdherenceInput(
        scheduledFor = rs.instant("scheduled_for"),
        status = OccurrenceStatus.fromDbValue(rs.getString("status")),
        effectiveAt = rs.optInstant("effective_at"),
        resolvedAt = rs.optInstant("resolved_at"),
        dueWindowEnd = rs.instant("due_window_end"),
        manual = rs.getString("origin") == OccurrenceOrigin.Manual.dbValue,
        corrected = rs.getBoolean("corrected")
      )
    )

  override def attributableBetween(accountId: UUID, from: Instant, until: Instant): List[DoseRecord] =
    sql"""SELECT o.medication_id, o.scheduled_for, o.status, o.effective_at,
                 COALESCE(o.skipped_at, o.missed_at) AS resolved_at, o.due_window_end, o.origin,
                 COALESCE((SELECT a.action = 'manually_corrected'
                           FROM dose_actions a
                           WHERE a.occurrence_id = o.id
                             AND a.action IN ('taken', 'skipped', 'auto_marked_missed', 'marked_unknown',
                                              'manually_corrected', 'undone')
                           ORDER BY a.seq DESC
                           LIMIT 1), false) AS corrected
          FROM dose_occurrences o
          WHERE o.account_id = $accountId
            AND ((o.scheduled_for >= $from AND o.scheduled_for < $until)
              OR (o.effective_at >= $from AND o.effective_at < $until)
              OR (o.skipped_at >= $from AND o.skipped_at < $until)
              OR (o.missed_at >= $from AND o.missed_at < $until))
          ORDER BY o.scheduled_for, o.id""".query[DoseRecord]()
