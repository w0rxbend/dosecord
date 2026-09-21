package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** ROADMAP M1.4b acceptance: `projection == fold(actions)` after any random action sequence. The sequences are driven
  * through `Decide.decide` itself, so the fold is checked against the projection the FSM actually maintains.
  */
class ProjectionFoldProperties extends munit.ScalaCheckSuite:

  import FsmGens.utc

  /** One scripted step. `CorrectRelative` carries a delta applied to the step's `now`, so corrections mostly land in
    * the past (an `effective_at` in the future is a named refusal, not a fold case).
    */
  private enum ScriptEvent:
    case Concrete(event: OccurrenceEvent)
    case CorrectRelative(deltaSeconds: Long)

  private final case class ScriptStep(
      event: ScriptEvent,
      advanceSeconds: Long,
      delivered: Boolean,
      healthyDeltaSeconds: Long
  )

  private val genConcreteEvent: Gen[ScriptEvent] =
    Gen
      .frequency(
        4 -> Gen.const(OccurrenceEvent.Tick),
        3 -> Gen.const(OccurrenceEvent.Taken),
        2 -> Gen.option(Gen.oneOf(SkipReason.values.toList)).map(OccurrenceEvent.Skipped(_)),
        2 -> Gen.choose(1, 240).map(OccurrenceEvent.Snoozed(_)),
        2 -> Gen.const(OccurrenceEvent.Undo),
        1 -> Gen.alphaNumStr.suchThat(_.nonEmpty).map(OccurrenceEvent.NoteAdded(_))
      )
      .map(ScriptEvent.Concrete(_))

  private val genStep: Gen[ScriptStep] =
    for
      event <- Gen.frequency(12 -> genConcreteEvent, 2 -> Gen.choose(-86400L, 60L).map(ScriptEvent.CorrectRelative(_)))
      advance <- Gen.choose(0L, 7200L)
      delivered <- Gen.oneOf(true, false)
      healthyDelta <- Gen.choose(-7200L, 7200L)
    yield ScriptStep(event, advance, delivered, healthyDelta)

  /** The latest user action still eligible for undo, as the M1.10 handler would read it from `dose_actions`: a user
    * taken/skipped/snoozed becomes undoable; an undo or a correction consumes that eligibility (ADR-012, M1.10).
    */
  private def nextUndoable(
      current: Option[LastUserAction],
      action: ActionRowIntent,
      seq: Int
  ): Option[LastUserAction] =
    (action.actor, action.action) match
      case (Actor.User, DoseActionKind.Taken)   => Some(LastUserAction(DoseActionKind.Taken, seq, action.occurredAt))
      case (Actor.User, DoseActionKind.Skipped) => Some(LastUserAction(DoseActionKind.Skipped, seq, action.occurredAt))
      case (Actor.User, DoseActionKind.Snoozed) => Some(LastUserAction(DoseActionKind.Snoozed, seq, action.occurredAt))
      case (_, DoseActionKind.Undone)           => None
      case (_, DoseActionKind.ManuallyCorrected) => None
      case _                                     => current

  property("projection == fold(actions) after any random action sequence"):
    val genScript =
      for
        policy <- FsmGens.genPolicy
        scheduledFor <- FsmGens.genInstant
        steps <- Gen.choose(1, 40)
        script <- Gen.listOfN(steps, genStep)
      yield (policy, scheduledFor, script)
    forAll(genScript) { (policy, scheduledFor, script) =>
      val seed = Occurrence.scheduled(scheduledFor, policy)
      var row = seed
      var now = scheduledFor.minusSeconds(3600L)
      var actions = List.empty[ActionRowIntent]
      var undoable: Option[LastUserAction] = None
      var seq = 0
      script.foreach { step =>
        now = now.plusSeconds(step.advanceSeconds)
        val event =
          step.event match
            case ScriptEvent.Concrete(e)              => e
            case ScriptEvent.CorrectRelative(delta)   => OccurrenceEvent.Corrected(now.plusSeconds(delta))
        val ctx = DecideContext(
          event,
          delivered = step.delivered,
          lastHealthyTick = now.plusSeconds(step.healthyDeltaSeconds),
          nextOccurrenceScheduledFor = Some(scheduledFor.plusSeconds(24L * 3600L)),
          lastUndoableAction = undoable
        )
        val transition = Decide.decide(row, policy, QuietHoursContext.none(utc), now, ctx)
        row = transition.row
        transition.action.foreach { action =>
          seq += 1
          actions = actions :+ action
          undoable = nextUndoable(undoable, action, seq)
          // Stronger than the acceptance: the invariant holds after every prefix, not only at the end.
          assertEquals(
            Projection.fold(actions, seed),
            Projection.of(row),
            s"fold diverged after ${actions.size} actions; last action: $action"
          )
        }
      }
    }
end ProjectionFoldProperties
