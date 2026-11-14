package dosegram.api

import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.TrendCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DaySeries
import dosegram.core.domain.insights.MedicationFigures
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.TrendPeriod
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.util.UUID

/** `GET /insights/overview` (ARCHITECTURE section 9.5, MINI_APP section 7.11): the dashboard of a period of 7, 30 or 90
  * days, one block per domain whose consent is active. Every line is written by the core's copy, so the app shows the
  * sentences of the chat and composes none of its own (MINI_APP section 15.1). A domain without consent is absent, not
  * empty (PRODUCT_SPEC R-IDC-9). Each block carries its figures, the user's own previous period of the same length in
  * words (MA-INS-3), a small line by bucket, and its charts laid out by [[Charts]]: by day for 7 and 30 days, by week
  * for 90 (VISUALS section 9.2).
  */
final class Insights(uow: UnitOfWork, tracking: TrackingUseCases):

  import Insights.*

  def overview(accountId: UUID, rangeDays: Int): Either[ApiFailure, InsightsOverview] =
    TrendPeriod
      .of(rangeDays)
      .toRight(ApiFailure.invalid("range", "invalid"))
      .flatMap(period =>
        uow.transaction(tx =>
          tracking.stats
            .today(tx, accountId)
            .left
            .map(_ => ApiFailure.notFound())
            .map(overviewOf(tx, accountId, period, _))
        )
      )

  private def overviewOf(tx: Tx, accountId: UUID, period: TrendPeriod, today: LocalDate): InsightsOverview =
    val frame = Frame(period, period.current(today), period.previous(today))
    val blocks = List(
      medication(tx, accountId, frame),
      hydration(tx, accountId, frame),
      mood(tx, accountId, frame),
      sleep(tx, accountId, frame),
      food(tx, accountId, frame)
    ).flatten
    InsightsOverview(
      rangeDays = period.days,
      rangeLabel = ApiCopy.rangeLabel(frame.current),
      timeZone = tx.accounts.timezoneOf(accountId).getOrElse("UTC"),
      ranges = TrendPeriod.values.toList.map(_.days),
      blocks = blocks,
      notice = Option.when(blocks.isEmpty)(ApiCopy.noConsentedDomain)
    )

  // A refusal is a missing consent (or an account that is gone): the block is left out.
  private def medication(tx: Tx, accountId: UUID, frame: Frame): Option[InsightBlock] =
    for
      view <- tracking.stats.medicationOver(tx, accountId, frame.current).toOption
      previous <- tracking.stats.medicationOver(tx, accountId, frame.previous).toOption
    yield
      val days = view.period.days
      val byBucket =
        if frame.period.byWeek then Some(Charts.dosesByWeek(days, frame.weeks, frame.current))
        else DaySeries.of(days, frame.current).shown.map(Charts.dosesByDay(_, frame.current))
      val shares = frame.buckets.map: bucket =>
        val pooled = bucket.dates.flatMap(days.get).map(_.total)
        val scheduled = pooled.map(Charts.scheduledIn).sum
        Option.when(scheduled > 0)(pooled.map(_.resolved.taken).sum.toDouble / scheduled)
      block(
        OverviewDomain.Medication,
        StatsCopy.doseFigures(view.period.total),
        Charts.doseOutcomes(view.period.total.buckets).toList ++ byBucket.toList,
        TrendCopy.doses(frame.period.days, previous.period.total),
        Charts.spark(shares, SceneTone.Recorded)
      ).copy(medications = medicationRows(tx, accountId, view.period.perMedication))

  private def hydration(tx: Tx, accountId: UUID, frame: Frame): Option[InsightBlock] =
    for
      view <- tracking.stats.hydrationOver(tx, accountId, frame.current).toOption
      previous <- tracking.stats.hydrationOver(tx, accountId, frame.previous).toOption
    yield
      val chart =
        if frame.period.byWeek then Some(Charts.waterByWeek(view.days, frame.weeks, frame.current))
        else DaySeries.of(view.days, frame.current).shown.map(Charts.waterByDay(_, frame.current))
      block(
        OverviewDomain.Hydration,
        StatsCopy.waterFigures(view.period),
        chart.toList,
        TrendCopy.water(frame.period.days, previous.period),
        Charts.spark(frame.means(view.days)(_.totalMl.toDouble), SceneTone.Hydration)
      ).copy(vessel = vesselOf(tx, accountId))

  private def mood(tx: Tx, accountId: UUID, frame: Frame): Option[InsightBlock] =
    for
      view <- tracking.stats.moodOver(tx, accountId, frame.current).toOption
      previous <- tracking.stats.moodOver(tx, accountId, frame.previous).toOption
    yield
      val trend =
        if frame.period.byWeek then Some(Charts.moodByWeek(view.days, frame.weeks, frame.current))
        else DaySeries.of(view.days, frame.current).shown.map(Charts.moodByDay(_, frame.current))
      val weekdays = MoodStats.byWeekday(view.days, frame.current)
      val byWeekday =
        Option.when(weekdays.exists(_.mean.shown.isDefined))(Charts.moodByWeekday(weekdays, frame.current))
      block(
        OverviewDomain.Mood,
        StatsCopy.moodFigures(view.period),
        trend.toList ++ byWeekday.toList,
        TrendCopy.mood(frame.period.days, previous.period),
        Charts.spark(frame.means(view.days)(_.mean), SceneTone.Mood)
      )

  private def sleep(tx: Tx, accountId: UUID, frame: Frame): Option[InsightBlock] =
    for
      view <- tracking.stats.sleepOver(tx, accountId, frame.current).toOption
      previous <- tracking.stats.sleepOver(tx, accountId, frame.previous).toOption
      regularity <- tracking.stats.sleepRegularity(tx, accountId, frame.current).toOption
    yield
      val goal = regularity.goalMinutes
      val chart =
        if frame.period.byWeek then Some(Charts.sleepByWeek(view.days, frame.weeks, frame.current, goal))
        else DaySeries.of(view.days, frame.current).shown.map(Charts.sleepByDay(_, frame.current, goal))
      block(
        OverviewDomain.Sleep,
        StatsCopy.sleepFigures(view.period),
        chart.toList ++ Charts.sleepTimes(regularity.bars, frame.current).toList,
        TrendCopy.sleep(frame.period.days, previous.period),
        Charts.spark(frame.means(view.days)(_.minutes.toDouble), SceneTone.Sleep)
      ).copy(details = TrendCopy.regularity(regularity))

  private def food(tx: Tx, accountId: UUID, frame: Frame): Option[InsightBlock] =
    for
      view <- tracking.stats.mealsOver(tx, accountId, frame.current).toOption
      previous <- tracking.stats.mealsOver(tx, accountId, frame.previous).toOption
    yield
      val chart =
        if frame.period.byWeek then Some(Charts.mealsByWeek(view.days, frame.weeks, frame.current))
        else DaySeries.of(view.days, frame.current).shown.map(Charts.mealsByDay(_, frame.current))
      val counts = frame.buckets.map(bucket => Some(bucket.dates.flatMap(view.days.get).map(_.meals).sum.toDouble))
      block(
        OverviewDomain.Nutrition,
        TrendCopy.foodFigures(view.period),
        chart.toList,
        TrendCopy.food(frame.period.days, previous.period),
        Option.when(view.period.meals > 0)(Charts.spark(counts, SceneTone.Nutrition)).flatten
      )

  /** Today against the user's own goal; without a goal there is nothing to fill. */
  private def vesselOf(tx: Tx, accountId: UUID): Option[InsightVessel] =
    for
      today <- tracking.hydration.day(tx, accountId, EntryDay.Today).toOption
      goal <- today.goalMl
      recorded <- today.recorded
      label <- StatsCopy.waterDay(Some(recorded))
    yield InsightVessel(recorded.totalMl, goal, label)

  /** The medications of the period in registry order, each with its own counts; one without a dose is left out. */
  private def medicationRows(
      tx: Tx,
      accountId: UUID,
      figures: Map[UUID, MedicationFigures]
  ): List[InsightMedicationRow] =
    tx.medications
      .listForAccount(accountId)
      .flatMap: medication =>
        figures
          .get(medication.id)
          .filterNot(_.buckets.isEmpty)
          .map(InsightReports.row(medication.id, medication.name, medication.status == MedicationStatus.Archived, _))

object Insights:

  /** The windows of one request: the period, its current window, the previous one of the same length, and the buckets
    * its charts and small lines are drawn in.
    */
  private final case class Frame(period: TrendPeriod, current: DateRange, previous: DateRange):
    val weeks: List[DateRange] = TrendPeriod.weeks(current)
    val buckets: List[DateRange] = if period.byWeek then weeks else current.dates.map(DateRange.single)

    /** Per bucket, the mean of a value over the recorded days in it; a bucket without a record is a gap. */
    def means[A](days: Map[LocalDate, A])(value: A => Double): List[Option[Double]] =
      buckets.map: bucket =>
        val own = bucket.dates.flatMap(days.get).map(value)
        Option.when(own.nonEmpty)(own.sum / own.size)

  private def block(
      domain: OverviewDomain,
      lines: List[String],
      charts: List[Chart],
      comparison: String,
      spark: Option[Scene]
  ): InsightBlock =
    InsightBlock(
      id = domain,
      title = ApiCopy.overviewTitle(domain),
      lines = lines,
      noDataText = Option.when(lines.isEmpty)(ApiCopy.noDataInPeriod),
      charts = if lines.isEmpty then Nil else charts,
      comparison = Some(comparison),
      spark = if lines.isEmpty then None else spark
    )
