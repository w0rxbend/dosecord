package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.mood.MoodRecorded
import dosegram.core.application.mood.MoodRefusal
import dosegram.core.application.mood.MoodService
import dosegram.core.application.mood.SetEnergy
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.StatusCards

import java.util.UUID

/** The mood check-in in chat (BOT_UX section 11.4): one tap records, the answer is the mood image with the labels and
  * the time as its caption, and energy is an optional second step. The answer has the same shape for every value.
  */
private[capture] final class MoodChat(mood: MoodService, cards: StatusCards, tokens: Tokens):

  def prompt(turn: Turn): Reply =
    val steps = Valence.steps.map: step =>
      Choice.callback(step.step.toString, tokens.direct(CaptureActions.MoodCheckIn, turn.accountId, step.step.toLong))
    turn.show(turn.message("mood.prompt", List(CheckInCopy.prompt, CheckInCopy.scale), List(steps)))

  /** Records the step. With an image the answer is a new photo message, because a text message cannot become one;
    * without an image the confirmation takes the place of the prompt.
    */
  def checkIn(turn: Turn, valence: Valence): Reply =
    record(turn, valence, turn.clientKey): recorded =>
      val confirmation = turn.message("mood.recorded", List(captionOf(turn, recorded.checkin)), followUpRows(recorded))
      cards.mood(MoodCardView(valence, None), turn.look) match
        case Some(attachment) => Reply(followUps = List(confirmation.copy(attachment = Some(attachment))))
        case None             => turn.show(confirmation)

  /** A tap on a prompt (owner decision 2026-11-13: one prompt, one check-in). The check-in's key is the prompt message,
    * so a later, racing or post-restart tap on it replays the first check-in: it records nothing and answers "Already
    * recorded at 21:04". The first tap edits the prompt into the recorded level with its 1 to 5 buttons gone; with an
    * image, the image follows as its own message and carries Add energy and Undo.
    */
  def checkInFromPrompt(turn: Turn, valence: Valence): Reply =
    turn.source match
      case None         => checkIn(turn, valence)
      case Some(prompt) =>
        val key = s"mood.prompt:${prompt.vendor}:${prompt.chatId}:${prompt.messageId}"
        record(turn, valence, key): recorded =>
          val caption = captionOf(turn, recorded.checkin)
          cards.mood(MoodCardView(valence, None), turn.look) match
            case None             => turn.show(turn.message("mood.recorded", List(caption), followUpRows(recorded)))
            case Some(attachment) =>
              val photo = turn.message("mood.recorded", List(caption), followUpRows(recorded))
              turn
                .show(turn.message("mood.prompt.recorded", List(caption), Nil))
                .copy(followUps = List(photo.copy(attachment = Some(attachment))))

  private def record(turn: Turn, valence: Valence, key: String)(reply: MoodRecorded => Reply): Reply =
    val command = CheckIn(turn.accountId, valence, None, Nil, EntryDay.Today, EntrySource.Bot, key)
    answered(turn, mood.checkIn(turn.tx, command)): recorded =>
      if recorded.replayed && key != turn.clientKey then
        turn.toast(CheckInCopy.alreadyRecorded(turn.localTime(recorded.checkin.occurredAt)))
      else reply(recorded)

  /** "Add energy": value 0 asks, 1 to 5 answer. The caption carries the energy label; the picture is not replaced. */
  def energyStep(turn: Turn, checkinId: UUID, value: Long): Reply =
    if value == AskEnergy then turn.show(energyAsk(turn, checkinId))
    else
      Energy.of(value.toInt) match
        case None         => turn.toast(ReminderCopy.staleControlToast)
        case Some(energy) =>
          answered(turn, mood.setEnergy(turn.tx, SetEnergy(turn.accountId, checkinId, energy, turn.clientKey))):
            recorded =>
              val rows = List(List(undoButton(recorded.checkin.id)))
              turn.show(turn.message("mood.energy", List(captionOf(turn, recorded.checkin)), rows))

  def undo(turn: Turn, checkinId: UUID): Reply =
    answered(turn, mood.undo(turn.tx, turn.accountId, checkinId)): _ =>
      turn.show(turn.message("mood.undone", List(CheckInCopy.undone), Nil))

  private def energyAsk(turn: Turn, checkinId: UUID) =
    val steps = Energy.steps.map: step =>
      Choice.callback(step.step.toString, tokens.direct(CaptureActions.MoodEnergy, checkinId, step.step.toLong))
    turn.message("mood.energy.ask", List(CheckInCopy.energyAsk, CheckInCopy.energyScale), List(steps))

  private def followUpRows(recorded: MoodRecorded): List[List[Choice]] =
    val checkinId = recorded.checkin.id
    val addEnergy =
      Choice.callback(CheckInCopy.AddEnergy, tokens.direct(CaptureActions.MoodEnergy, checkinId, AskEnergy))
    List(List(addEnergy, undoButton(checkinId)))

  private def undoButton(checkinId: UUID): Choice =
    Choice.callback(Labels.Undo, tokens.direct(CaptureActions.MoodUndo, checkinId, 0))

  private def captionOf(turn: Turn, checkin: MoodCheckin): String =
    CheckInCopy.recorded(checkin.valence, checkin.energy, turn.localTime(checkin.occurredAt))

  private def answered[A](turn: Turn, outcome: Either[MoodRefusal, A])(reply: A => Reply): Reply =
    outcome match
      case Right(value)  => reply(value)
      case Left(refusal) => turn.toast(refusalText(refusal))

  private def refusalText(refusal: MoodRefusal): String =
    refusal match
      case MoodRefusal.UnknownCheckin   => CheckInCopy.unknownCheckin
      case MoodRefusal.UndoWindowPassed => CheckInCopy.undoWindowPassed
      // The consent was withdrawn, or the account is gone, after the button was sent; the chat sends no tags.
      case MoodRefusal.ConsentMissing | MoodRefusal.UnknownAccount | MoodRefusal.TooManyTags(_) =>
        ReminderCopy.staleControlToast

  private val AskEnergy = 0L
