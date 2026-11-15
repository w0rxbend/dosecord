package dosegram.api

import dosegram.api.ApiJson.jsonBody
import dosegram.contracts.AccountId
import dosegram.contracts.Actor
import dosegram.contracts.Event
import dosegram.core.application.dose.DoseAttribution
import dosegram.core.application.dose.DoseCommands
import dosegram.core.application.dose.DoseOutcome
import dosegram.core.domain.Feedback
import dosegram.core.domain.OccurrenceEvent
import dosegram.core.domain.Refusal
import dosegram.core.ports.Clock
import dosegram.core.ports.NewDomainEvent
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import sttp.tapir.*

import java.util.UUID

/** The intake-log command of ARCHITECTURE section 9.5: `POST /occurrences/{id}/actions` (I). */
object DoseActionEndpoints:
  private val id = path[UUID]("id")
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID made when the user taps; required (API-16)")

  val actOnOccurrence =
    Endpoints.secured.post
      .in("occurrences" / id / "actions")
      .in(idempotencyKey)
      .in(jsonBody[OccurrenceActionInput])
      .out(jsonBody[OccurrenceActionResult])
      .name("actOnOccurrence")
      .description(
        "Taken, skipped, snoozed or undo, through the use case the chat's buttons use; the reminder in the chat is " +
          "closed through the outbox. A dose answered elsewhere first answers `already_resolved` with its state."
      )

  val documented: List[AnyEndpoint] = List(actOnOccurrence)

/** Answers to doses from the Mini App (ROADMAP G3.7, MINI_APP MA-TODAY-2 and MA-TODAY-3): the occurrence is locked on
  * the session's account, so another account's identifier is not found (API-11), and the answer goes through
  * [[DoseCommands]], the chat's own use case, with the request's idempotency key as its correlation id.
  */
final class DoseActions(uow: UnitOfWork, clock: Clock):
  import DoseActions.*

  private val commands = DoseCommands(clock)

  def act(
      accountId: UUID,
      occurrenceId: UUID,
      key: UUID,
      input: OccurrenceActionInput
  ): Either[ApiFailure, OccurrenceActionResult] =
    eventOf(input).flatMap: userEvent =>
      uow.transactionAs(accountId): tx =>
        tx.occurrences.lockById(occurrenceId).filter(_.accountId == accountId) match
          case None      => Left(ApiFailure.notFound())
          case Some(occ) => Right(answer(tx, occ, userEvent, key))

  private def answer(tx: Tx, occ: StoredOccurrence, userEvent: OccurrenceEvent, key: UUID): OccurrenceActionResult =
    val correlationId = s"${DoseCommands.MiniAppVendor}:$key"
    commands.answer(tx, occ, userEvent, DoseAttribution(correlationId, DoseCommands.MiniAppVendor)) match
      case DoseOutcome.Applied(_, events) =>
        events.foreach(append(tx, occ.accountId, correlationId, _))
        result(tx, occ.id, Recorded)
      case DoseOutcome.Unchanged(feedback) if answeredElsewhere(occ, feedback) =>
        result(tx, occ.id, AlreadyResolved).copy(resolvedIn = commands.answeredBy(tx, occ.id).map(surfaceOf))
      case DoseOutcome.Unchanged(Feedback.Refused(reason)) =>
        result(tx, occ.id, Refused).copy(refusal = Some(refusalCode(reason)))
      case DoseOutcome.Unchanged(_) => result(tx, occ.id, Refused)

  private def result(tx: Tx, occurrenceId: UUID, outcome: String): OccurrenceActionResult =
    val row =
      tx.occurrences.lockById(occurrenceId).getOrElse(throw IllegalStateException("answered occurrence vanished"))
    OccurrenceActionResult(Rendering.answerable(tx, row, clock.now()), outcome)

  /** The intake event on the request's transaction, as the chat's mediator appends a reply's events (ADR-003). */
  private def append(tx: Tx, accountId: UUID, correlationId: String, event: Event): Unit =
    val actor = Actor(DoseCommands.MiniAppVendor, accountId.toString, None, Some(AccountId(accountId)))
    val now = clock.now()
    val envelope = Event.toEnvelopeJson(event, actor, now, Some(correlationId), None)
    tx.domainEvents.append(
      NewDomainEvent(
        id = envelope.id.uuid,
        eventType = Event.messageType(event),
        source = Event.sourceOf(event).value,
        subject = Some(actor.subject),
        accountId = Some(accountId),
        correlationId = Some(correlationId),
        causationId = None,
        actorJson = envelope.actorJson,
        occurredAt = now,
        dataJson = envelope.envelopeJson
      )
    )

object DoseActions:
  private val Recorded = "recorded"
  private val AlreadyResolved = "already_resolved"
  private val Refused = "refused"

  private def eventOf(input: OccurrenceActionInput): Either[ApiFailure, OccurrenceEvent] =
    (input.kind, input.minutes) match
      case ("taken", None)                           => Right(OccurrenceEvent.Taken)
      case ("skipped", None)                         => Right(OccurrenceEvent.Skipped(reason = None))
      case ("undo", None)                            => Right(OccurrenceEvent.Undo)
      case ("snoozed", Some(minutes)) if minutes > 0 => Right(OccurrenceEvent.Snoozed(minutes))
      case ("snoozed", _)                            => Left(ApiFailure.invalid("minutes", "invalid"))
      case ("taken" | "skipped" | "undo", Some(_))   => Left(ApiFailure.invalid("minutes", "not_allowed"))
      case _                                         => Left(ApiFailure.invalid("kind", "invalid"))

  /** A second answer to a dose its user resolved first, here or in the chat: Taken on a taken dose, Skip on a taken
    * one, Taken on a skipped one (which the chat turns into its correction prompt), Snooze on either.
    */
  private def answeredElsewhere(occ: StoredOccurrence, feedback: Feedback): Boolean = feedback match
    case Feedback.AlreadyRecorded(_)                => true
    case Feedback.Refused(Refusal.AlreadyResolved)  => true
    case Feedback.Refused(Refusal.CorrectionPrompt) => !occ.status.isOpen
    case Feedback.Refused(Refusal.SnoozeNotAllowed) => !occ.status.isOpen
    case _                                          => false

  private def surfaceOf(vendor: String): String = if vendor == DoseCommands.MiniAppVendor then "app" else "chat"

  private def refusalCode(reason: Refusal): String = reason match
    case Refusal.NothingToUndo                => "nothing_to_undo"
    case Refusal.UndoWindowPassed             => "undo_window_passed"
    case Refusal.UndoWindowPassedOfferCorrect => "undo_window_passed"
    case Refusal.SnoozeLimitReached           => "snooze_limit_reached"
    case Refusal.SnoozePastBound              => "snooze_past_bound"
    case Refusal.SnoozeNotAllowed             => "snooze_not_allowed"
    case Refusal.CorrectionPrompt             => "correction_needed"
    case Refusal.CorrectOnOpenRow             => "correction_needed"
    case Refusal.AlreadyResolved              => "already_resolved"
    case Refusal.InvalidEffectiveAt           => "invalid_time"
    case Refusal.RowCancelled                 => "not_scheduled"
