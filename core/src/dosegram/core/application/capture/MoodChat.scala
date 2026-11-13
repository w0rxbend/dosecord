package dosegram.core.application.capture

import dosegram.contracts.AttachmentRef
import dosegram.contracts.Choice
import dosegram.contracts.MessageHandle
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
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.StatusCards
import dosegram.core.scheduling.OutboxDispatcher

import java.util.UUID

/** The mood check-in in chat (BOT_UX section 11.4): one tap records, the answer is the mood image with the labels and
  * the time as its caption, and energy is an optional second step. The answer has the same shape for every value. The
  * image needs the `images_in_chat` consent like every personal image (owner decision 2026-11-13, BUX-MOOD-03); without
  * it the answer is the text alone, and the consent is not asked here.
  */
private[capture] final class MoodChat(mood: MoodService, cards: StatusCards, clock: Clock, tokens: Tokens):

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
      image(turn, valence) match
        case Some(attachment) => Reply(followUps = List(confirmation.copy(attachment = Some(attachment))))
        case None             => turn.show(confirmation)

  /** A tap on a prompt (owner decision 2026-11-13: one prompt, one check-in). The check-in's key is the prompt message,
    * so a later, racing or post-restart tap on it replays the first check-in: it records nothing and answers "Already
    * recorded at 21:04", or that the check-in was undone. The first tap edits the prompt into the recorded level with
    * its 1 to 5 buttons gone; with an image, the image follows as its own message and carries Add energy and Undo. A
    * prompt of the prompt loop is closed as answered, so the next prompt leaves that message in the chat.
    */
  def checkInFromPrompt(turn: Turn, valence: Valence): Reply =
    turn.source match
      case None         => checkIn(turn, valence)
      case Some(prompt) =>
        record(turn, valence, MoodChat.promptKey(prompt)): recorded =>
          closeLoopPrompt(turn, prompt)
          val caption = captionOf(turn, recorded.checkin)
          image(turn, valence) match
            case None             => turn.show(turn.message("mood.recorded", List(caption), followUpRows(recorded)))
            case Some(attachment) =>
              val photo = turn.message("mood.recorded", List(caption), followUpRows(recorded))
              turn
                .show(turn.message("mood.prompt.recorded", List(caption), Nil))
                .copy(followUps = List(photo.copy(attachment = Some(attachment))))

  /** Closes the open mood prompt of the prompt loop when `prompt` is its message; a `/mood` prompt has no occurrence.
    */
  private def closeLoopPrompt(turn: Turn, prompt: MessageHandle): Unit =
    val prompts = turn.tx.reminders.prompts
    for
      plan <- prompts.schedule(turn.accountId, PromptKind.Mood)
      open <- prompts.lastSent(plan.id) if open.status == PromptStatus.Sent
      key <- open.sendKey
      if turn.tx.outbox.recordedHandle(prompt.vendor, key).contains(OutboxDispatcher.encodeHandle(prompt))
    do prompts.close(open.id, PromptStatus.Answered, clock.now())

  private def image(turn: Turn, valence: Valence): Option[AttachmentRef] =
    if turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.ImagesInChat) then
      cards.mood(MoodCardView(valence, None), turn.look, Some(turn.event.chat))
    else None

  private def record(turn: Turn, valence: Valence, key: String)(reply: MoodRecorded => Reply): Reply =
    val command = CheckIn(turn.accountId, valence, None, Nil, EntryDay.Today, EntrySource.Bot, key)
    answered(turn, mood.checkIn(turn.tx, command)): recorded =>
      if !recorded.replayed || key == turn.clientKey then reply(recorded)
      else if standing(turn, recorded.checkin) then
        turn.toast(CheckInCopy.alreadyRecorded(turn.localTime(recorded.checkin.occurredAt)))
      else turn.toast(CheckInCopy.promptUndone)

  /** Whether a check-in still counts: it is live, or a live correction (the energy step) descends from it. */
  private def standing(turn: Turn, checkin: MoodCheckin): Boolean =
    def descends(candidate: MoodCheckin): Boolean =
      candidate.supersedesId.exists: id =>
        id == checkin.id || turn.tx.tracking.mood.get(turn.accountId, id).exists(descends)
    checkin.isLive ||
    turn.tx.tracking.mood.listLive(turn.accountId, DateRange.single(checkin.localDate)).exists(descends)

  /** The first check-in of a correction chain: the one the tap recorded, with its key. */
  private def origin(turn: Turn, checkin: MoodCheckin): MoodCheckin =
    checkin.supersedesId.flatMap(turn.tx.tracking.mood.get(turn.accountId, _)).fold(checkin)(origin(turn, _))

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

  /** Undo answers on the message it was tapped on. A check-in from a prompt also takes its level off the prompt, which
    * shows it apart from the image message that carries the Undo.
    */
  def undo(turn: Turn, checkinId: UUID): Reply =
    answered(turn, mood.undo(turn.tx, turn.accountId, checkinId)): _ =>
      val undone = turn.show(turn.message("mood.undone", List(CheckInCopy.undone), Nil))
      promptOf(turn, checkinId).filterNot(prompt => turn.source.exists(MoodChat.sameMessage(_, prompt))) match
        case None         => undone
        case Some(prompt) =>
          val cleared = turn.message("mood.prompt.undone", List(CheckInCopy.undone), Nil).copy(replaces = Some(prompt))
          undone.copy(followUps = undone.followUps :+ cleared)

  private def promptOf(turn: Turn, checkinId: UUID): Option[MessageHandle] =
    turn.tx.tracking.mood
      .get(turn.accountId, checkinId)
      .flatMap(checkin => origin(turn, checkin).clientKey)
      .flatMap(MoodChat.promptOf)

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

private object MoodChat:
  private val Prefix = "mood.prompt:"

  /** The client key of a check-in from a prompt: the prompt message itself, so one prompt records once. */
  def promptKey(prompt: MessageHandle): String = s"$Prefix${prompt.vendor}:${prompt.chatId}:${prompt.messageId}"

  /** The prompt a client key names; `None` for the key of a typed check-in. The vendor has no colon and the message id
    * is the last part, so a chat id with a colon (`dm:user-1`) is read whole.
    */
  def promptOf(key: String): Option[MessageHandle] =
    Option
      .when(key.startsWith(Prefix))(key.drop(Prefix.length))
      .flatMap: handle =>
        val vendorEnd = handle.indexOf(':')
        val idStart = handle.lastIndexOf(':')
        Option.when(vendorEnd > 0 && idStart > vendorEnd + 1 && idStart < handle.length - 1):
          MessageHandle(handle.take(vendorEnd), handle.slice(vendorEnd + 1, idStart), handle.drop(idStart + 1))

  def sameMessage(a: MessageHandle, b: MessageHandle): Boolean =
    a.vendor == b.vendor && a.chatId == b.chatId && a.messageId == b.messageId
