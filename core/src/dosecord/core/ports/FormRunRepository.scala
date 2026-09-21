package dosecord.core.ports

import java.util.UUID

/** Row of `form_runs` (DESIGN.md section 4.3): one in-flight FormRunner per conversation session. `answers` is a raw
  * JSON document keyed by field key.
  */
final case class FormRun(
    sessionId: UUID,
    formId: String,
    answers: String,
    fieldIndex: Int
)

trait FormRunRepository:
  def insert(run: FormRun): Unit

  /** Locks the row until the surrounding transaction ends. */
  def loadForUpdate(sessionId: UUID): Option[FormRun]

  def save(run: FormRun): Unit
  def delete(sessionId: UUID): Unit
