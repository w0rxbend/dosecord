package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.WaterCardView

import java.time.LocalDate

/** Statistics in chat (BOT_UX section 12.5): the text of a period first, one tap to change the period, and the daily
  * summary image on request. Text and image come from the same records at the same moment (BUX-STAT-01).
  */
private[capture] final class StatsChat(parts: CaptureParts, tokens: Tokens):

  def card(turn: Turn, period: StatsPeriod): Reply =
    parts.stats.summary(turn.tx, turn.accountId, period) match
      case Left(_)        => turn.toast(ReminderCopy.staleControlToast)
      case Right(summary) => turn.show(turn.message("stats.card", summary.lines, rows(turn, period)))

  /** The tap on a period button; the value is the number of days of the period. */
  def show(turn: Turn, days: Long): Reply =
    StatsChat.periodOf(days) match
      case Some(period) => card(turn, period)
      case None         => turn.toast(ReminderCopy.staleControlToast)

  /** "Show as image": the daily summary card with today's text as its caption, as a new message. `doses` is absent when
    * the medication records are not shown.
    */
  def dayImage(turn: Turn, doses: Option[List[DoseMark]]): Reply =
    parts.stats.summary(turn.tx, turn.accountId, StatsPeriod.Today) match
      case Left(_)        => turn.toast(ReminderCopy.staleControlToast)
      case Right(summary) =>
        val today = summary.window.end
        val view = DayCardView(today, latestMood(turn), doses, waterToday(turn, today), sleepToday(turn, today))
        parts.cards.day(view, turn.look) match
          case Some(attachment) =>
            val caption = turn.message("stats.image", summary.lines, Nil)
            Reply(followUps = List(caption.copy(attachment = Some(attachment))))
          case None => turn.toast(StatsCardCopy.imageUnavailable)

  // A domain without consent answers with a refusal and is left out of the picture, as it is left out of the text.
  private def latestMood(turn: Turn): Option[MoodCardView] =
    parts.mood
      .day(turn.tx, turn.accountId, EntryDay.Today)
      .toOption
      .flatMap(_.latest)
      .map(checkin => MoodCardView(checkin.valence, checkin.energy))

  /** Water has a line on the card only against the user's own goal. */
  private def waterToday(turn: Turn, today: LocalDate): Option[WaterCardView] =
    for
      view <- parts.hydration.day(turn.tx, turn.accountId, EntryDay.Today).toOption
      goal <- view.goalMl
    yield
      val day = WaterChat.dayOf(view)
      WaterCardView(today, day.totalMl, goal, day.entries)

  /** Sleep, like water, has a line on the card only against the user's own goal: its halo segment is the last night
    * divided by that goal (VISUALS section 4.7).
    */
  private def sleepToday(turn: Turn, today: LocalDate): Option[SleepCardView] =
    for
      status <- parts.sleep.status(turn.tx, turn.accountId).toOption
      goal <- status.goalMinutes
      nights <- parts.stats.sleepCalendar(turn.tx, turn.accountId, DateRange.single(today)).toOption
    yield SleepCardView(nights.get(today).map(_.minutes), goal)

  private def rows(turn: Turn, period: StatsPeriod): List[List[Choice]] =
    val periods = StatsPeriod.values.toList.map: value =>
      Choice.callback(
        StatsCardCopy.period(value),
        tokens.direct(CaptureActions.StatsShow, turn.accountId, value.days.toLong)
      )
    val image = Option.when(period == StatsPeriod.Today):
      Choice.callback(StatsCardCopy.ShowAsImage, tokens.direct(CaptureActions.StatsImage, turn.accountId, 0))
    List(periods, image.toList)

private[capture] object StatsChat:

  def periodOf(days: Long): Option[StatsPeriod] = StatsPeriod.values.find(_.days.toLong == days)

  /** `/stats`, `/stats today`, `/stats 7`, `/stats 30`; no argument is the last seven days. */
  def periodOf(argument: String): Option[StatsPeriod] =
    argument match
      case ""      => Some(StatsPeriod.Last7Days)
      case "today" => Some(StatsPeriod.Today)
      case "7"     => Some(StatsPeriod.Last7Days)
      case "30"    => Some(StatsPeriod.Last30Days)
      case _       => None
