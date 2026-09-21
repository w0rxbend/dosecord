package dosecord.infra.db

import dosecord.core.ports.FormRun
import dosecord.core.ports.FormRunRepository

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

final class PgFormRunRepository(conn: Connection) extends FormRunRepository:
  private given Connection = conn

  private given RowMapper[FormRun] = rs =>
    FormRun(
      sessionId = rs.uuid("session_id"),
      formId = rs.getString("form_id"),
      answers = rs.getString("answers"),
      fieldIndex = rs.getInt("field_index")
    )

  override def insert(run: FormRun): Unit =
    sql"""INSERT INTO form_runs (session_id, form_id, answers, field_index)
          VALUES (${run.sessionId}, ${run.formId}, ${Jsonb(run.answers)}, ${run.fieldIndex})""".execute()

  override def loadForUpdate(sessionId: UUID): Option[FormRun] =
    sql"""SELECT session_id, form_id, answers, field_index
          FROM form_runs WHERE session_id = $sessionId FOR UPDATE""".queryOne[FormRun]()

  override def save(run: FormRun): Unit =
    sql"""UPDATE form_runs SET answers = ${Jsonb(run.answers)}, field_index = ${run.fieldIndex}
          WHERE session_id = ${run.sessionId}""".execute()

  override def delete(sessionId: UUID): Unit =
    sql"DELETE FROM form_runs WHERE session_id = $sessionId".execute()
