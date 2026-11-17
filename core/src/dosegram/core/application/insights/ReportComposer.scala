package dosegram.core.application.insights

import dosegram.contracts.AttachmentRef
import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.capture.DoseMarks
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.GamificationCopy
import dosegram.core.domain.copy.InsightCopy
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.Consistency
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.InsightEngine
import dosegram.core.domain.insights.LoggingDomain
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.NightFacts
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.Tx
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekDayFood
import dosegram.core.ports.WeekDayMood
import dosegram.core.ports.WeekDayNight
import dosegram.core.ports.WeekDayWater

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The views of the report cards, read through [[StatsQueryService]] on the caller's transaction: each domain behind
  * its consent, a day without a record absent. The chat's "Show as image" and the scheduled reports draw the same
  * views.
  */
final class ReportViews(stats: StatsQueryService):

  /** The month's pages: the figures and, with the sleep consent, the month's nights on the clock. */
  def month(tx: Tx, accountId: UUID, figures: MonthFigures, today: LocalDate): MonthCardView =
    val sleep = Option.when(tx.consents.isGranted(accountId, ConsentPurpose.Sleep)) {
      val goal = NightFacts.goalOn(figures.window.end, tx.tracking.sleep.goals(accountId, figures.window.end))
      SleepRegularity.view(tx.tracking.sleep.liveEpisodes(accountId, figures.window), figures.window, goal)
    }
    MonthCardView(figures, sleep, today)

  /** The seven days of the weekly card. */
  def week(tx: Tx, accountId: UUID, window: DateRange): WeekCardView =
    val doses = Option.when(tx.consents.isGranted(accountId, ConsentPurpose.Medication))(
      window.dates.map(DoseMarks.of(tx, accountId, _))
    )
    val water = stats.hydrationCalendar(tx, accountId, window).toOption.map { days =>
      window.dates.map(date => days.get(date).map(day => WeekDayWater(day.totalMl, day.goalMl)))
    }
    val mood = stats.moodCalendar(tx, accountId, window).toOption.map { days =>
      window.dates.map(date => days.get(date).map(day => WeekDayMood(day.mean, day.energyMean)))
    }
    val sleep = stats.sleepCalendar(tx, accountId, window).toOption.map { days =>
      val goal = NightFacts.goalOn(window.end, tx.tracking.sleep.goals(accountId, window.end))
      window.dates.map(date => days.get(date).map(night => WeekDayNight(night.minutes, goal)))
    }
    val food = stats.mealCalendar(tx, accountId, window).toOption.map { days =>
      window.dates.map(date => days.get(date).map(day => WeekDayFood(day.logged.size, day.snacks)))
    }
    WeekCardView(window.start, doses, water, mood, sleep, food)

  /** The theme and discreet mode the account set for its images. */
  def look(tx: Tx, accountId: UUID): CardLook =
    CardLook.of(tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default))

/** A due report as the outbox sends it: its pages first, one photo or one album of every page (VISUALS section 6.6),
  * then `main`, the text with the "Stop these" control, which is the message the report's prompt row names. A photo
  * album cannot carry a keyboard, so the text message does.
  */
final case class ComposedReport(images: List[OutboundMessage], main: OutboundMessage)

/** What a report that fell due becomes. A period without records sends nothing (VA-PS-05). */
enum ReportOutcome:
  case Send(report: ComposedReport)
  case NoRecords

/** The proactive reports of VISUALS_AND_ANALYTICS section 10: the weekly summary of the seven days that end on the day
  * it is due, and the monthly report of the previous calendar month, each in text with at most one pattern sentence,
  * and with its cards when the account allows images in chat (VA-PS-07). Every figure comes from [[StatsQueryService]],
  * so a report says what `/stats` says over the same days. Nothing here sends: the prompt loop puts the messages in the
  * outbox. The cards are still drawn here, on the prompt loop's transaction: rendering them ahead of their send time in
  * the report class is VISUALS VA-PS-08, not yet built.
  */
final class ReportComposer(stats: StatsQueryService, cards: StatusCards, drawing: CardDrawing, codec: CallbackCodec):

  private val views = ReportViews(stats)

  /** The seven complete days before `planDate`, the day the report is due (PRODUCT_SPEC R-INS-4). `withImages` is false
    * on a day that already had the monthly report, which takes precedence (VA-PS-03): the week is then sent as its
    * text. With the gamification switch on, the week's day counts and the milestones it passed close the text
    * (R-GAM-4).
    */
  def weekly(
      tx: Tx,
      accountId: UUID,
      planDate: LocalDate,
      key: String,
      silent: Boolean,
      withImages: Boolean
  ): ReportOutcome =
    val week = DateRange.endingOn(planDate.minusDays(1), 7)
    stats.summary(tx, accountId, StatsPeriod.Last7Days, Some(week.end)) match
      case Left(_)                                                       => ReportOutcome.NoRecords
      case Right(summary) if summary.lines.tail == List(StatsCopy.Empty) => ReportOutcome.NoRecords
      case Right(summary)                                                =>
        // At most one pattern, the first in rank order (VISUALS section 8.5, "Selection for the weekly report").
        val pattern = stats
          .insights(tx, accountId, ComparisonWindow.Days28)
          .toOption
          .flatMap(view => InsightEngine.forReport(view.list))
          .map(InsightCopy.text)
        val lines = ReportCopy.weeklyTitle :: summary.lines.tail ++ pattern.toList ++ counts(tx, accountId, week)
        val images =
          if !withImages || !imagesAllowed(tx, accountId) then Nil
          else drawn(cards.week(views.week(tx, accountId, week), views.look(tx, accountId)).toList)
        ReportOutcome.Send(compose(PromptKind.WeeklyReport, accountId, lines, images, key, silent))

  /** The calendar month before `planDate`; produced only when a domain meets the gate of R-INS-11. */
  def monthly(tx: Tx, accountId: UUID, planDate: LocalDate, key: String, silent: Boolean): ReportOutcome =
    stats.month(tx, accountId, YearMonth.from(planDate).minusMonths(1)) match
      case Left(_)                                   => ReportOutcome.NoRecords
      case Right(view) if !view.figures.isReportable => ReportOutcome.NoRecords
      case Right(view)                               =>
        val monthText =
          if tx.accounts.gamificationOf(accountId) then MonthCopy.lines(view.figures, view.zone)
          else MonthCopy.linesOmittingGates(view.figures, view.zone)
        val lines = ReportCopy.monthlyTitle :: monthText
        val images =
          if !imagesAllowed(tx, accountId) then Nil
          else drawn(cards.month(views.month(tx, accountId, view.figures, planDate), views.look(tx, accountId)))
        ReportOutcome.Send(compose(PromptKind.MonthlyReport, accountId, lines, images, key, silent))

  /** The day counts of the week, the round counts it passed, and a one-year line when that day falls in the week
    * (PRODUCT_SPEC section 8.14). Nothing with the switch off.
    */
  private def counts(tx: Tx, accountId: UUID, week: DateRange): List[String] =
    if !tx.accounts.gamificationOf(accountId) then Nil
    else
      val recorded = ReportComposer.logging(stats.recordedDays(tx, accountId, week))
      val before = ReportComposer.logging(stats.loggingCounts(tx, accountId, week.start.minusDays(1)))
      val after = ReportComposer.logging(stats.loggingCounts(tx, accountId, week.end))
      val firsts = ReportComposer.logging(stats.firstLogged(tx, accountId, week.end))
      GamificationCopy.consistency(Consistency.weekly(recorded, week)).toList ++
        Consistency.milestones(before, after).map(GamificationCopy.milestone) ++
        Consistency.anniversaries(firsts, week).map(GamificationCopy.anniversary)

  /** The question of ROADMAP conflict X-19, when the week or month before `today` is the first one eligible (VISUALS
    * section 10.1): a weekly report after a week with records on 3 or more days, a monthly one after a month that meets
    * the gate of R-INS-11. `None` when the period is not eligible.
    */
  def offer(tx: Tx, accountId: UUID, kind: PromptKind, today: LocalDate, key: String): Option[OutboundMessage] =
    val days =
      if kind == PromptKind.MonthlyReport then
        stats
          .month(tx, accountId, YearMonth.from(today).minusMonths(1))
          .toOption
          .filter(_.figures.isReportable)
          .map(_.figures.daysWithRecords)
      else
        val week = DateRange.endingOn(today.minusDays(1), 7)
        Some(stats.recordedDays(tx, accountId, week).values.flatten.toSet.size)
          .filter(_ >= ReportComposer.WeekOfferDays)
    days.map: count =>
      val choices = List(
        control(ReportComposer.OfferYesAction, accountId, kind, ReportCopy.OfferYes),
        control(ReportComposer.OfferNoAction, accountId, kind, ReportCopy.OfferNo)
      )
      textMessage(List(ReportCopy.offer(kind, count)), choices, key)

  /** The one line of VA-PS-06 a paused report type leaves, with Resume. */
  def pausedNotice(kind: PromptKind, accountId: UUID, key: String): OutboundMessage =
    textMessage(
      List(ReportCopy.paused(kind)),
      List(control(ReportComposer.ResumeAction, accountId, kind, ReportCopy.Resume)),
      key
    )

  private def control(action: String, accountId: UUID, kind: PromptKind, label: String): Choice =
    val entry = ActionRegistry.byName(action).getOrElse(throw IllegalStateException(s"action '$action' is missing"))
    Choice.callback(label, codec.encode(CallbackMode.Direct, entry, accountId, kind.ordinal.toLong).wire)

  private def textMessage(lines: List[String], choices: List[Choice], key: String): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = List(Block.Choices(ChoiceSet("report.0", choices = choices))),
      silent = true,
      dedupeKey = key,
      correlationId = key
    )

  /** The pages that could be drawn, in order; a page that cannot be drawn ends the album there. */
  private def drawn(pages: List[CardPage]): List[AttachmentRef] =
    pages.iterator.map(drawing.draw(_, None).toOption).takeWhile(_.isDefined).flatten.toList

  private def imagesAllowed(tx: Tx, accountId: UUID): Boolean =
    tx.consents.isGranted(accountId, ConsentPurpose.ImagesInChat)

  private def compose(
      kind: PromptKind,
      accountId: UUID,
      lines: List[String],
      images: List[AttachmentRef],
      key: String,
      silent: Boolean
  ): ComposedReport =
    // Pages of one report go as one album, each page captioned with its text equivalent (VISUALS section 6.6); a
    // single page is a photo with its caption.
    val photos = images match
      case Nil          => Nil
      case List(single) =>
        List(
          OutboundMessage(
            body = List(Node.Paragraph(List(Inline.Text(single.altText)))),
            attachment = Some(single),
            silent = silent,
            dedupeKey = ReportComposer.imageKey(key, 0),
            correlationId = key
          )
        )
      case pages =>
        List(
          OutboundMessage(
            body = Nil,
            album = pages.take(OutboundMessage.MaxAlbum),
            silent = silent,
            dedupeKey = ReportComposer.imageKey(key, 0),
            correlationId = key
          )
        )
    val entry = ActionRegistry
      .byName(ReportComposer.StopAction)
      .getOrElse(throw IllegalStateException(s"action '${ReportComposer.StopAction}' is missing"))
    val stop = Choice.callback(
      ReportCopy.StopThese,
      codec.encode(CallbackMode.Direct, entry, accountId, kind.ordinal.toLong).wire
    )
    val main = OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = List(Block.Choices(ChoiceSet("report.0", choices = List(stop)))),
      silent = silent,
      dedupeKey = key,
      correlationId = key
    )
    ComposedReport(photos, main)
end ReportComposer

object ReportComposer:

  /** The "Stop these" control of a scheduled report (VA-PS-01); its value is the ordinal of the report's kind. */
  val StopAction = "reminders.report_stop"

  val OfferYesAction = "reminders.report_offer_yes"
  val OfferNoAction = "reminders.report_offer_no"
  val ResumeAction = "reminders.report_resume"

  /** A week with records on this many days is the first one a weekly report is offered for (VISUALS section 10.1). */
  val WeekOfferDays = 3

  /** The counted domains of a reading per consent purpose: medication is never one of them (R-GAM-2). */
  def logging[A](byPurpose: Map[ConsentPurpose, A]): Map[LoggingDomain, A] =
    byPurpose.flatMap: (purpose, value) =>
      val domain = purpose match
        case ConsentPurpose.Hydration => Some(LoggingDomain.Water)
        case ConsentPurpose.Mood      => Some(LoggingDomain.Mood)
        case ConsentPurpose.Sleep     => Some(LoggingDomain.Sleep)
        case ConsentPurpose.Nutrition => Some(LoggingDomain.Food)
        case _                        => None
      domain.map(_ -> value)

  /** The send key of a report's image `index` (0-based), next to the key of its text. */
  def imageKey(key: String, index: Int): String = s"$key:image${index + 1}"
