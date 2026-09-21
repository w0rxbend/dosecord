package dosecord.core.chat

import dosecord.contracts.CallbackRef
import dosecord.contracts.Field
import dosecord.contracts.Inbound
import dosecord.contracts.WizardDocument
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.FormRun
import dosecord.core.ports.Tx

import java.util.UUID

object FormRunner:
  /** Reserved answers key holding the form's submit token wire, so a completed run emits a FormSubmitted whose callback
    * is identical to the native modal path's.
    */
  private[chat] val SubmitKey = "_submit"

  enum AnswerOutcome:
    case Invalid(reason: String)
    case Advanced(nextFieldIndex: Int)
    case Completed(submitted: Inbound.FormSubmitted)

/** The mediator FormRunner (DESIGN.md section 4.3, form ladder rung 2): on tiers without native modals the engine asks
  * one field per message, keeps partial answers in `form_runs` and emits exactly one `FormSubmitted` — equal to the
  * modal path's, so the core never learns which happened.
  */
final class FormRunner(codec: CallbackCodec):
  import FormRunner.*

  /** Starts a run for a form-bearing prompt just persisted by the engine. */
  def start(tx: Tx, sessionId: UUID, formId: String, submitWire: String): Unit =
    tx.formRuns.insert(
      FormRun(sessionId, formId, WizardDocument.answersToJson(Map(SubmitKey -> submitWire)), fieldIndex = 0)
    )

  /** Records one text answer for the run's current field; completes the run (and deletes it) after the last field. */
  def accept(tx: Tx, run: FormRun, fields: List[Field], text: String): AnswerOutcome =
    fields.lift(run.fieldIndex) match
      case None        => AnswerOutcome.Invalid(WizardCopy.fieldRequired)
      case Some(field) =>
        val trimmed = text.trim
        if field.required && trimmed.isEmpty then AnswerOutcome.Invalid(WizardCopy.fieldRequired)
        else
          val answers = WizardDocument.answersFromJson(run.answers) + (field.key -> trimmed)
          val nextIndex = run.fieldIndex + 1
          if nextIndex < fields.size then
            tx.formRuns.save(run.copy(answers = WizardDocument.answersToJson(answers), fieldIndex = nextIndex))
            AnswerOutcome.Advanced(nextIndex)
          else
            tx.formRuns.delete(run.sessionId)
            AnswerOutcome.Completed(formSubmitted(run.formId, answers))

  private def formSubmitted(formId: String, answers: Map[String, String]): Inbound.FormSubmitted =
    val wire = answers(SubmitKey)
    val payload = codec
      .decode(wire)
      .getOrElse(throw new IllegalStateException("form run submit token does not decode"))
    Inbound.FormSubmitted(
      formId,
      CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire),
      answers - SubmitKey
    )
