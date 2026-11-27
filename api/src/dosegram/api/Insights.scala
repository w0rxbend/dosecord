package dosegram.api

import dosegram.core.application.insights.TrendBlock
import dosegram.core.application.insights.TrendSource
import dosegram.core.application.insights.TrendsView
import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.TrendCopy
import dosegram.core.domain.insights.DaySeries
import dosegram.core.domain.insights.MedicationFigures
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.TrendFrame
import dosegram.core.domain.insights.TrendPeriod
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.util.UUID

/** `GET /insights/overview` (ARCHITECTURE section 9.5, MINI_APP section 7.11): the dashboard of a period of 7, 30 or 90
  * days, one block per domain whose consent is active. Every line is written by the core's copy, so the app shows the
  * sentences of the chat and composes none of its own (MINI_APP section 15.1). A domain without consent is absent, not
  * empty (PRODUCT_SPEC R-IDC-9). Each block carries its figures, the user's own previous period of the same length in
  * words (MA-INS-3), a small line by bucket, and its charts laid out by [[Charts]]: by day for 7 and 30 days, by week
  * for 90 (VISUALS section 9.2).
  */
final class Insights(uow: UnitOfWork, tracking: TrackingUseCases):

  def overview(accountId: UUID, rangeDays: Int): Either[ApiFailure, InsightsOverview] =
    TrendPeriod
      .of(rangeDays)
      .toRight(ApiFailure.invalid("range", "invalid"))
      .flatMap(period =>
        uow.transactionAs(accountId)(tx =>
          tracking.stats
            .trends(tx, accountId, period)
            .left
            .map(_ => ApiFailure.notFound())
            .map(overviewOf(tx, accountId, _))
        )
      )

  private def overviewOf(tx: Tx, accountId: UUID, view: TrendsView): InsightsOverview =
    val frame = view.frame
    val blocks = view.blocks.map(trend => blockOf(tx, accountId, frame, trend))
    InsightsOverview(
      rangeDays = frame.period.days,
      rangeLabel = ApiCopy.rangeLabel(frame.current),
      timeZone = view.zone.getId,
      ranges = TrendPeriod.values.toList.map(_.days),
      blocks = blocks,
      notice = Option.when(blocks.isEmpty)(ApiCopy.noConsentedDomain),
      counts = view.weekCounts
    )

  // The words come from the core's trend block as they are; the API adds the charts of its records.
  private def blockOf(tx: Tx, accountId: UUID, frame: TrendFrame, trend: TrendBlock): InsightBlock =
    def block(domain: OverviewDomain, charts: List[Chart], tone: SceneTone): InsightBlock =
      Insights.block(domain, trend.lines, charts, trend.comparison, Charts.spark(trend.buckets, tone))
    trend.source match
      case TrendSource.Medication(period) =>
        val byBucket =
          if frame.period.byWeek then Some(Charts.dosesByWeek(period.days, frame.weeks, frame.current))
          else DaySeries.of(period.days, frame.current).shown.map(Charts.dosesByDay(_, frame.current))
        block(
          OverviewDomain.Medication,
          Charts.doseOutcomes(period.total.buckets).toList ++ byBucket.toList,
          SceneTone.Recorded
        ).copy(medications = medicationRows(tx, accountId, period.perMedication))
      case TrendSource.Hydration(days) =>
        val chart =
          if frame.period.byWeek then Some(Charts.waterByWeek(days, frame.weeks, frame.current))
          else DaySeries.of(days, frame.current).shown.map(Charts.waterByDay(_, frame.current))
        block(OverviewDomain.Hydration, chart.toList, SceneTone.Hydration).copy(vessel = vesselOf(tx, accountId))
      case TrendSource.Mood(days) =>
        val trendChart =
          if frame.period.byWeek then Some(Charts.moodByWeek(days, frame.weeks, frame.current))
          else DaySeries.of(days, frame.current).shown.map(Charts.moodByDay(_, frame.current))
        val weekdays = MoodStats.byWeekday(days, frame.current)
        val byWeekday =
          Option.when(weekdays.exists(_.mean.shown.isDefined))(Charts.moodByWeekday(weekdays, frame.current))
        block(OverviewDomain.Mood, trendChart.toList ++ byWeekday.toList, SceneTone.Mood)
      case TrendSource.Sleep(days, regularity) =>
        val goal = regularity.goalMinutes
        val chart =
          if frame.period.byWeek then Some(Charts.sleepByWeek(days, frame.weeks, frame.current, goal))
          else DaySeries.of(days, frame.current).shown.map(Charts.sleepByDay(_, frame.current, goal))
        block(
          OverviewDomain.Sleep,
          chart.toList ++ Charts.sleepTimes(regularity.bars, frame.current).toList,
          SceneTone.Sleep
        ).copy(details = TrendCopy.regularity(regularity))
      case TrendSource.Nutrition(days) =>
        val chart =
          if frame.period.byWeek then Some(Charts.mealsByWeek(days, frame.weeks, frame.current))
          else DaySeries.of(days, frame.current).shown.map(Charts.mealsByDay(_, frame.current))
        block(OverviewDomain.Nutrition, chart.toList, SceneTone.Nutrition)

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
