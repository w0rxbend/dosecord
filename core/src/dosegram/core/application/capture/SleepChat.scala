package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.application.reports.CardDelivery
import dosegram.core.application.sleep.AdjustEpisode
import dosegram.core.application.sleep.CompleteNight
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.RateEpisode
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepRefusal
import dosegram.core.application.sleep.SleepService
import dosegram.core.application.sleep.WokeUp
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.sleep.ClockNights
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepText
import dosegram.core.domain.sleep.TypedSleep
import dosegram.core.ports.Clock
import dosegram.core.ports.StatusCards

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Sleep in chat (BOT_UX sections 11.5 and 21.9): the `/sleep` card, the two check-ins, the one-tap rating, Undo, and
  * the typed night or nap. Sleep is what the user said it was: an "I'm up" without an open night records no night and
  * asks for the times. Nothing here reads Telegram presence.
  */
private val MinutesInDay = 24 * 60

private[capture] final class SleepChat(
    sleep: SleepService,
    cards: StatusCards,
    delivery: CardDelivery,
    clock: Clock,
    tokens: Tokens
):

  /** Where sleep stands: the open nap or night, or the latest night, and the buttons that fit. */
  def card(turn: Turn): Reply =
    answered(turn, sleep.status(turn.tx, turn.accountId)): status =>
      (status.openNap, status.open) match
        case (Some(nap), _) =>
          val rows = List(List(button(turn, CaptureActions.SleepUp, SleepCopy.ImUp)))
          turn.show(turn.message("sleep.card", List(SleepCopy.napStarted(turn.localTime(nap.occurredAt))), rows))
        case (None, Some(open)) =>
          val rows = List(List(button(turn, CaptureActions.SleepUp, SleepCopy.ImUp)))
          turn.show(turn.message("sleep.card", List(SleepCopy.started(turn.localTime(open.occurredAt))), rows))
        case (None, None) =>
          val line = status.latestNight.fold(SleepCopy.noNight)(sleptLine(turn, _))
          val image =
            Option.when(status.latestNight.nonEmpty)(button(turn, CaptureActions.SleepImage, SleepCopy.ShowAsImage))
          val start = List(
            button(turn, CaptureActions.SleepStart, SleepCopy.GoingToSleep),
            button(turn, CaptureActions.SleepNap, SleepCopy.Nap)
          )
          turn.show(turn.message("sleep.card", List(line), List(start, image.toList)))

  /** IMG-12 for the last 14 sleep days, sent as a new photo with the figures of `/stats` as its caption. */
  def image(turn: Turn): Reply =
    answered(turn, sleep.status(turn.tx, turn.accountId)): status =>
      val today = LocalDate.ofInstant(clock.now(), status.zone)
      val window = DateRange.endingOn(today, SleepRegularity.WindowDays)
      val episodes = turn.tx.tracking.sleep.liveEpisodes(turn.accountId, window)
      val figures = SleepRegularity.view(episodes, window, status.goalMinutes)
      StatsCopy.sleep(SleepStats.period(SleepStats.byDay(episodes), window)) match
        case None          => turn.toast(SleepCopy.imageEmpty)
        case Some(caption) =>
          cards.sleep(figures, turn.look) match
            case Some(card) =>
              Reply(followUps = turn.images(delivery, List(card -> turn.message("sleep.image", List(caption), Nil))))
            case None => turn.toast(SleepCopy.imageUnavailable)

  /** "Going to sleep": the night card says that prompts are held until "I'm up" (BOT_UX section 21.9). */
  def goingToSleep(turn: Turn): Reply =
    answered(turn, sleep.goingToSleep(turn.tx, input(turn))): started =>
      val lines = List(SleepCopy.started(turn.localTime(started.checkin.occurredAt)), SleepCopy.onHold)
      turn.show(turn.message("sleep.night", lines, List(openRow(turn, started.checkin.id))))

  /** "Nap": an explicit nap, which "I'm up" closes (PRODUCT_SPEC R-SLP-7). */
  def nap(turn: Turn): Reply =
    answered(turn, sleep.napStart(turn.tx, input(turn))): started =>
      val line = SleepCopy.napStarted(turn.localTime(started.checkin.occurredAt))
      turn.show(turn.message("sleep.nap", List(line), List(openRow(turn, started.checkin.id))))

  def wakeUp(turn: Turn): Reply =
    answered(turn, sleep.wakeUp(turn.tx, input(turn))):
      case WokeUp.Closed(episode, _)    => recorded(turn, episode)
      case WokeUp.NeedsBedtime(checkin) =>
        show(turn, "sleep.ask", SleepCards.bedtimeAsk(checkin.id, bedtimeOf(turn), token))

  /** A bedtime chip under "When did you go to sleep?": the night from that clock time to the "I'm up" tap. */
  def bedtime(turn: Turn, wakeCheckinId: UUID, minute: Long): Reply =
    if minute < 0 || minute >= MinutesInDay then turn.toast(ReminderCopy.staleControlToast)
    else
      val command = CompleteNight(input(turn), wakeCheckinId, LocalTime.ofSecondOfDay(minute * 60))
      answered(turn, sleep.completeNight(turn.tx, command))(entered => recorded(turn, entered.episode))

  /** "Adjust times" and each step in it: the edges as Save would store them; nothing is stored yet (BUX-SLEEP-04). */
  def adjust(turn: Turn, episodeId: UUID, value: Long): Reply =
    SleepShifts.of(value) match
      case None         => turn.toast(ReminderCopy.staleControlToast)
      case Some(shifts) =>
        answered(turn, sleep.episodeOf(turn.tx, turn.accountId, episodeId)): episode =>
          show(turn, "sleep.adjust", SleepCards.adjusting(episode, shifts, turn.zone, token))

  /** Save: the moved edges become entered times, the night keeps its rating, the old row stays as superseded. */
  def adjustSave(turn: Turn, episodeId: UUID, value: Long): Reply =
    SleepShifts.of(value) match
      case None         => turn.toast(ReminderCopy.staleControlToast)
      case Some(shifts) =>
        val command = AdjustEpisode(input(turn), episodeId, shifts.onset, shifts.wake)
        answered(turn, sleep.adjust(turn.tx, command))(adjusted => recorded(turn, adjusted.episode))

  /** Cancel: back to the night as it is stored. */
  def adjustCancel(turn: Turn, episodeId: UUID): Reply =
    answered(turn, sleep.episodeOf(turn.tx, turn.accountId, episodeId))(recorded(turn, _))

  /** A typed night or nap: `slept 23:30 7:10`, `/sleep 23:30 7:10`, `nap 20 min`. A length without an edge is not
    * guessed: the user is asked for both times.
    */
  def typed(turn: Turn, text: SleepText): Reply =
    entryOf(turn, text).fold(turn.show(turn.message("sleep.ask", List(SleepCopy.ask), Nil)))(recordTyped(turn, _))

  /** The night or nap a typed line states, with its edges as of now; `None` for a length without an edge. */
  def entryOf(turn: Turn, text: SleepText): Option[TypedSleep] =
    val now = clock.now()
    text match
      case SleepText.Night(bedtime, wakeTime)         => Some(between(turn, SleepKind.Main, bedtime, wakeTime, now))
      case SleepText.NapBetween(start, end)           => Some(between(turn, SleepKind.Nap, start, end, now))
      case SleepText.NapFor(length)                   => Some(TypedSleep(SleepKind.Nap, now.minus(length), now))
      case SleepText.SleptFor(_) | SleepText.NotSleep => None

  /** Records a typed night or nap, one held while the consent was asked included. */
  def recordTyped(turn: Turn, entry: TypedSleep): Reply =
    record(turn, EnterSleep(input(turn), entry.kind, entry.onsetAt, entry.wakeAt))

  def rate(turn: Turn, episodeId: UUID, step: Long): Reply =
    Quality.of(step.toInt) match
      case None          => turn.toast(ReminderCopy.staleControlToast)
      case Some(quality) =>
        answered(turn, sleep.rate(turn.tx, RateEpisode(input(turn), episodeId, quality))): rated =>
          show(turn, "sleep.rated", SleepCards.recorded(rated.episode, turn.zone, token))

  def undo(turn: Turn, episodeId: UUID): Reply =
    answered(turn, sleep.undoEpisode(turn.tx, turn.accountId, episodeId)): _ =>
      turn.show(turn.message("sleep.undone", List(SleepCopy.undone), Nil))

  def undoStart(turn: Turn, checkinId: UUID): Reply =
    answered(turn, sleep.undoCheckin(turn.tx, turn.accountId, checkinId)): _ =>
      turn.show(turn.message("sleep.night.undone", List(SleepCopy.startUndone), Nil))

  private def between(turn: Turn, kind: SleepKind, start: LocalTime, end: LocalTime, now: Instant): TypedSleep =
    val wake = ClockNights.latestAt(end, now, turn.zone)
    TypedSleep(kind, ClockNights.latestAt(start, wake.minusSeconds(1), turn.zone), wake)

  private def record(turn: Turn, command: EnterSleep): Reply =
    answered(turn, sleep.enter(turn.tx, command))(entered => recorded(turn, entered.episode))

  /** The night with the rating question, "Adjust times" and Undo; a nap is not rated. */
  private def recorded(turn: Turn, episode: SleepEpisode): Reply =
    show(turn, "sleep.morning", SleepCards.recorded(episode, turn.zone, token))

  private def show(turn: Turn, kind: String, card: SleepCard): Reply =
    turn.show(turn.message(kind, card.lines, card.rows))

  private def sleptLine(turn: Turn, episode: SleepEpisode): String = SleepCards.sleptLine(episode, turn.zone)

  /** The bedtime of the user's day: the anchor of `/reminders`, or the default day's. */
  private def bedtimeOf(turn: Turn): LocalTime =
    turn.tx.reminders.dayProfiles.anchors(turn.accountId).fold(DayProfile.Default.bedtime)(_.bedtime)

  private def token(action: String, subject: UUID, value: Long): String =
    val entry = ActionRegistry.byName(action).getOrElse(throw IllegalStateException(s"action '$action' is missing"))
    tokens.direct(entry, subject, value)

  private def button(turn: Turn, action: ActionEntry, label: String): Choice =
    Choice.callback(label, tokens.direct(action, turn.accountId, 0))

  /** "I'm up" and Undo under an open night or nap. */
  private def openRow(turn: Turn, checkinId: UUID): List[Choice] =
    List(
      button(turn, CaptureActions.SleepUp, SleepCopy.ImUp),
      Choice.callback(Labels.Undo, tokens.direct(CaptureActions.SleepStartUndo, checkinId, 0))
    )

  private def input(turn: Turn): SleepInput = SleepInput(turn.accountId, EntrySource.Bot, turn.clientKey)

  private def answered[A](turn: Turn, outcome: Either[SleepRefusal, A])(reply: A => Reply): Reply =
    outcome match
      case Right(value)  => reply(value)
      case Left(refusal) => turn.toast(refusalText(refusal))

  private def refusalText(refusal: SleepRefusal): String =
    refusal match
      case SleepRefusal.UnknownEpisode | SleepRefusal.UnknownCheckin      => SleepCopy.unknown
      case SleepRefusal.UndoWindowPassed                                  => SleepCopy.undoWindowPassed
      case SleepRefusal.Overlaps(_)                                       => SleepCopy.overlaps
      case SleepRefusal.NightExists(_)                                    => SleepCopy.nightExists
      case SleepRefusal.Invalid(SleepInvalid.TooShort(SleepKind.Main, _)) => SleepCopy.tooShort
      case SleepRefusal.Invalid(_)                                        => SleepCopy.invalid
      // The consent was withdrawn, or the account is gone, after the button was sent; the chat sets no goal.
      case SleepRefusal.ConsentMissing | SleepRefusal.UnknownAccount | SleepRefusal.GoalOutOfBounds =>
        ReminderCopy.staleControlToast
