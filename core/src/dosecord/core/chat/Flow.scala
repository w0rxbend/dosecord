package dosecord.core.chat

import dosecord.contracts.Field
import dosecord.contracts.Reply
import dosecord.contracts.RichText

/** The declarative wizard model of DESIGN.md section 4.6: a `Flow` is a map of steps, each step renders a prompt from
  * the accumulated data and accepts one input; the [[WizardEngine]] drives sessions over it. M1.9's add-medication
  * wizard and M0.12d's timezone picker plug in as flows; the engine owns sessions, tokens, Back/Cancel and the sweeper,
  * so flows never touch persistence.
  */

/** What a step accepted from the user, after mediator resolution. */
enum StepInput:
  /** A rendered choice was tapped (button/select/numbered/reaction — all resolve to the same key). */
  case Chosen(key: String)

  /** Free text on a text step, or one FormRunner field answer handled by the engine. */
  case TextEntered(text: String)

  /** A form submission — identical whether it came from a native modal or a completed FormRunner (M0.12b). */
  case FormAnswered(fields: Map[String, String])

/** Where a step sends the session next. */
enum StepTransition:
  /** Advance to `stepId`, merging `save` into the session data. */
  case Next(stepId: String, save: Map[String, String] = Map.empty)

  /** Flow finished: the engine closes the session and returns `Flow.onComplete`'s reply. */
  case Complete

/** What kind of input a step takes. The engine renders the prompt controls from this and routes inputs accordingly. */
enum StepKind:
  /** Tappable options as (payload key, label); `[Back]`/`[Cancel]` are appended by the engine. */
  case Choices(options: List[(String, String)])

  /** Free text; Back/Cancel render as a typed hint so bare digits stay wizard input (DESIGN.md section 4.6 step 5). */
  case Text

  /** A form: one native modal where supported, the mediator FormRunner elsewhere; both yield one FormAnswered. */
  case Form(formId: String, title: String, fields: List[Field])

final case class Step(
    id: String,
    kind: StepKind,
    /** The prompt body for the accumulated data (engine appends controls / nav hint). */
    render: Map[String, String] => RichText,
    /** The transition for one accepted input; `Left` is the validation failure shown with the re-rendered prompt. */
    accept: (StepInput, Map[String, String]) => Either[String, StepTransition]
)

final case class Flow(
    id: String,
    firstStep: String,
    steps: Map[String, Step],
    /** The closing reply for `StepTransition.Complete`, built from the final session data. */
    onComplete: Map[String, String] => Reply
):
  require(steps.contains(firstStep), s"flow '$id' first step '$firstStep' is not a step")
  require(steps.keySet.forall(k => !k.startsWith("_")), s"flow '$id' step ids must not start with '_'")
