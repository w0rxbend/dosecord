package dosegram.core.application.insights

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Comparisons
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationPeriod
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MedicationPeriod
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodPeriod
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentCheck
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.TrackingTx
import dosegram.core.ports.TrackingUnitOfWork

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Why a statistics request was not answered. */
enum StatsRefusal:
  case UnknownAccount

  /** No active consent for the purpose: the data of that domain is not shown (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing(purpose: ConsentPurpose)

/** The hydration view of a period: the figures and the recorded days behind them (a day without an entry is absent). */
final case class HydrationPeriodView(zone: ZoneId, period: HydrationPeriod, days: Map[LocalDate, HydrationDay])

/** The mood view of a period: the figures and the check-in days behind them (a day without a check-in is absent). */
final case class MoodPeriodView(zone: ZoneId, period: MoodPeriod, days: Map[LocalDate, MoodDay])

/** The medication view of a period in the account zone. Medications are identified by id only: the caller resolves a
  * name or a discreet label for its own surface.
  */
final case class MedicationPeriodView(zone: ZoneId, period: MedicationPeriod)

/** The text statistics of a period: the header line and one block of lines per domain that has consent and data. */
final case class PeriodSummary(zone: ZoneId, window: DateRange, lines: List[String])

/** The windows a comparison may run over (VISUALS_AND_ANALYTICS section 8.5): 28 local days by default. */
enum ComparisonWindow(val days: Int):
  case Days28 extends ComparisonWindow(28)
  case Days56 extends ComparisonWindow(56)
  case Days84 extends ComparisonWindow(84)

/** The read API of the statistics (ARCHITECTURE section 11.4): one place that the chat flows, the report renderer and
  * the Mini App API ask for a period. Every answer is computed per account from that account's rows, by the pure
  * functions of `core.domain.insights` over daily rollups that are refreshed before they are read. A derived figure is
  * a `Stat` or a `Comparison`, so "not enough data" reaches every surface as a state of its own.
  */
final class StatsQueryService(uow: TrackingUnitOfWork, rollups: RollupService, consent: ConsentCheck, clock: Clock):

  def medication(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MedicationPeriodView] =
    answer(accountId, ConsentPurpose.Medication): (tx, zone) =>
      MedicationPeriodView(zone, medicationPeriod(tx, accountId, zone, window(period, zone)))

  def hydration(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, HydrationPeriodView] =
    answer(accountId, ConsentPurpose.Hydration): (tx, zone) =>
      val range = window(period, zone)
      val days = hydrationDays(tx, accountId, range)
      HydrationPeriodView(zone, HydrationStats.period(days, range), days)

  def mood(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MoodPeriodView] =
    answer(accountId, ConsentPurpose.Mood): (tx, zone) =>
      val range = window(period, zone)
      val days = moodDays(tx, accountId, range)
      MoodPeriodView(zone, MoodStats.period(days, range), days)

  /** The text of `/stats` for a period. A domain without consent is left out, and so is a domain without data; a period
    * with nothing at all says so.
    */
  def summary(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, PeriodSummary] =
    uow.transaction: tx =>
      tx.accountZone(accountId)
        .toRight(StatsRefusal.UnknownAccount)
        .map: zone =>
          val range = window(period, zone)
          val blocks =
            block(accountId, ConsentPurpose.Medication)(doseLines(tx, accountId, range)) ++
              block(accountId, ConsentPurpose.Hydration)(waterLines(tx, accountId, period, range)) ++
              block(accountId, ConsentPurpose.Mood)(moodLines(tx, accountId, period, range))
          val body = if blocks.isEmpty then List(StatsCopy.Empty) else blocks
          PeriodSummary(zone, range, StatsCopy.header(period, zone) :: body)

  /** IR-04: mood on the days the user's own water goal was reached against the other recorded days. It runs only with
    * the `cross_domain_patterns` consent on top of both domain consents (VISUALS VA-IN-01).
    */
  def waterGoalAndMood(accountId: UUID, window: ComparisonWindow): Either[StatsRefusal, Comparison] =
    val purposes = List(ConsentPurpose.CrossDomainPatterns, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    purposes.find(!consent.isGranted(accountId, _)) match
      case Some(missing) => Left(StatsRefusal.ConsentMissing(missing))
      case None          =>
        uow.transaction: tx =>
          tx.accountZone(accountId)
            .toRight(StatsRefusal.UnknownAccount)
            .map: zone =>
              val range = DateRange.endingOn(LocalDays.dateOf(clock.now(), zone), window.days)
              Comparisons.waterGoalAndMood(hydrationDays(tx, accountId, range), moodDays(tx, accountId, range), range)

  private def answer[A](accountId: UUID, purpose: ConsentPurpose)(
      f: (TrackingTx, ZoneId) => A
  ): Either[StatsRefusal, A] =
    if !consent.isGranted(accountId, purpose) then Left(StatsRefusal.ConsentMissing(purpose))
    else uow.transaction(tx => tx.accountZone(accountId).toRight(StatsRefusal.UnknownAccount).map(f(tx, _)))

  private def block(accountId: UUID, purpose: ConsentPurpose)(lines: => List[String]): List[String] =
    if consent.isGranted(accountId, purpose) then lines else Nil

  private def window(period: StatsPeriod, zone: ZoneId): DateRange =
    period.window(LocalDays.dateOf(clock.now(), zone))

  private def doseLines(tx: TrackingTx, accountId: UUID, range: DateRange): List[String] =
    val days = medicationDays(tx, accountId, range)
    StatsCopy.doses(MedicationStats.figures(MedicationStats.total(days.values), range))

  private def waterLines(tx: TrackingTx, accountId: UUID, period: StatsPeriod, range: DateRange): List[String] =
    val days = hydrationDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today                              => StatsCopy.waterDay(days.get(range.end))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days => StatsCopy.water(HydrationStats.period(days, range))
    line.toList

  private def moodLines(tx: TrackingTx, accountId: UUID, period: StatsPeriod, range: DateRange): List[String] =
    val days = moodDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today                              => StatsCopy.moodDay(days.get(range.end))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days => StatsCopy.mood(MoodStats.period(days, range))
    line.toList

  private def medicationPeriod(tx: TrackingTx, accountId: UUID, zone: ZoneId, range: DateRange): MedicationPeriod =
    MedicationStats.period(medicationDays(tx, accountId, range), dosesOf(tx, accountId, zone, range), range)

  private def medicationDays(tx: TrackingTx, accountId: UUID, range: DateRange): Map[LocalDate, MedicationDay] =
    rollups.refresh(tx, accountId, RollupDomain.Medication, range)
    tx.rollups
      .list(accountId, RollupDomain.Medication, range)
      .collect { case (date, RollupMetrics.Medication(day)) => date -> day }

  /** The single doses attributed to the days of the range: the samples of the delay statistics. */
  private def dosesOf(tx: TrackingTx, accountId: UUID, zone: ZoneId, range: DateRange): List[AdherenceInput] =
    tx.doseHistory
      .attributableBetween(accountId, LocalDays.startOf(range.start, zone), LocalDays.endOf(range.end, zone))
      .map(_.input)
      .filter(input => MedicationStats.dayOf(input, zone).exists(range.contains))

  private def hydrationDays(tx: TrackingTx, accountId: UUID, range: DateRange): Map[LocalDate, HydrationDay] =
    rollups.refresh(tx, accountId, RollupDomain.Hydration, range)
    tx.rollups
      .list(accountId, RollupDomain.Hydration, range)
      .collect { case (date, RollupMetrics.Hydration(day)) => date -> day }

  private def moodDays(tx: TrackingTx, accountId: UUID, range: DateRange): Map[LocalDate, MoodDay] =
    rollups.refresh(tx, accountId, RollupDomain.Mood, range)
    tx.rollups
      .list(accountId, RollupDomain.Mood, range)
      .collect { case (date, RollupMetrics.Mood(day)) => date -> day }
end StatsQueryService
