package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.RateEpisode
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepRefusal
import dosegram.core.application.sleep.SleepService
import dosegram.core.application.sleep.WokeUp
import dosegram.core.chat.ActionEntry
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.sleep.ClockNights
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepText
import dosegram.core.ports.Clock
import dosegram.core.ports.StatusCards

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Sleep in chat (BOT_UX sections 11.5 and 21.9): the `/sleep` card, the two check-ins, the one-tap rating, Undo, and
  * the typed night or nap. Sleep is what the user said it was: an "I'm up" without an open night records no night and
  * asks for the times. Nothing here reads Telegram presence.
  */
private[capture] final class SleepChat(sleep: SleepService, cards: StatusCards, clock: Clock, tokens: Tokens):

  /** Where sleep stands: the open check-in or the latest night, and the one button that fits. */
  def card(turn: Turn): Reply =
    answered(turn, sleep.status(turn.tx, turn.accountId)): status =>
      status.open match
        case Some(open) =>
          val rows = List(List(button(turn, CaptureActions.SleepUp, SleepCopy.ImUp)))
          turn.show(turn.message("sleep.card", List(SleepCopy.started(turn.localTime(open.occurredAt))), rows))
        case None =>
          val line = status.latestNight.fold(SleepCopy.noNight)(sleptLine(turn, _))
          val image =
            Option.when(status.latestNight.nonEmpty)(button(turn, CaptureActions.SleepImage, SleepCopy.ShowAsImage))
          val rows = List(button(turn, CaptureActions.SleepStart, SleepCopy.GoingToSleep) :: image.toList)
          turn.show(turn.message("sleep.card", List(line), rows))

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
            case Some(attachment) =>
              Reply(followUps =
                List(turn.message("sleep.image", List(caption), Nil).copy(attachment = Some(attachment)))
              )
            case None => turn.toast(SleepCopy.imageUnavailable)

  def goingToSleep(turn: Turn): Reply =
    answered(turn, sleep.goingToSleep(turn.tx, input(turn))): started =>
      val rows = List(
        List(
          button(turn, CaptureActions.SleepUp, SleepCopy.ImUp),
          Choice.callback(Labels.Undo, tokens.direct(CaptureActions.SleepStartUndo, started.checkin.id, 0))
        )
      )
      turn.show(turn.message("sleep.night", List(SleepCopy.started(turn.localTime(started.checkin.occurredAt))), rows))

  def wakeUp(turn: Turn): Reply =
    answered(turn, sleep.wakeUp(turn.tx, input(turn))):
      case WokeUp.Closed(episode, _) => recorded(turn, episode)
      case WokeUp.NeedsBedtime(_)    => turn.show(turn.message("sleep.ask", List(SleepCopy.ask), Nil))

  /** A typed night or nap: `slept 23:30 7:10`, `/sleep 23:30 7:10`, `nap 20 min`. A length without an edge is not
    * guessed: the user is asked for both times.
    */
  def typed(turn: Turn, text: SleepText): Reply =
    val now = clock.now()
    text match
      case SleepText.Night(bedtime, wakeTime) => enter(turn, SleepKind.Main, bedtime, wakeTime)
      case SleepText.NapBetween(start, end)   => enter(turn, SleepKind.Nap, start, end)
      case SleepText.NapFor(length)           =>
        record(turn, EnterSleep(input(turn), SleepKind.Nap, now.minus(length), now))
      case SleepText.SleptFor(_) | SleepText.NotSleep => turn.show(turn.message("sleep.ask", List(SleepCopy.ask), Nil))

  def rate(turn: Turn, episodeId: UUID, step: Long): Reply =
    Quality.of(step.toInt) match
      case None          => turn.toast(ReminderCopy.staleControlToast)
      case Some(quality) =>
        answered(turn, sleep.rate(turn.tx, RateEpisode(input(turn), episodeId, quality))): rated =>
          val lines = List(sleptLine(turn, rated.episode), SleepCopy.rated(quality.step))
          turn.show(turn.message("sleep.rated", lines, List(List(undoButton(rated.episode.id)))))

  def undo(turn: Turn, episodeId: UUID): Reply =
    answered(turn, sleep.undoEpisode(turn.tx, turn.accountId, episodeId)): _ =>
      turn.show(turn.message("sleep.undone", List(SleepCopy.undone), Nil))

  def undoStart(turn: Turn, checkinId: UUID): Reply =
    answered(turn, sleep.undoCheckin(turn.tx, turn.accountId, checkinId)): _ =>
      turn.show(turn.message("sleep.night.undone", List(SleepCopy.startUndone), Nil))

  private def enter(turn: Turn, kind: SleepKind, start: LocalTime, end: LocalTime): Reply =
    val wake = ClockNights.latestAt(end, clock.now(), turn.zone)
    val onset = ClockNights.latestAt(start, wake.minusSeconds(1), turn.zone)
    record(turn, EnterSleep(input(turn), kind, onset, wake))

  private def record(turn: Turn, command: EnterSleep): Reply =
    answered(turn, sleep.enter(turn.tx, command))(entered => recorded(turn, entered.episode))

  /** The night with the rating question; a nap is not rated. */
  private def recorded(turn: Turn, episode: SleepEpisode): Reply =
    val rate = Option.when(episode.kind == SleepKind.Main):
      Quality.steps.map: step =>
        Choice.callback(step.step.toString, tokens.direct(CaptureActions.SleepRate, episode.id, step.step.toLong))
    val lines = sleptLine(turn, episode) :: rate.map(_ => SleepCopy.qualityAsk).toList
    turn.show(turn.message("sleep.morning", lines, rate.toList :+ List(undoButton(episode.id))))

  private def sleptLine(turn: Turn, episode: SleepEpisode): String =
    SleepCopy.slept(episode, turn.localTime(episode.interval.onset.at), turn.localTime(episode.interval.wake.at))

  private def button(turn: Turn, action: ActionEntry, label: String): Choice =
    Choice.callback(label, tokens.direct(action, turn.accountId, 0))

  private def undoButton(episodeId: UUID): Choice =
    Choice.callback(Labels.Undo, tokens.direct(CaptureActions.SleepUndo, episodeId, 0))

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
