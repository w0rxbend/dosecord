package dosegram.api

import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork

import java.time.ZoneId
import java.util.UUID

/** The read side of the tracking domains that the API shows (ARCHITECTURE section 11.4). The composition root builds
  * the services; this module never sees their storage (DEP-05).
  */
final case class TrackingReads(stats: StatsQueryService, hydration: HydrationService)

/** `GET /insights/overview` (ARCHITECTURE section 9.5): the statistics of a period, one block per domain whose consent
  * is active. Every line is written by the statistics copy of the core, so the app shows the sentences of the chat and
  * composes none of its own (MINI_APP section 15.1). A domain without consent is absent, not empty (PRODUCT_SPEC
  * R-IDC-9).
  */
final class Insights(uow: UnitOfWork, tracking: TrackingReads, clock: Clock):

  import Insights.*

  def overview(accountId: UUID, rangeDays: Int): Either[ApiFailure, InsightsOverview] =
    periodOf(rangeDays).toRight(ApiFailure.invalid("range", "invalid")).map(overviewOf(accountId, _))

  private def overviewOf(accountId: UUID, period: StatsPeriod): InsightsOverview =
    val zone = ZoneId.of(uow.transaction(_.accounts.timezoneOf(accountId)).getOrElse("UTC"))
    val window = period.window(LocalDays.dateOf(clock.now(), zone))
    val blocks = List(medication(accountId, period), hydration(accountId, period), mood(accountId, period)).flatten
    InsightsOverview(
      rangeDays = period.days,
      rangeLabel = ApiCopy.rangeLabel(window),
      timeZone = zone.getId,
      ranges = Periods.map(_.days),
      blocks = blocks,
      notice = Option.when(blocks.isEmpty)(ApiCopy.noConsentedDomain)
    )

  // A refusal is a missing consent (or an account that is gone): the block is left out.
  private def medication(accountId: UUID, period: StatsPeriod): Option[InsightBlock] =
    tracking.stats
      .medication(accountId, period)
      .toOption
      .map(view => block(InsightDomain.Medication, StatsCopy.doseFigures(view.period.total), vessel = None))

  private def hydration(accountId: UUID, period: StatsPeriod): Option[InsightBlock] =
    tracking.stats
      .hydration(accountId, period)
      .toOption
      .map(view => block(InsightDomain.Hydration, StatsCopy.waterFigures(view.period), vesselOf(accountId)))

  private def mood(accountId: UUID, period: StatsPeriod): Option[InsightBlock] =
    tracking.stats
      .mood(accountId, period)
      .toOption
      .map(view => block(InsightDomain.Mood, StatsCopy.moodFigures(view.period), vessel = None))

  /** Today against the user's own goal; without a goal there is nothing to fill. */
  private def vesselOf(accountId: UUID): Option[InsightVessel] =
    for
      today <- tracking.hydration.day(accountId, EntryDay.Today).toOption
      goal <- today.goalMl
      recorded <- today.recorded
      label <- StatsCopy.waterDay(Some(recorded))
    yield InsightVessel(recorded.totalMl, goal, label)

object Insights:

  /** The periods an overview is computed for; one day is the Today screen, not a statistics period. */
  private val Periods: List[StatsPeriod] = List(StatsPeriod.Last7Days, StatsPeriod.Last30Days)

  private def periodOf(rangeDays: Int): Option[StatsPeriod] = Periods.find(_.days == rangeDays)

  private def block(domain: InsightDomain, lines: List[String], vessel: Option[InsightVessel]): InsightBlock =
    InsightBlock(
      id = domain,
      title = ApiCopy.insightTitle(domain),
      lines = lines,
      noDataText = Option.when(lines.isEmpty)(ApiCopy.noDataInPeriod),
      vessel = vessel
    )
