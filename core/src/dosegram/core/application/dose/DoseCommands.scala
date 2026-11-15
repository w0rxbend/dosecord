package dosegram.core.application.dose

import dosegram.contracts.AccountId
import dosegram.contracts.Event
import dosegram.core.domain.Actor
import dosegram.core.domain.Decide
import dosegram.core.domain.DecideContext
import dosegram.core.domain.DispatchIntent
import dosegram.core.domain.Feedback
import dosegram.core.domain.LastUserAction
import dosegram.core.domain.OccurrenceEvent
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Transition
import dosegram.core.domain.copy.DoseActionKind
import dosegram.core.ports.Clock
import dosegram.core.ports.NewDoseAction
import dosegram.core.ports.OccurrenceOrigin
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx
import dosegram.core.scheduling.FinalizeDispatch

import java.time.Instant
import java.util.UUID

/** Who answered a dose, for its `dose_actions` row (R27): the correlation id, which is also the row's idempotency key,
  * the surface (`telegram`, `console` or [[DoseCommands.MiniAppVendor]]) and, from the chat, the identity and the
  * message the tap came from.
  */
final case class DoseAttribution(
    correlationId: String,
    vendor: String,
    platformIdentityId: Option[UUID] = None,
    platformMessageId: Option[String] = None
)

/** What one answer did: a recorded transition with the domain events it owes, or nothing, with the reason. */
enum DoseOutcome:
  case Applied(transition: Transition, events: List[Event])
  case Unchanged(feedback: Feedback)

/** One user answer to a locked occurrence (Taken, Skip, Snooze, Undo, a correction), the same use case for the chat and
  * the Mini App (ROADMAP G3.7, ADR-012). The transition comes from [[Decide.decide]]; this writes the fenced row, the
  * action rows, and the finalize ops for every recorded handle of the occurrence, so a reminder answered in the app is
  * closed in the chat through the outbox. The domain events are returned for the caller to append on the same
  * transaction: the chat's mediator appends a reply's events, the API appends its own.
  */
final class DoseCommands(clock: Clock):
  import DoseCommands.*

  def answer(tx: Tx, occ: StoredOccurrence, userEvent: OccurrenceEvent, by: DoseAttribution): DoseOutcome =
    val now = clock.now()
    val (policy, quiet) = tx.policies.forOccurrence(occ)
    val ctx = DecideContext(
      event = userEvent,
      delivered = tx.outbox.deliveredFor(occ.id),
      nextOccurrenceScheduledFor = occ.scheduleId.flatMap(tx.occurrences.nextScheduledAfter(_, occ.state.scheduledFor)),
      lastUndoableAction = lastUndoableAction(tx, occ.id),
      manualLog = occ.origin == OccurrenceOrigin.Manual
    )
    val transition = Decide.decide(occ.state, policy, quiet, now, ctx)
    if transition.row == occ.state then DoseOutcome.Unchanged(transition.feedback)
    else
      if !tx.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, transition.row, now) then
        // The row was locked FOR UPDATE by the caller, so the guarded write cannot lose a race; failing the transaction
        // is the honest failure.
        throw new IllegalStateException(s"occurrence ${occ.id} changed while locked for a user action")
      (transition.action.toList ++ transition.additionalActions).foreach: intent =>
        tx.doseActions.append(
          NewDoseAction
            .from(intent, occ.id, occ.accountId, by.correlationId)
            .copy(
              idempotencyKey = Some(by.correlationId),
              vendor = Some(by.vendor),
              platformIdentityId = by.platformIdentityId,
              platformMessageId = by.platformMessageId
            )
        )
      transition.dispatches.foreach:
        case DispatchIntent.FinalizeControls(reason) =>
          FinalizeDispatch.enqueue(tx, occ.id, occ.accountId, transition.row.epoch, reason, now)
        case _ => () // a user event never sends a reminder of its own
      DoseOutcome.Applied(transition, takenEvent(occ, userEvent, transition, now))

  /** Where the occurrence was last answered by its user: the surface of the latest user action row. */
  def answeredBy(tx: Tx, occurrenceId: UUID): Option[String] =
    tx.doseActions.listForOccurrence(occurrenceId).reverse.find(_.actor == Actor.User).flatMap(_.vendor)

  /** Exactly one `intake_taken.v1` per transition to Taken recorded by a user event (R30); corrections carry the
    * explicit `effective_at` they recorded.
    */
  private def takenEvent(
      occ: StoredOccurrence,
      userEvent: OccurrenceEvent,
      transition: Transition,
      now: Instant
  ): List[Event] =
    val recordsTaken = userEvent match
      case OccurrenceEvent.Taken        => true
      case OccurrenceEvent.Corrected(_) => true
      case _                            => false
    if recordsTaken && transition.row.status == OccurrenceStatus.Taken then
      val effectiveAt = transition.row.effectiveAt.getOrElse(now)
      List(
        Event.IntakeTaken(
          AccountId(occ.accountId),
          occ.id,
          effectiveAt,
          takenLate = effectiveAt.isAfter(occ.state.dueWindowEnd)
        )
      )
    else Nil

  /** The latest user action row still eligible for undo (taken/skipped/snoozed, not already undone), read from
    * `dose_actions` for the FSM's undo arm (ADR-012).
    */
  private def lastUndoableAction(tx: Tx, occurrenceId: UUID): Option[LastUserAction] =
    val actions = tx.doseActions.listForOccurrence(occurrenceId)
    val undoneSeqs =
      actions.flatMap(action => if action.action == DoseActionKind.Undone then action.undoesSeq.toList else Nil).toSet
    actions.reverse
      .find(action =>
        action.actor == Actor.User && UndoableKinds.contains(action.action) && !undoneSeqs.contains(action.seq)
      )
      .map(action => LastUserAction(action.action, action.seq, action.occurredAt))

object DoseCommands:

  /** The surface name of an answer given in the Mini App. */
  val MiniAppVendor = "miniapp"

  private val UndoableKinds: Set[DoseActionKind] =
    Set(DoseActionKind.Taken, DoseActionKind.Skipped, DoseActionKind.Snoozed)
