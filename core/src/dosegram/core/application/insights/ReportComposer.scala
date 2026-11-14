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
import dosegram.core.domain.copy.InsightCopy
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.InsightEngine
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.NightFacts
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
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

/** A due report as the outbox sends it: the images first, each its own photo message with its caption, then `main`, the
  * text with the "Stop these" control, which is the message the report's prompt row names. A photo album cannot carry a
  * keyboard, so the text message does (VISUALS section 6.6).
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
  * outbox.
  */
final class ReportComposer(stats: StatsQueryService, cards: StatusCards, codec: CallbackCodec):

  private val views = ReportViews(stats)

  /** The week that ends on `planDate`, the day the report is due. `withImages` is false on a day that already had the
    * monthly report, which takes precedence (VA-PS-03): the week is then sent as its text.
    */
  def weekly(
      tx: Tx,
      accountId: UUID,
      planDate: LocalDate,
      key: String,
      silent: Boolean,
      withImages: Boolean
  ): ReportOutcome =
    stats.summary(tx, accountId, StatsPeriod.Last7Days) match
      case Left(_)                                                       => ReportOutcome.NoRecords
      case Right(summary) if summary.lines.tail == List(StatsCopy.Empty) => ReportOutcome.NoRecords
      case Right(summary)                                                =>
        // At most one pattern, the first in rank order (VISUALS section 8.5, "Selection for the weekly report").
        val pattern = stats
          .insights(tx, accountId, ComparisonWindow.Days28)
          .toOption
          .flatMap(view => InsightEngine.forReport(view.list))
          .map(InsightCopy.text)
        val lines = ReportCopy.weeklyTitle :: summary.lines.tail ++ pattern.toList
        val images =
          if !withImages || !imagesAllowed(tx, accountId) then Nil
          else cards.week(views.week(tx, accountId, DateRange.endingOn(planDate, 7)), views.look(tx, accountId)).toList
        ReportOutcome.Send(compose(PromptKind.WeeklyReport, accountId, lines, images, key, silent))

  /** The calendar month before `planDate`; produced only when a domain meets the gate of R-INS-11. */
  def monthly(tx: Tx, accountId: UUID, planDate: LocalDate, key: String, silent: Boolean): ReportOutcome =
    stats.month(tx, accountId, YearMonth.from(planDate).minusMonths(1)) match
      case Left(_)                                   => ReportOutcome.NoRecords
      case Right(view) if !view.figures.isReportable => ReportOutcome.NoRecords
      case Right(view)                               =>
        val lines = ReportCopy.monthlyTitle :: MonthCopy.lines(view.figures, view.zone)
        val images =
          if !imagesAllowed(tx, accountId) then Nil
          else cards.month(views.month(tx, accountId, view.figures, planDate), views.look(tx, accountId))
        ReportOutcome.Send(compose(PromptKind.MonthlyReport, accountId, lines, images, key, silent))

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
    val photos = images.zipWithIndex.map { (image, index) =>
      val photoKey = ReportComposer.imageKey(key, index)
      OutboundMessage(
        body = List(Node.Paragraph(List(Inline.Text(image.altText)))),
        attachment = Some(image),
        silent = silent,
        dedupeKey = photoKey,
        correlationId = key
      )
    }
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

  /** The send key of a report's image `index` (0-based), next to the key of its text. */
  def imageKey(key: String, index: Int): String = s"$key:image${index + 1}"
