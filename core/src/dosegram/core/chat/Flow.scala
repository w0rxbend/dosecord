package dosegram.core.chat

import dosegram.contracts.Field
import dosegram.contracts.InboundEvent
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.contracts.RichText
import dosegram.core.ports.Tx

import java.util.UUID

/** The declarative wizard model of DESIGN.md section 4.6: a `Flow` is a map of steps, each step renders a prompt from
  * the accumulated data and accepts one input; the [[WizardEngine]] drives sessions over it. M1.9's add-medication
  * wizard and M0.12d's timezone picker plug in as flows; the engine owns sessions, tokens, Back/Cancel and the sweeper,
  * so flows never touch persistence.
  */

/** What a step accepted from the user, after mediator resolution. */
enum StepInput:
  /** A rendered choice was tapped or its number typed — both resolve to the same key. */
  case Chosen(key: String)

  /** A multi-select choice set was submitted. */
  case ChosenMany(keys: List[String])

  /** Free text on a text step, or one FormRunner field answer handled by the engine. */
  case TextEntered(text: String)

  /** A form submission from a completed FormRunner (M0.12b). */
  case FormAnswered(fields: Map[String, String])

/** Where a step sends the session next. */
enum StepTransition:
  /** Advance to `stepId`, merging `save` into the session data. */
  case Next(stepId: String, save: Map[String, String] = Map.empty)

  /** Flow finished: the engine closes the session and returns `Flow.onComplete`'s reply. */
  case Complete

  /** The step's own Cancel control (e.g. the wizard confirm card's `[Cancel]`): the engine aborts the session exactly
    * like the global `/cancel`, so a mid-flow exit never writes rows.
    */
  case Abort

/** What kind of input a step takes. The engine renders the prompt controls from this and routes inputs accordingly. */
enum StepKind:
  /** Tappable options as (payload key, label), computed from the accumulated data at render time so steps like the
    * timezone picker can group by the current offset; `[Back]`/`[Cancel]` are appended by the engine. `maxSelect > 1`
    * makes it a multi-select picker (M1.9's day picker): on the numbered rung a space/comma reply reaches the step as
    * [[StepInput.TextEntered]].
    */
  case Choices(options: Map[String, String] => List[(String, String)], minSelect: Int = 1, maxSelect: Int = 1)

  /** Free text; Back/Cancel render as a typed hint so bare digits stay wizard input (DESIGN.md section 4.6 step 5). */
  case Text

  /** A form: the mediator FormRunner asks one field per message and yields one FormAnswered. Re-rendered with session
    * data, the fields carry their previous answers as pre-filled values (M1.9 `[Back]` pre-fill).
    */
  case Form(formId: String, title: String, fields: List[Field])

object StepKind:
  /** A static option list (the common case). */
  def choices(options: List[(String, String)]): StepKind =
    StepKind.Choices(_ => options)

final case class Step(
    id: String,
    kind: StepKind,
    /** The prompt body for the accumulated data (engine appends controls / nav hint). */
    render: Map[String, String] => RichText,
    /** The transition for one accepted input; `Left` is the validation failure shown with the re-rendered prompt. */
    accept: (StepInput, Map[String, String]) => Either[String, StepTransition],
    /** Runs inside the engine's transaction when the session enters this step (also on re-entry); the returned pairs
      * merge into the session data before the prompt renders. Steps that must read the database to render — the M1.9
      * wizard's find-or-create offer and its timezone default — compute them here, so `render` stays pure.
      */
    onEnter: (Map[String, String], Option[UUID], Tx) => Map[String, String] = (_, _, _) => Map.empty
)

/** What a flow sees at completion (M0.12d): the triggering event, the stamped principal and the per-event transaction,
  * so a completing flow can write its rows (account create inserts `users`/`platform_identities`/`delivery_channels`)
  * inside the mediator's transaction.
  */
final case class FlowContext(event: InboundEvent, principal: Principal, tx: Tx)

final case class Flow(
    id: String,
    firstStep: String,
    steps: Map[String, Step],
    /** The closing reply for `StepTransition.Complete`, built from the final session data. */
    onComplete: (Map[String, String], FlowContext) => Reply
):
  require(steps.contains(firstStep), s"flow '$id' first step '$firstStep' is not a step")
  require(steps.keySet.forall(k => !k.startsWith("_")), s"flow '$id' step ids must not start with '_'")
