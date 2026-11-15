package dosegram.infra.db

import dosegram.core.ports.FormRun
import dosegram.core.ports.FormRunRepository
import dosegram.core.ports.KeyService

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** `form_runs`. The answers of an open form are sealed with the transport service key (V19, ROADMAP G2.4b): a form runs
  * inside a session, which exists before the account does.
  */
final class PgFormRunRepository(conn: Connection, keys: KeyService) extends FormRunRepository:
  private given Connection = conn
  import SealedColumns.FormAnswers

  private given RowMapper[FormRun] = rs =>
    val sessionId = rs.uuid("session_id")
    val answers = Option(rs.getBytes("answers_enc"))
      .map(keys.openText(FormAnswers.forService(sessionId), _))
      .getOrElse(rs.getString("answers"))
    FormRun(
      sessionId = sessionId,
      formId = rs.getString("form_id"),
      answers = answers,
      fieldIndex = rs.getInt("field_index")
    )

  private def sealedAnswers(run: FormRun): Array[Byte] =
    keys.sealText(FormAnswers.forService(run.sessionId), run.answers)

  override def insert(run: FormRun): Unit =
    sql"""INSERT INTO form_runs (session_id, form_id, answers_enc, field_index)
          VALUES (${run.sessionId}, ${run.formId}, ${sealedAnswers(run)}, ${run.fieldIndex})""".execute()

  override def loadForUpdate(sessionId: UUID): Option[FormRun] =
    sql"""SELECT session_id, form_id, answers, answers_enc, field_index
          FROM form_runs WHERE session_id = $sessionId FOR UPDATE""".queryOne[FormRun]()

  override def save(run: FormRun): Unit =
    sql"""UPDATE form_runs SET answers = NULL, answers_enc = ${sealedAnswers(run)}, field_index = ${run.fieldIndex}
          WHERE session_id = ${run.sessionId}""".execute()

  override def delete(sessionId: UUID): Unit =
    sql"DELETE FROM form_runs WHERE session_id = $sessionId".execute()
