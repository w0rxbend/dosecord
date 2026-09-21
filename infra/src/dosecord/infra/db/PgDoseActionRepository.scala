package dosecord.infra.db

import dosecord.core.domain.Actor
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.DoseActionRepository
import dosecord.core.ports.NewDoseAction
import dosecord.core.ports.StoredDoseAction

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `dose_actions` (R27): append-only — `seq` is assigned as `max(seq) + 1` per occurrence inside the same
  * transaction; the V1 trigger rejects any UPDATE/DELETE (the erasure GUC excepted, M4.4).
  */
final class PgDoseActionRepository(conn: Connection) extends DoseActionRepository:
  private given Connection = conn

  private given RowMapper[StoredDoseAction] = rs =>
    StoredDoseAction(
      id = rs.uuid("id"),
      occurrenceId = rs.uuid("occurrence_id"),
      accountId = rs.uuid("account_id"),
      seq = rs.getInt("seq"),
      action = DoseActionKind.fromDbValue(rs.getString("action")),
      actor = if rs.getString("actor_type") == Actor.User.dbValue then Actor.User else Actor.System,
      occurredAt = rs.instant("occurred_at"),
      recordedAt = rs.instant("recorded_at"),
      priorStatus = OccurrenceStatus.fromDbValue(rs.getString("prior_status")),
      newStatus = OccurrenceStatus.fromDbValue(rs.getString("new_status")),
      effectiveAt = rs.optInstant("effective_at"),
      reasonCode = rs.optString("reason_code"),
      note = rs.optString("note"),
      undoesSeq = rs.optInt("undoes_seq"),
      catchUp = rs.getBoolean("catch_up"),
      correlationId = rs.getString("correlation_id"),
      idempotencyKey = rs.optString("idempotency_key"),
      metadata = rs.getString("metadata")
    )

  override def append(row: NewDoseAction): Unit =
    sql"""INSERT INTO dose_actions
            (id, occurrence_id, account_id, seq, action, actor_type, idempotency_key, occurred_at,
             prior_status, new_status, effective_at, reason_code, note, undoes_seq, catch_up,
             correlation_id, metadata)
          SELECT ${row.id}, ${row.occurrenceId}, ${row.accountId}, COALESCE(MAX(seq), 0) + 1,
                 ${row.action.dbValue}::dose_action, ${row.actor.dbValue}, ${row.idempotencyKey}, ${row.occurredAt},
                 ${row.priorStatus.dbValue}::occ_status, ${row.newStatus.dbValue}::occ_status, ${row.effectiveAt},
                 ${row.reasonCode}, ${row.note}, ${row.undoesSeq}, ${row.catchUp}, ${row.correlationId},
                 ${Jsonb(row.metadata)}
          FROM dose_actions
          WHERE occurrence_id = ${row.occurrenceId}""".execute()

  override def listForOccurrence(occurrenceId: UUID): List[StoredDoseAction] =
    sql"""SELECT id, occurrence_id, account_id, seq, action, actor_type, occurred_at, recorded_at,
                 prior_status, new_status, effective_at, reason_code, note, undoes_seq, catch_up,
                 correlation_id, idempotency_key, metadata
          FROM dose_actions
          WHERE occurrence_id = $occurrenceId
          ORDER BY seq""".query[StoredDoseAction]()
