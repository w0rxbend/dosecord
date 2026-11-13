package dosegram.api

import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DaySeries
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.Clock
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.ZoneId
import java.util.UUID

/** `GET /insights/overview` (ARCHITECTURE section 9.5): the statistics of a period, one block per domain whose consent
  * is active. Every line is written by the statistics copy of the core, so the app shows the sentences of the chat and
  * composes none of its own (MINI_APP section 15.1). A domain without consent is absent, not empty (PRODUCT_SPEC
  * R-IDC-9). Each block carries the charts of its days, laid out by [[Charts]]: dose outcomes and doses by day, water
  * by day, mood by day, sleep by night.
  */
final class Insights(uow: UnitOfWork, tracking: TrackingUseCases, clock: Clock):

  import Insights.*

  def overview(accountId: UUID, rangeDays: Int): Either[ApiFailure, InsightsOverview] =
    periodOf(rangeDays)
      .toRight(ApiFailure.invalid("range", "invalid"))
      .map(period => uow.transaction(overviewOf(_, accountId, period)))

  private def overviewOf(tx: Tx, accountId: UUID, period: StatsPeriod): InsightsOverview =
    val zone = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))
    val window = period.window(LocalDays.dateOf(clock.now(), zone))
    val blocks = List(
      medication(tx, accountId, period, window),
      hydration(tx, accountId, period, window),
      mood(tx, accountId, period, window),
      sleep(tx, accountId, period, window)
    ).flatten
    InsightsOverview(
      rangeDays = period.days,
      rangeLabel = ApiCopy.rangeLabel(window),
      timeZone = zone.getId,
      ranges = Periods.map(_.days),
      blocks = blocks,
      notice = Option.when(blocks.isEmpty)(ApiCopy.noConsentedDomain)
    )

  // A refusal is a missing consent (or an account that is gone): the block is left out.
  private def medication(tx: Tx, accountId: UUID, period: StatsPeriod, window: DateRange): Option[InsightBlock] =
    tracking.stats
      .medication(tx, accountId, period)
      .toOption
      .map: view =>
        val byDay = DaySeries.of(view.period.days, window).shown.map(Charts.dosesByDay(_, window))
        val charts = Charts.doseOutcomes(view.period.total.buckets).toList ++ byDay.toList
        block(InsightDomain.Medication, StatsCopy.doseFigures(view.period.total), vessel = None, charts)

  private def hydration(tx: Tx, accountId: UUID, period: StatsPeriod, window: DateRange): Option[InsightBlock] =
    tracking.stats
      .hydration(tx, accountId, period)
      .toOption
      .map: view =>
        val chart = DaySeries.of(view.days, window).shown.map(Charts.waterByDay(_, window))
        block(InsightDomain.Hydration, StatsCopy.waterFigures(view.period), vesselOf(tx, accountId), chart.toList)

  private def mood(tx: Tx, accountId: UUID, period: StatsPeriod, window: DateRange): Option[InsightBlock] =
    tracking.stats
      .mood(tx, accountId, period)
      .toOption
      .map: view =>
        val chart = DaySeries.of(view.days, window).shown.map(Charts.moodByDay(_, window))
        block(InsightDomain.Mood, StatsCopy.moodFigures(view.period), vessel = None, chart.toList)

  private def sleep(tx: Tx, accountId: UUID, period: StatsPeriod, window: DateRange): Option[InsightBlock] =
    for
      view <- tracking.stats.sleep(tx, accountId, period).toOption
      goal = tracking.sleep.status(tx, accountId).toOption.flatMap(_.goalMinutes)
    yield
      val chart = DaySeries.of(view.days, window).shown.map(Charts.sleepByDay(_, window, goal))
      block(InsightDomain.Sleep, StatsCopy.sleepFigures(view.period), vessel = None, chart.toList)

  /** Today against the user's own goal; without a goal there is nothing to fill. */
  private def vesselOf(tx: Tx, accountId: UUID): Option[InsightVessel] =
    for
      today <- tracking.hydration.day(tx, accountId, EntryDay.Today).toOption
      goal <- today.goalMl
      recorded <- today.recorded
      label <- StatsCopy.waterDay(Some(recorded))
    yield InsightVessel(recorded.totalMl, goal, label)

object Insights:

  /** The periods an overview is computed for; one day is the Today screen, not a statistics period. */
  private val Periods: List[StatsPeriod] = List(StatsPeriod.Last7Days, StatsPeriod.Last30Days)

  private def periodOf(rangeDays: Int): Option[StatsPeriod] = Periods.find(_.days == rangeDays)

  private def block(
      domain: InsightDomain,
      lines: List[String],
      vessel: Option[InsightVessel],
      charts: List[Chart]
  ): InsightBlock =
    InsightBlock(
      id = domain,
      title = ApiCopy.insightTitle(domain),
      lines = lines,
      noDataText = Option.when(lines.isEmpty)(ApiCopy.noDataInPeriod),
      vessel = vessel,
      charts = if lines.isEmpty then Nil else charts
    )
