package dosegram.core.application.insights

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.GamificationCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.insights.BaselineStatements
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Comparisons
import dosegram.core.domain.insights.Consistency
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationPeriod
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.InsightEngine
import dosegram.core.domain.insights.InsightInputs
import dosegram.core.domain.insights.InsightList
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealPeriod
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MedicationPeriod
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.MonthStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodPeriod
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.NightFacts
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepPeriod
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.insights.TrendFrame
import dosegram.core.domain.insights.TrendPeriod
import dosegram.core.domain.insights.YearFigures
import dosegram.core.domain.insights.YearSources
import dosegram.core.domain.insights.YearStats
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
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

/** The sleep view of a period: the figures and the nights behind them, by sleep day (a day without a night is absent).
  */
final case class SleepPeriodView(zone: ZoneId, period: SleepPeriod, days: Map[LocalDate, SleepNight])

/** The medication view of a period in the account zone. Medications are identified by id only: the caller resolves a
  * name or a discreet label for its own surface.
  */
final case class MedicationPeriodView(zone: ZoneId, period: MedicationPeriod)

/** The meal view of a period: the counts and the meal days behind them (a day without a meal is absent). */
final case class MealPeriodView(zone: ZoneId, period: MealPeriod, days: Map[LocalDate, MealDay])

/** The text statistics of a period: the header line and one block of lines per domain that has consent and data. */
final case class PeriodSummary(zone: ZoneId, window: DateRange, lines: List[String])

/** A calendar month as every surface reads it: the figures and the zone they were computed in. */
final case class MonthView(zone: ZoneId, figures: MonthFigures)

/** A calendar year as `/stats year` and the one-year card read it: the figures and the zone they were computed in. */
final case class YearView(zone: ZoneId, today: LocalDate, figures: YearFigures)

/** The current insights of an account and whether cross-domain comparisons are on (VISUALS VA-IN-01). */
final case class InsightsView(zone: ZoneId, list: InsightList, crossDomain: Boolean)

/** The windows a comparison may run over (VISUALS_AND_ANALYTICS section 8.5): 28 local days by default. */
enum ComparisonWindow(val days: Int):
  case Days28 extends ComparisonWindow(28)
  case Days56 extends ComparisonWindow(56)
  case Days84 extends ComparisonWindow(84)

/** The read API of the statistics (ARCHITECTURE section 11.4): one place that the chat flows, the report renderer and
  * the Mini App API ask for a period. Every answer is computed per account from that account's rows, by the pure
  * functions of `core.domain.insights` over daily rollups that are refreshed before they are read. A derived figure is
  * a `Stat` or a `Comparison`, so "not enough data" reaches every surface as a state of its own. Every answer is read
  * on the caller's transaction, where the consents are checked as well.
  */
final class StatsQueryService(rollups: RollupService, clock: Clock):

  def medication(tx: Tx, accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MedicationPeriodView] =
    answer(tx, accountId, ConsentPurpose.Medication): zone =>
      MedicationPeriodView(zone, medicationPeriod(tx, accountId, zone, window(period, zone)))

  def hydration(tx: Tx, accountId: UUID, period: StatsPeriod): Either[StatsRefusal, HydrationPeriodView] =
    answer(tx, accountId, ConsentPurpose.Hydration)(zone => hydrationView(tx, accountId, zone, window(period, zone)))

  def mood(tx: Tx, accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MoodPeriodView] =
    answer(tx, accountId, ConsentPurpose.Mood)(zone => moodView(tx, accountId, zone, window(period, zone)))

  def sleep(tx: Tx, accountId: UUID, period: StatsPeriod): Either[StatsRefusal, SleepPeriodView] =
    answer(tx, accountId, ConsentPurpose.Sleep)(zone => sleepView(tx, accountId, zone, window(period, zone)))

  /** The account's current local day: where a dashboard period ([[dosegram.core.domain.insights.TrendPeriod]]) ends.
    */
  def today(tx: Tx, accountId: UUID): Either[StatsRefusal, LocalDate] =
    zoneOf(tx, accountId).map(zone => LocalDays.dateOf(clock.now(), zone))

  // The reads of any window of local days, for the Mini App dashboard and its previous period (MINI_APP MA-INS-3).

  def medicationOver(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, MedicationPeriodView] =
    answer(tx, accountId, ConsentPurpose.Medication)(zone =>
      MedicationPeriodView(zone, medicationPeriod(tx, accountId, zone, range))
    )

  def hydrationOver(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, HydrationPeriodView] =
    answer(tx, accountId, ConsentPurpose.Hydration)(zone => hydrationView(tx, accountId, zone, range))

  def moodOver(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, MoodPeriodView] =
    answer(tx, accountId, ConsentPurpose.Mood)(zone => moodView(tx, accountId, zone, range))

  def sleepOver(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, SleepPeriodView] =
    answer(tx, accountId, ConsentPurpose.Sleep)(zone => sleepView(tx, accountId, zone, range))

  def mealsOver(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, MealPeriodView] =
    answer(tx, accountId, ConsentPurpose.Nutrition): zone =>
      val days = mealDays(tx, accountId, range).filter((_, day) => day.meals > 0)
      MealPeriodView(zone, MealStats.period(days, range), days)

  /** The bedtime regularity figures of a window (VISUALS section 7.7), with the user's own goal on its last day. */
  def sleepRegularity(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, SleepRegularityFigures] =
    answer(tx, accountId, ConsentPurpose.Sleep): _ =>
      val goal = NightFacts.goalOn(range.end, tx.tracking.sleep.goals(accountId, range.end))
      SleepRegularity.view(tx.tracking.sleep.liveEpisodes(accountId, range), range, goal)

  /** The trends of a dashboard period (ROADMAP G9.4, G9.5): the one source of the chat's trends, the trends card and
    * the Mini App's Insights blocks. A domain without consent is absent (PRODUCT_SPEC R-IDC-9).
    */
  def trends(tx: Tx, accountId: UUID, period: TrendPeriod): Either[StatsRefusal, TrendsView] =
    zoneOf(tx, accountId).map: zone =>
      val today = LocalDays.dateOf(clock.now(), zone)
      val frame = TrendFrame.of(period, today)
      val countsOn = tx.accounts.gamificationOf(accountId)
      def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
      def both[A](read: DateRange => A): (A, A) = read(frame.current) -> read(frame.previous)
      val blocks = List(
        Option.when(granted(ConsentPurpose.Medication)):
          val (current, previous) = both(medicationPeriod(tx, accountId, zone, _))
          TrendBlocks.medication(frame, current, previous)
        ,
        Option.when(granted(ConsentPurpose.Hydration)):
          val (current, previous) = both(hydrationView(tx, accountId, zone, _))
          TrendBlocks.hydration(frame, current.period, current.days, previous.period, countsOn)
        ,
        Option.when(granted(ConsentPurpose.Mood)):
          val (current, previous) = both(moodView(tx, accountId, zone, _))
          TrendBlocks.mood(frame, current.period, current.days, previous.period, countsOn)
        ,
        Option.when(granted(ConsentPurpose.Sleep)):
          val (current, previous) = both(sleepView(tx, accountId, zone, _))
          val goal = NightFacts.goalOn(frame.current.end, tx.tracking.sleep.goals(accountId, frame.current.end))
          val regularity =
            SleepRegularity.view(tx.tracking.sleep.liveEpisodes(accountId, frame.current), frame.current, goal)
          TrendBlocks.sleep(frame, current.period, current.days, regularity, previous.period, countsOn)
        ,
        Option.when(granted(ConsentPurpose.Nutrition)):
          val (current, previous) = both(mealDays(tx, accountId, _).filter((_, day) => day.meals > 0))
          TrendBlocks.nutrition(
            frame,
            MealStats.period(current, frame.current),
            current,
            MealStats.period(previous, frame.previous)
          )
      ).flatten
      val weekCounts = if countsOn then weekSoFar(tx, accountId, today).headOption else None
      TrendsView(zone, frame, blocks, weekCounts)

  private def hydrationView(tx: Tx, accountId: UUID, zone: ZoneId, range: DateRange): HydrationPeriodView =
    val days = hydrationDays(tx, accountId, range)
    HydrationPeriodView(zone, HydrationStats.period(days, range), days)

  private def moodView(tx: Tx, accountId: UUID, zone: ZoneId, range: DateRange): MoodPeriodView =
    val days = moodDays(tx, accountId, range)
    MoodPeriodView(zone, MoodStats.period(days, range), days)

  private def sleepView(tx: Tx, accountId: UUID, zone: ZoneId, range: DateRange): SleepPeriodView =
    val days = sleepDays(tx, accountId, range)
    SleepPeriodView(zone, SleepStats.period(days, range), days)

  /** The nights of a range of sleep days, for a calendar or a chart of days: a day without a night is absent. */
  def sleepCalendar(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, Map[LocalDate, SleepNight]] =
    answer(tx, accountId, ConsentPurpose.Sleep)(_ => sleepDays(tx, accountId, range))

  /** The recorded days of a range of local days, for a calendar or a chart of days: a day without a dose is absent. */
  def medicationCalendar(
      tx: Tx,
      accountId: UUID,
      range: DateRange
  ): Either[StatsRefusal, Map[LocalDate, MedicationDay]] =
    answer(tx, accountId, ConsentPurpose.Medication)(_ => medicationDays(tx, accountId, range))

  /** The recorded days of a range of local days: a day without an entry is absent, never a zero. */
  def hydrationCalendar(
      tx: Tx,
      accountId: UUID,
      range: DateRange
  ): Either[StatsRefusal, Map[LocalDate, HydrationDay]] =
    answer(tx, accountId, ConsentPurpose.Hydration)(_ => hydrationDays(tx, accountId, range))

  /** The check-in days of a range of local days: a day without a check-in is absent. */
  def moodCalendar(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, Map[LocalDate, MoodDay]] =
    answer(tx, accountId, ConsentPurpose.Mood)(_ => moodDays(tx, accountId, range))

  /** The meal days of a range of local days: a day without a meal is absent. */
  def mealCalendar(tx: Tx, accountId: UUID, range: DateRange): Either[StatsRefusal, Map[LocalDate, MealDay]] =
    answer(tx, accountId, ConsentPurpose.Nutrition)(_ =>
      mealDays(tx, accountId, range).filter((_, day) => day.meals > 0)
    )

  /** The text of `/stats` for a period. A domain without consent is left out, and so is a domain without data; a period
    * with nothing at all says so.
    */
  def summary(
      tx: Tx,
      accountId: UUID,
      period: StatsPeriod,
      endingOn: Option[LocalDate] = None
  ): Either[StatsRefusal, PeriodSummary] =
    zoneOf(tx, accountId).map: zone =>
      val range = endingOn.fold(window(period, zone))(period.window)
      val countsOn = tx.accounts.gamificationOf(accountId)
      def block(purpose: ConsentPurpose)(lines: => List[String]): List[String] =
        if tx.consents.isGranted(accountId, purpose) then lines else Nil
      val blocks =
        block(ConsentPurpose.Medication)(doseLines(tx, accountId, range)) ++
          block(ConsentPurpose.Hydration)(waterLines(tx, accountId, period, range, countsOn)) ++
          block(ConsentPurpose.Mood)(moodLines(tx, accountId, period, range, countsOn)) ++
          block(ConsentPurpose.Sleep)(sleepLines(tx, accountId, period, range, countsOn)) ++
          block(ConsentPurpose.Nutrition)(foodLines(tx, accountId, period, range))
      val weekLine =
        if period == StatsPeriod.Today && countsOn then weekSoFar(tx, accountId, range.end) else Nil
      val body = if blocks.isEmpty then List(StatsCopy.Empty) else blocks
      PeriodSummary(zone, range, StatsCopy.header(period, zone) :: body ++ weekLine)

  /** One finished local day in one line per domain, for the morning summary (supervisor decision, 2026-11-27): the
    * doses taken of those due, water against the user's own goal, mood, the night that ended that day, and the meals. A
    * domain without consent or without a record that day has no line; a day with nothing has no lines at all.
    */
  def dayLines(tx: Tx, accountId: UUID, date: LocalDate): List[String] =
    val day = DateRange.single(date)
    def block(purpose: ConsentPurpose)(lines: => List[String]): List[String] =
      if tx.consents.isGranted(accountId, purpose) then lines else Nil
    block(ConsentPurpose.Medication)(doseLines(tx, accountId, day).take(1)) ++
      block(ConsentPurpose.Hydration)(hydrationDays(tx, accountId, day).get(date).map(ReportCopy.dayWater).toList) ++
      block(ConsentPurpose.Mood)(moodDays(tx, accountId, day).get(date).map(ReportCopy.dayMood).toList) ++
      block(ConsentPurpose.Sleep)(sleepDays(tx, accountId, day).get(date).map(ReportCopy.daySleep).toList) ++
      block(ConsentPurpose.Nutrition)(foodLines(tx, accountId, StatsPeriod.Today, day))

  /** IR-04: mood on the days the user's own water goal was reached against the other recorded days. It runs only with
    * the `cross_domain_patterns` consent on top of both domain consents (VISUALS VA-IN-01).
    */
  def waterGoalAndMood(tx: Tx, accountId: UUID, window: ComparisonWindow): Either[StatsRefusal, Comparison] =
    val purposes = List(ConsentPurpose.CrossDomainPatterns, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    purposes.find(!tx.consents.isGranted(accountId, _)) match
      case Some(missing) => Left(StatsRefusal.ConsentMissing(missing))
      case None          =>
        zoneOf(tx, accountId).map: zone =>
          val range = DateRange.endingOn(LocalDays.dateOf(clock.now(), zone), window.days)
          Comparisons.waterGoalAndMood(hydrationDays(tx, accountId, range), moodDays(tx, accountId, range), range)

  /** A calendar month (VISUALS section 6.6): one block per domain whose consent is active, a domain without consent is
    * absent. The previous month's medication days are the only baseline. Weeks start on Monday until the account has a
    * week-start setting.
    */
  def month(tx: Tx, accountId: UUID, month: YearMonth): Either[StatsRefusal, MonthView] =
    zoneOf(tx, accountId).map: zone =>
      val range = MonthStats.window(month)
      def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
      val medication = Option.when(granted(ConsentPurpose.Medication))(
        medicationDays(tx, accountId, range) -> dosesOf(tx, accountId, zone, range)
      )
      val previous = Option.when(granted(ConsentPurpose.Medication))(
        medicationDays(tx, accountId, MonthStats.window(month.minusMonths(1)))
      )
      MonthView(
        zone,
        MonthStats.of(
          month,
          DayOfWeek.MONDAY,
          medication,
          Option.when(granted(ConsentPurpose.Hydration))(hydrationDays(tx, accountId, range)),
          Option.when(granted(ConsentPurpose.Mood))(moodDays(tx, accountId, range)),
          Option.when(granted(ConsentPurpose.Sleep))(sleepDays(tx, accountId, range)),
          previous,
          Option.when(granted(ConsentPurpose.Nutrition))(mealDays(tx, accountId, range))
        )
      )

  /** A calendar year (supervisor decision, 2026-11-28, analytics round 6): one row per area whose consent is active,
    * read once over the year and the year before it, the only baseline. A year after the current one is the current
    * one.
    */
  def year(tx: Tx, accountId: UUID, year: Int): Either[StatsRefusal, YearView] =
    zoneOf(tx, accountId).map: zone =>
      val today = LocalDays.dateOf(clock.now(), zone)
      val shown = Math.min(year, today.getYear)
      val read = DateRange.between(LocalDate.of(shown - 1, 1, 1), YearStats.window(shown, today).end)
      def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
      val sources = YearSources(
        medication = Option.when(granted(ConsentPurpose.Medication))(medicationDays(tx, accountId, read)),
        hydration = Option.when(granted(ConsentPurpose.Hydration))(hydrationDays(tx, accountId, read)),
        mood = Option.when(granted(ConsentPurpose.Mood))(moodDays(tx, accountId, read)),
        sleep = Option.when(granted(ConsentPurpose.Sleep))(sleepDays(tx, accountId, read)),
        food = Option.when(granted(ConsentPurpose.Nutrition))(mealDays(tx, accountId, read))
      )
      YearView(zone, today, YearStats.of(shown, today, sources))

  /** The current insights (VISUALS section 8.5): every rule of the registry whose sources have consent, over the last
    * `window` local days; the baseline statements read the last five weeks. Cross-domain rules run only with the
    * `cross_domain_patterns` consent; the timing statement and the baselines are single-domain.
    */
  def insights(tx: Tx, accountId: UUID, window: ComparisonWindow): Either[StatsRefusal, InsightsView] =
    zoneOf(tx, accountId).map: zone =>
      val today = LocalDays.dateOf(clock.now(), zone)
      val range = DateRange.endingOn(today, window.days)
      // One read covers the window and the five weeks of the baselines.
      val read = DateRange.endingOn(today, Math.max(window.days, BaselineStatements.window(today).days))
      def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
      val crossDomain = granted(ConsentPurpose.CrossDomainPatterns)
      val inputs = InsightInputs(
        today = today,
        window = range,
        zone = zone,
        hydration = Option.when(granted(ConsentPurpose.Hydration))(hydrationDays(tx, accountId, read)),
        mood = Option.when(granted(ConsentPurpose.Mood))(moodDays(tx, accountId, read)),
        nights = Option.when(granted(ConsentPurpose.Sleep))(
          NightFacts.of(tx.tracking.sleep.liveEpisodes(accountId, read), tx.tracking.sleep.goals(accountId, today))
        ),
        doses = Option.when(granted(ConsentPurpose.Medication))(dosesOf(tx, accountId, zone, range)),
        // Only IR-06 reads the work days, and it runs only with the cross-domain consent.
        workDays = if crossDomain then workDaysOf(tx, accountId) else DayProfile.Default.work.days,
        crossDomain = crossDomain,
        // Only IR-05 reads the meals, and it runs only with the cross-domain consent.
        meals = Option.when(crossDomain && granted(ConsentPurpose.Nutrition))(mealDays(tx, accountId, range)),
        mealPlan = tx.tracking.meals.plan(accountId).getOrElse(MealPlan.Default)
      )
      InsightsView(zone, InsightEngine.evaluate(inputs), crossDomain)

  /** The user's own work days from the day profile, Monday to Friday until the user set them (VISUALS IR-06). */
  private def workDaysOf(tx: Tx, accountId: UUID): Set[DayOfWeek] =
    tx.reminders.dayProfiles
      .anchors(accountId)
      .fold(DayProfile.Default.work.days)(_.work.days)

  private def answer[A](tx: Tx, accountId: UUID, purpose: ConsentPurpose)(f: ZoneId => A): Either[StatsRefusal, A] =
    if !tx.consents.isGranted(accountId, purpose) then Left(StatsRefusal.ConsentMissing(purpose))
    else zoneOf(tx, accountId).map(f)

  private def zoneOf(tx: Tx, accountId: UUID): Either[StatsRefusal, ZoneId] =
    tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(StatsRefusal.UnknownAccount)

  private def window(period: StatsPeriod, zone: ZoneId): DateRange =
    period.window(LocalDays.dateOf(clock.now(), zone))

  private def doseLines(tx: Tx, accountId: UUID, range: DateRange): List[String] =
    val days = medicationDays(tx, accountId, range)
    StatsCopy.doses(MedicationStats.figures(MedicationStats.total(days.values), range))

  private def waterLines(
      tx: Tx,
      accountId: UUID,
      period: StatsPeriod,
      range: DateRange,
      countsOn: Boolean
  ): List[String] =
    val days = hydrationDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today                              => StatsCopy.waterDay(days.get(range.end))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days =>
        val figures = HydrationStats.period(days, range)
        if countsOn then StatsCopy.water(figures) else StatsCopy.waterOmittingGates(figures)
    line.toList

  private def moodLines(
      tx: Tx,
      accountId: UUID,
      period: StatsPeriod,
      range: DateRange,
      countsOn: Boolean
  ): List[String] =
    val days = moodDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today                              => StatsCopy.moodDay(days.get(range.end))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days =>
        val figures = MoodStats.period(days, range)
        if countsOn then StatsCopy.mood(figures) else StatsCopy.moodOmittingGates(figures)
    line.toList

  private def sleepLines(
      tx: Tx,
      accountId: UUID,
      period: StatsPeriod,
      range: DateRange,
      countsOn: Boolean
  ): List[String] =
    val days = sleepDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today                              => StatsCopy.sleepDay(days.get(range.end))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days =>
        val figures = SleepStats.period(days, range)
        if countsOn then StatsCopy.sleep(figures) else StatsCopy.sleepOmittingGates(figures)
    line.toList

  /** Hit days of the Monday week that contains `today`, out of all 7 days (R-GAM-1). Nothing when no counted domain has
    * a record in that week.
    */
  private def weekSoFar(tx: Tx, accountId: UUID, today: LocalDate): List[String] =
    val start = today.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val week = DateRange.between(start, start.plusDays(6))
    val recorded = ReportComposer.logging(recordedDays(tx, accountId, week))
    GamificationCopy.consistency(Consistency.weekly(recorded, week)).toList

  /** Today: the regular-meal view by the user's own plan; a period: the meals and the days per main meal. A period
    * without a meal has no line.
    */
  private def foodLines(tx: Tx, accountId: UUID, period: StatsPeriod, range: DateRange): List[String] =
    val days = mealDays(tx, accountId, range)
    val line = period match
      case StatsPeriod.Today =>
        days
          .get(range.end)
          .map: day =>
            val plan = tx.tracking.meals.plan(accountId).getOrElse(MealPlan.Default)
            FoodCopy.statsDay(MealStats.regular(range.end, Some(day), plan))
      case StatsPeriod.Last7Days | StatsPeriod.Last30Days => FoodCopy.statsPeriod(MealStats.period(days, range))
    line.toList

  /** The days of the range with a record, per domain whose consent is active: the hit days of VISUALS section 7.3. A
    * sleep day counts with a night. Read at every use; nothing is stored.
    */
  def recordedDays(tx: Tx, accountId: UUID, range: DateRange): Map[ConsentPurpose, Set[LocalDate]] =
    def when(purpose: ConsentPurpose)(days: => Iterable[LocalDate]): Option[(ConsentPurpose, Set[LocalDate])] =
      Option.when(tx.consents.isGranted(accountId, purpose))(purpose -> days.toSet)
    List(
      when(ConsentPurpose.Medication)(medicationDays(tx, accountId, range).filter((_, d) => !d.total.isEmpty).keys),
      when(ConsentPurpose.Hydration)(hydrationDays(tx, accountId, range).filter((_, d) => d.entries > 0).keys),
      when(ConsentPurpose.Mood)(moodDays(tx, accountId, range).filter((_, d) => d.n > 0).keys),
      when(ConsentPurpose.Sleep)(sleepDays(tx, accountId, range).keys),
      when(ConsentPurpose.Nutrition)(mealDays(tx, accountId, range).filter((_, d) => d.meals > 0).keys)
    ).flatten.toMap

  /** The acts of logging up to `upTo` in each non-medication domain with consent: water entries, mood check-ins, nights
    * and naps, meals and snacks (the milestones of VISUALS section 7.3). Medication has none (R-GAM-2).
    */
  def loggingCounts(tx: Tx, accountId: UUID, upTo: LocalDate): Map[ConsentPurpose, Int] =
    val ever = DateRange.between(StatsQueryService.FirstDay, upTo)
    def when(purpose: ConsentPurpose)(count: => Int): Option[(ConsentPurpose, Int)] =
      Option.when(tx.consents.isGranted(accountId, purpose))(purpose -> count)
    List(
      when(ConsentPurpose.Hydration)(hydrationDays(tx, accountId, ever).values.map(_.entries).sum),
      when(ConsentPurpose.Mood)(moodDays(tx, accountId, ever).values.map(_.n).sum),
      when(ConsentPurpose.Sleep)(tx.tracking.sleep.liveEpisodes(accountId, ever).size),
      when(ConsentPurpose.Nutrition)(mealDays(tx, accountId, ever).values.map(_.meals).sum)
    ).flatten.toMap

  /** The earliest logged local day up to `upTo` in each non-medication domain with consent. The one-year milestone
    * reads it; nothing is stored. Medication has none (R-GAM-2).
    */
  def firstLogged(tx: Tx, accountId: UUID, upTo: LocalDate): Map[ConsentPurpose, LocalDate] =
    val ever = DateRange.between(StatsQueryService.FirstDay, upTo)
    def earliest(purpose: ConsentPurpose)(date: => Option[LocalDate]): Option[(ConsentPurpose, LocalDate)] =
      Option.when(tx.consents.isGranted(accountId, purpose))(date).flatten.map(purpose -> _)
    List(
      earliest(ConsentPurpose.Hydration)(
        hydrationDays(tx, accountId, ever).filter((_, day) => day.entries > 0).keys.minOption
      ),
      earliest(ConsentPurpose.Mood)(moodDays(tx, accountId, ever).filter((_, day) => day.n > 0).keys.minOption),
      earliest(ConsentPurpose.Sleep)(tx.tracking.sleep.liveEpisodes(accountId, ever).map(_.sleepDay).minOption),
      earliest(ConsentPurpose.Nutrition)(
        mealDays(tx, accountId, ever).filter((_, day) => day.meals > 0).keys.minOption
      )
    ).flatten.toMap

  private def mealDays(tx: Tx, accountId: UUID, range: DateRange): Map[LocalDate, MealDay] =
    rollups.refresh(tx, accountId, RollupDomain.Nutrition, range)
    tx.tracking.rollups
      .list(accountId, RollupDomain.Nutrition, range)
      .collect { case (date, RollupMetrics.Nutrition(day)) => date -> day }

  // Sleep has no rollup: a sleep day holds one night, read straight from the episodes.
  private def sleepDays(tx: Tx, accountId: UUID, range: DateRange): Map[LocalDate, SleepNight] =
    SleepStats.byDay(tx.tracking.sleep.liveEpisodes(accountId, range))

  private def medicationPeriod(tx: Tx, accountId: UUID, zone: ZoneId, range: DateRange): MedicationPeriod =
    MedicationStats.period(medicationDays(tx, accountId, range), dosesOf(tx, accountId, zone, range), range)

  private def medicationDays(tx: Tx, accountId: UUID, range: DateRange): Map[LocalDate, MedicationDay] =
    rollups.refresh(tx, accountId, RollupDomain.Medication, range)
    tx.tracking.rollups
      .list(accountId, RollupDomain.Medication, range)
      .collect { case (date, RollupMetrics.Medication(day)) => date -> day }

  /** The single doses attributed to the days of the range: the samples of the delay statistics. */
  private def dosesOf(tx: Tx, accountId: UUID, zone: ZoneId, range: DateRange): List[AdherenceInput] =
    tx.tracking.doseHistory
      .attributableBetween(accountId, LocalDays.startOf(range.start, zone), LocalDays.endOf(range.end, zone))
      .map(_.input)
      .filter(input => MedicationStats.dayOf(input, zone).exists(range.contains))

  private def hydrationDays(tx: Tx, accountId: UUID, range: DateRange): Map[LocalDate, HydrationDay] =
    rollups.refresh(tx, accountId, RollupDomain.Hydration, range)
    tx.tracking.rollups
      .list(accountId, RollupDomain.Hydration, range)
      .collect { case (date, RollupMetrics.Hydration(day)) => date -> day }

  private def moodDays(tx: Tx, accountId: UUID, range: DateRange): Map[LocalDate, MoodDay] =
    rollups.refresh(tx, accountId, RollupDomain.Mood, range)
    tx.tracking.rollups
      .list(accountId, RollupDomain.Mood, range)
      .collect { case (date, RollupMetrics.Mood(day)) => date -> day }
end StatsQueryService

object StatsQueryService:

  /** The first day an act of logging is counted from: earlier than any record the product holds. */
  val FirstDay: LocalDate = LocalDate.of(2000, 1, 1)
