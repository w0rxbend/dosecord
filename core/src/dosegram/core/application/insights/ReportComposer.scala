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
import dosegram.core.domain.insights.HighlightDomain
import dosegram.core.domain.insights.InsightEngine
import dosegram.core.domain.insights.LoggingDomain
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.NightFacts
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.insights.WeekHighlights
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DayCardView
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.Tx
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekDayFood
import dosegram.core.ports.WeekDayMood
import dosegram.core.ports.WeekDayNight
import dosegram.core.ports.WeekDayWater
import dosegram.core.ports.WeekHighlightsView
import dosegram.core.ports.WeekObservation

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.util.HexFormat
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

  /** IMG-25 (image round 5, supervisor decision of 2026-11-28): the highest day of each shown domain of `week` with a
    * record (never medication), and the weekly report's observation; `None` when no such domain has a record. The daily
    * mood mean is taken to one decimal, as the card states it, so days that read alike tie.
    */
  def highlights(week: WeekCardView, observation: Option[WeekObservation]): Option[WeekHighlightsView] =
    def of[A](domain: HighlightDomain, days: Option[List[Option[A]]])(value: A => Double) =
      days.flatMap(recorded => WeekHighlights.of(domain, recorded.map(_.map(value))))
    val rows = List(
      of(HighlightDomain.Water, week.water)(_.totalMl.toDouble),
      of(HighlightDomain.Food, week.food)(_.mainMeals.toDouble),
      of(HighlightDomain.Mood, week.mood)(day =>
        BigDecimal(day.mean).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble
      ),
      of(HighlightDomain.Sleep, week.sleep)(_.minutes.toDouble)
    ).flatten
    Option.when(rows.nonEmpty)(WeekHighlightsView(week.from, rows, observation))

  /** IMG-03 for a finished day, as the morning summary draws it: the last check-in of the day, the doses in schedule
    * order, water and the night against the user's own goals, the meals against the plan. Each domain behind its
    * consent; a domain the card cannot draw without inventing a figure is left to the text.
    */
  def day(tx: Tx, accountId: UUID, date: LocalDate): DayCardView =
    def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
    val single = DateRange.single(date)
    val mood = Option
      .when(granted(ConsentPurpose.Mood))(tx.tracking.mood.listLive(accountId, single).maxByOption(_.occurredAt))
      .flatten
      .map(checkin => MoodCardView(checkin.valence, checkin.energy))
    val doses = Option.when(granted(ConsentPurpose.Medication))(DoseMarks.of(tx, accountId, date))
    val water = stats.hydrationCalendar(tx, accountId, single).toOption.flatMap(_.get(date)).flatMap { day =>
      day.goalMl.map(goal => WaterCardView(date, day.totalMl, goal, day.entries))
    }
    val sleep = stats.sleepCalendar(tx, accountId, single).toOption.flatMap { nights =>
      NightFacts
        .goalOn(date, tx.tracking.sleep.goals(accountId, date))
        .map(SleepCardView(nights.get(date).map(_.minutes), _))
    }
    val food = stats.mealCalendar(tx, accountId, single).toOption.map { days =>
      val plan = tx.tracking.meals.plan(accountId).getOrElse(MealPlan.Default)
      val regular = MealStats.regular(date, days.get(date), plan)
      if plan.planned.isEmpty then FoodLineView(regular.logged.size, 0)
      else FoodLineView(regular.plannedLogged, plan.planned.size)
    }
    DayCardView(date, mood, doses, water, sleep, food)

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
  * outbox. Nothing here draws either: [[ReportPrerender]] draws the pages of [[scheduled]] in the 30 minutes before the
  * send time, and a report takes its pages only when every one of them is ready by then, else it goes as its text
  * (VISUALS VA-PS-08). `notReady` counts each page that was not, by its template id.
  */
final class ReportComposer(
    stats: StatsQueryService,
    cards: StatusCards,
    drawing: CardDrawing,
    codec: CallbackCodec,
    notReady: String => Unit = _ => ()
):

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
    weekDraft(tx, accountId, planDate, withImages) match
      case None        => ReportOutcome.NoRecords
      case Some(draft) =>
        val lines = draft.lines ++ counts(tx, accountId, ReportComposer.weekOf(planDate))
        ReportOutcome.Send(compose(PromptKind.WeeklyReport, accountId, lines, prepared(accountId, draft), key, silent))

  /** The morning summary (supervisor decision, 2026-11-27): the day before `planDate` in one message, one line per
    * domain, with the daily card (IMG-03) first when the account allows images in chat. A day without a record in any
    * domain sends nothing (VA-PS-05, VA-IMG-03a). `withImages` is false on a day that already had the monthly report.
    */
  def daily(
      tx: Tx,
      accountId: UUID,
      planDate: LocalDate,
      key: String,
      silent: Boolean,
      withImages: Boolean
  ): ReportOutcome =
    dayDraft(tx, accountId, planDate, withImages) match
      case None        => ReportOutcome.NoRecords
      case Some(draft) =>
        val images = prepared(accountId, draft)
        ReportOutcome.Send(compose(PromptKind.DailyReport, accountId, draft.lines, images, key, silent))

  /** The calendar month before `planDate`; produced only when a domain meets the gate of R-INS-11. */
  def monthly(tx: Tx, accountId: UUID, planDate: LocalDate, key: String, silent: Boolean): ReportOutcome =
    monthDraft(tx, accountId, planDate) match
      case None        => ReportOutcome.NoRecords
      case Some(draft) =>
        val images = prepared(accountId, draft)
        ReportOutcome.Send(compose(PromptKind.MonthlyReport, accountId, draft.lines, images, key, silent))

  /** The pages a report of `kind` due on `planDate` would carry now, each under its [[ReportComposer.preparedKey]]:
    * what [[ReportPrerender]] draws ahead of the send time (VISUALS VA-PS-08). Empty for a period without records or
    * without the images consent. [[ReportPrerender]] asks the send's precedence of VA-PS-03 first, so a report that
    * will go as its text has nothing drawn.
    */
  def scheduled(tx: Tx, kind: PromptKind, accountId: UUID, planDate: LocalDate): List[CardPage] =
    val draft = kind match
      case PromptKind.WeeklyReport  => weekDraft(tx, accountId, planDate, withImages = true)
      case PromptKind.MonthlyReport => monthDraft(tx, accountId, planDate)
      case PromptKind.DailyReport   => dayDraft(tx, accountId, planDate, withImages = true)
      case _                        => None
    draft.fold(Nil)(_.pages.map(ReportComposer.keyed(accountId, _)))

  /** The weekly text before its counts, and the week card then its highlights with the same pattern sentence (VISUALS
    * IMG-25), as one album. `None` for a week without records (VA-PS-05).
    */
  private def weekDraft(tx: Tx, accountId: UUID, planDate: LocalDate, withImages: Boolean): Option[Draft] =
    val week = ReportComposer.weekOf(planDate)
    stats.summary(tx, accountId, StatsPeriod.Last7Days, Some(week.end)) match
      case Left(_)                                                       => None
      case Right(summary) if summary.lines.tail == List(StatsCopy.Empty) => None
      case Right(summary)                                                =>
        // At most one pattern, the first in rank order (VISUALS section 8.5, "Selection for the weekly report").
        val pattern = stats
          .insights(tx, accountId, ComparisonWindow.Days28)
          .toOption
          .flatMap(view => InsightEngine.forReport(view.list))
          .map(InsightCopy.text)
        val pages =
          if !withImages || !imagesAllowed(tx, accountId) then Nil
          else
            val view = views.week(tx, accountId, week)
            val look = views.look(tx, accountId)
            val observation = pattern.map(WeekObservation(ComparisonWindow.Days28.days, _))
            val highlights = views.highlights(view, observation).flatMap(cards.weekHighlights(_, look))
            cards.week(view, look).toList ++ highlights.toList
        Some(Draft(ReportCopy.weeklyTitle :: summary.lines.tail ++ pattern.toList, pages))

  private def dayDraft(tx: Tx, accountId: UUID, planDate: LocalDate, withImages: Boolean): Option[Draft] =
    val yesterday = planDate.minusDays(1)
    stats.dayLines(tx, accountId, yesterday) match
      case Nil   => None
      case lines =>
        val pages =
          if !withImages || !imagesAllowed(tx, accountId) then Nil
          else cards.day(views.day(tx, accountId, yesterday), views.look(tx, accountId)).toList
        Some(Draft(ReportCopy.dailyTitle(yesterday) :: lines, pages))

  private def monthDraft(tx: Tx, accountId: UUID, planDate: LocalDate): Option[Draft] =
    stats.month(tx, accountId, YearMonth.from(planDate).minusMonths(1)) match
      case Left(_)                                   => None
      case Right(view) if !view.figures.isReportable => None
      case Right(view)                               =>
        val monthText =
          if tx.accounts.gamificationOf(accountId) then MonthCopy.lines(view.figures, view.zone)
          else MonthCopy.linesOmittingGates(view.figures, view.zone)
        val pages =
          if !imagesAllowed(tx, accountId) then Nil
          else
            val month = views.month(tx, accountId, view.figures, planDate)
            val look = views.look(tx, accountId)
            cards.month(month, look) ++ cards.monthPoster(month, look).toList
        Some(Draft(ReportCopy.monthlyTitle :: monthText, pages))

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

  /** The pages drawn ahead of the send, in order, with nothing drawn now (VA-PS-08). A report with a page that is not
    * ready goes as its text alone, so a week card never arrives without its highlights; each such page is counted.
    */
  private def prepared(accountId: UUID, draft: Draft): List[AttachmentRef] =
    val found = draft.pages.map(page => page -> drawing.ready(ReportComposer.keyed(accountId, page)))
    val missing = found.collect { case (page, None) => page.templateId }
    missing.foreach(notReady)
    if missing.isEmpty then found.flatMap(_._2) else Nil

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
            body = List(Node.Paragraph(List(Inline.Text(single.albumCaption)))),
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

  /** How long before its send time a scheduled report's pages are drawn (VISUALS VA-PS-08). */
  val PrepareAhead: Duration = Duration.ofMinutes(30)

  /** The key a scheduled page is drawn under and found by at its send time: the account's [[preparedPrefix]], then a
    * digest of the account and the picture, so a page whose figures changed after it was drawn is not found, and the
    * key says nothing about either.
    */
  def preparedKey(accountId: UUID, page: CardPage): String =
    val digest = MessageDigest.getInstance("SHA-256")
    List(accountId.toString, page.templateId, page.svg).foreach: part =>
      digest.update(part.getBytes(UTF_8))
      digest.update(0.toByte)
    s"${preparedPrefix(accountId)}${HexFormat.of().formatHex(digest.digest())}"

  /** The start of the key of every page drawn ahead for the account: a digest of the account id alone, so the account
    * deletion finds, in the same transaction, the pages drawn and not yet sent (supervisor decision, 2026-11-28), while
    * the key still says nothing about the account. The erasure registry computes the same digest in SQL.
    */
  def preparedPrefix(accountId: UUID): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$PreparedContext:$accountId".getBytes(UTF_8))
    s"report.${HexFormat.of().formatHex(digest, 0, PreparedPrefixBytes)}."

  /** The domain-separation context of [[preparedPrefix]] and the bytes of its digest that the prefix keeps. */
  val PreparedContext = "dosegram.report-pages.v1"
  val PreparedPrefixBytes = 8

  private def keyed(accountId: UUID, page: CardPage): CardPage =
    page.copy(sharedKey = Some(preparedKey(accountId, page)))

  /** The seven complete days before the day a weekly report is due (PRODUCT_SPEC R-INS-4). */
  private def weekOf(planDate: LocalDate): DateRange = DateRange.endingOn(planDate.minusDays(1), 7)

/** A report's text and the pages it carries when they are ready. */
final private case class Draft(lines: List[String], pages: List[CardPage])
