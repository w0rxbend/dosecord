package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.application.insights.PeriodSummary
import dosegram.core.application.insights.ReportViews
import dosegram.core.application.nutrition.FoodViews
import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.CardPage
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView

import java.time.LocalDate

/** Statistics in chat (BOT_UX section 12.5): the text of a period first, one tap to change the period, and an image on
  * request. Text and image come from the same records at the same moment (BUX-STAT-01).
  */
private[capture] final class StatsChat(parts: CaptureParts, tokens: Tokens, share: ShareChat):

  def card(turn: Turn, period: StatsPeriod): Reply =
    parts.stats.summary(turn.tx, turn.accountId, period) match
      case Left(_)        => turn.toast(ReminderCopy.staleControlToast)
      case Right(summary) =>
        turn.show(turn.message("stats.card", summary.lines, rows(turn, period) :+ more(turn, summary.window.end)))

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
        val view = DayCardView(
          today,
          latestMood(turn),
          doses,
          waterToday(turn, today),
          sleepToday(turn, today),
          foodToday(turn)
        )
        parts.cards.day(view, turn.look) match
          case Some(card) =>
            val caption = turn.message("stats.image", summary.lines, Nil)
            Reply(followUps = turn.images(parts.delivery, List(card -> caption)))
          case None => turn.toast(StatsCardCopy.imageUnavailable)

  /** "Show as image" on the seven days (VISUALS IMG-19): the week at a glance with the seven days' text as its caption,
    * as a new message. Each domain is drawn behind its consent, as the weekly card draws it.
    */
  def weekImage(turn: Turn): Reply =
    parts.stats.summary(turn.tx, turn.accountId, StatsPeriod.Last7Days) match
      case Left(_)        => turn.toast(ReminderCopy.staleControlToast)
      case Right(summary) =>
        val view = ReportViews(parts.stats).week(turn.tx, turn.accountId, summary.window)
        parts.cards.weekGlance(view, turn.look) match
          case Some(card) =>
            val caption = turn.message("stats.week_image", summary.lines, Nil)
            Reply(followUps = turn.images(parts.delivery, List(card -> caption)))
          case None => turn.toast(StatsCardCopy.imageUnavailable)

  /** IMG-20: the water of the seven days, captioned with the card's own text equivalent. */
  def waterWeekImage(turn: Turn): Reply =
    withWeek(turn)((_, view) => parts.cards.waterWeek(view, turn.look))

  /** IMG-21: the main sleep of the seven nights, captioned with the card's own text equivalent. */
  def sleepWeekImage(turn: Turn): Reply =
    withWeek(turn)((_, view) => parts.cards.sleepWeek(view, turn.look))

  /** IMG-22: mood by weekday over the seven days, captioned with the card's own text equivalent. */
  def moodWeekdayImage(turn: Turn): Reply =
    withWeek(turn): (summary, _) =>
      val days = parts.stats
        .moodCalendar(turn.tx, turn.accountId, summary.window)
        .toOption
        .fold(Nil)(recorded => MoodStats.byWeekday(recorded, summary.window))
      parts.cards.moodWeekday(summary.window, days, turn.look)

  /** The seven-day view, then `draw`. No card means the same toast as "Show as image". */
  private def withWeek(turn: Turn)(draw: (PeriodSummary, WeekCardView) => Option[CardPage]): Reply =
    parts.stats.summary(turn.tx, turn.accountId, StatsPeriod.Last7Days) match
      case Left(_)        => turn.toast(ReminderCopy.staleControlToast)
      case Right(summary) =>
        val view = ReportViews(parts.stats).week(turn.tx, turn.accountId, summary.window)
        draw(summary, view) match
          case Some(card) =>
            val caption = turn.message(s"stats.image.${card.templateId}", List(card.altText), Nil)
            Reply(followUps = turn.images(parts.delivery, List(card -> caption)))
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

  /** Food has a line whenever the domain is on: the planned meals with an entry, or a count without a plan. */
  private def foodToday(turn: Turn): Option[FoodLineView] =
    for
      day <- parts.nutrition.today(turn.tx, turn.accountId).toOption
      plan <- parts.nutrition.plan(turn.tx, turn.accountId).toOption
    yield FoodViews.line(day, plan)

  private def rows(turn: Turn, period: StatsPeriod): List[List[Choice]] =
    val periods = StatsPeriod.values.toList.map: value =>
      Choice.callback(
        StatsCardCopy.period(value),
        tokens.direct(CaptureActions.StatsShow, turn.accountId, value.days.toLong)
      )
    // Today draws the daily summary, the seven days the week at a glance; the value names the period's days.
    val image = Option.when(period == StatsPeriod.Today || period == StatsPeriod.Last7Days):
      val days = if period == StatsPeriod.Today then 0L else StatsChat.WeekImageValue
      Choice.callback(StatsCardCopy.ShowAsImage, tokens.direct(CaptureActions.StatsImage, turn.accountId, days))
    // The shareable card (VISUALS IMG-17) is a fact of the last seven days, so it is offered beside them.
    val shareable = if period == StatsPeriod.Last7Days then share.button(turn) else None
    // IMG-20 to IMG-22 sit on their own row of the seven days, after the glance and the share.
    val weekCards = Option.when(period == StatsPeriod.Last7Days)(
      List(
        weekCard(turn, StatsCardCopy.WaterWeek, Pending.WaterWeekImage),
        weekCard(turn, StatsCardCopy.SleepWeek, Pending.SleepWeekImage),
        weekCard(turn, StatsCardCopy.MoodWeekday, Pending.MoodWeekdayImage)
      )
    )
    List(periods, image.toList ++ shareable.toList) ++ weekCards.toList

  private def weekCard(turn: Turn, label: String, pending: Pending): Choice =
    Choice.callback(label, tokens.direct(CaptureActions.StatsImage, turn.accountId, pending.argument.toLong))

  /** The month so far and the patterns: one tap away from any period (VISUALS sections 6.6 and 8.5). */
  private def more(turn: Turn, today: LocalDate): List[Choice] =
    val month = MonthAndInsightsChat.indexOf(java.time.YearMonth.from(today)).toLong
    List(
      Choice.callback(ReportCopy.ThisMonth, tokens.direct(CaptureActions.StatsMonth, turn.accountId, month)),
      Choice.callback(ReportCopy.Patterns, tokens.direct(CaptureActions.InsightsShow, turn.accountId, 0))
    )

private[capture] object StatsChat:

  /** The value of the "Show as image" button of the seven days; 0 is the daily summary of today. */
  val WeekImageValue: Long = 7

  def periodOf(days: Long): Option[StatsPeriod] = StatsPeriod.values.find(_.days.toLong == days)

  /** `/stats`, `/stats today`, `/stats 7`, `/stats 30`; no argument is the last seven days. */
  def periodOf(argument: String): Option[StatsPeriod] =
    argument match
      case ""      => Some(StatsPeriod.Last7Days)
      case "today" => Some(StatsPeriod.Today)
      case "7"     => Some(StatsPeriod.Last7Days)
      case "30"    => Some(StatsPeriod.Last30Days)
      case _       => None
