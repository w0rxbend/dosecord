package dosegram.core.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** One week row of a month: the days of a locale week that fall inside the month, with each domain's figure behind the
  * weekly mean gate. A week below its gate is drawn as a dotted slot, never as a zero (VISUALS VA-PR-03).
  */
final case class MonthWeekFigures(
    days: DateRange,
    doses: DoseBuckets,
    waterMeanMl: Stat[Int],
    moodMean: Stat[Double],
    sleepMeanMinutes: Stat[Double]
)

/** One domain of a month: the days with a record and the period figures. `meetsGate` is the section gate of
  * PRODUCT_SPEC R-INS-11, records on at least 14 days of the month.
  */
final case class MonthDomain[+P](recordedDays: Int, figures: P):
  def meetsGate: Boolean = recordedDays >= MinimumSample.MonthlyReportDays.required
  def missingDays: Int = Math.max(0, MinimumSample.MonthlyReportDays.required - recordedDays)

/** The figures of a calendar month (VISUALS section 6.6, IMG-05): one block per domain whose consent is active (`None`
  * otherwise), the week rows, the days behind the calendars, and the user's own previous month as the only baseline.
  */
final case class MonthFigures(
    month: YearMonth,
    window: DateRange,
    weeks: List[MonthWeekFigures],
    daysWithRecords: Int,
    medication: Option[MonthDomain[MedicationPeriod]],
    hydration: Option[MonthDomain[HydrationPeriod]],
    mood: Option[MonthDomain[MoodPeriod]],
    sleep: Option[MonthDomain[SleepPeriod]],
    hydrationDays: Map[LocalDate, HydrationDay],
    moodDays: Map[LocalDate, MoodDay],
    sleepDays: Map[LocalDate, SleepNight],
    previousAdherence: Stat[Ratio],
    food: Option[MonthDomain[MealPeriod]] = None,
    mealDays: Map[LocalDate, MealDay] = Map.empty
):
  /** PRODUCT_SPEC R-INS-11: a month in which no domain meets the gate produces no report. */
  def isReportable: Boolean =
    List(medication, hydration, mood, sleep, food).flatten.exists(_.meetsGate)

/** The month aggregation of VISUALS_AND_ANALYTICS section 6.6 over the same daily figures as every other period: each
  * figure is one of the pure functions of this package over the days of the month, so the month agrees with "the last
  * 30 days" over the same days.
  */
object MonthStats:

  /** The local days of `month`. */
  def window(month: YearMonth): DateRange = DateRange.between(month.atDay(1), month.atEndOfMonth)

  /** The week rows of a month: weeks start on `weekStart`, the first and the last are cut at the month's edges. */
  def weeks(month: YearMonth, weekStart: DayOfWeek): List[DateRange] =
    val first = month.atDay(1).`with`(TemporalAdjusters.previousOrSame(weekStart))
    Iterator
      .iterate(first)(_.plusWeeks(1))
      .takeWhile(!_.isAfter(month.atEndOfMonth))
      .map(start => DateRange.between(maxDate(start, month.atDay(1)), minDate(start.plusDays(6), month.atEndOfMonth)))
      .toList

  /** Each source is `None` without its consent. `previousMedication` holds the previous month's medication days. A meal
    * day counts as recorded only with a live meal.
    */
  def of(
      month: YearMonth,
      weekStart: DayOfWeek,
      medication: Option[(Map[LocalDate, MedicationDay], List[dosegram.core.domain.AdherenceInput])],
      hydration: Option[Map[LocalDate, HydrationDay]],
      mood: Option[Map[LocalDate, MoodDay]],
      sleep: Option[Map[LocalDate, SleepNight]],
      previousMedication: Option[Map[LocalDate, MedicationDay]],
      food: Option[Map[LocalDate, MealDay]] = None
  ): MonthFigures =
    val range = window(month)
    def inMonth[A](days: Map[LocalDate, A]): Map[LocalDate, A] = days.view.filterKeys(range.contains).toMap
    val medicationDays = medication.map((days, _) => inMonth(days).filter((_, day) => !day.total.isEmpty))
    val hydrationDays = hydration.map(inMonth)
    val moodDays = mood.map(inMonth)
    val sleepDays = sleep.map(inMonth)
    val mealDays = food.map(days => inMonth(days).filter((_, day) => day.meals > 0))
    val recorded =
      List(medicationDays, hydrationDays, moodDays, sleepDays, mealDays).flatten.flatMap(_.keySet).toSet
    val previousWindow = window(month.minusMonths(1))
    MonthFigures(
      month = month,
      window = range,
      weeks = weeks(month, weekStart).map(weekOf(_, medicationDays, hydrationDays, moodDays, sleepDays)),
      daysWithRecords = recorded.size,
      medication = medication.map((days, doses) =>
        MonthDomain(medicationDays.fold(0)(_.size), MedicationStats.period(inMonth(days), doses, range))
      ),
      hydration = hydrationDays.map(days => MonthDomain(days.size, HydrationStats.period(days, range))),
      mood = moodDays.map(days => MonthDomain(days.size, MoodStats.period(days, range))),
      sleep = sleepDays.map(days => MonthDomain(days.size, SleepStats.period(days, range))),
      hydrationDays = hydrationDays.getOrElse(Map.empty),
      moodDays = moodDays.getOrElse(Map.empty),
      sleepDays = sleepDays.getOrElse(Map.empty),
      previousAdherence =
        previousMedication.fold(Stat.Insufficient(0, MinimumSample.PercentageDenominator.required, previousWindow)) {
          days =>
            val inPrevious = days.view.filterKeys(previousWindow.contains).values
            MedicationStats.adherence(MedicationStats.total(inPrevious).resolved, previousWindow)
        },
      food = mealDays.map(days => MonthDomain(days.size, MealStats.period(days, range))),
      mealDays = mealDays.getOrElse(Map.empty)
    )

  private def weekOf(
      days: DateRange,
      medication: Option[Map[LocalDate, MedicationDay]],
      hydration: Option[Map[LocalDate, HydrationDay]],
      mood: Option[Map[LocalDate, MoodDay]],
      sleep: Option[Map[LocalDate, SleepNight]]
  ): MonthWeekFigures =
    def recordedIn[A](source: Option[Map[LocalDate, A]]): List[A] =
      source.toList.flatMap(map => days.dates.flatMap(map.get))
    val water = recordedIn(hydration).map(_.totalMl)
    val moods = recordedIn(mood).map(_.mean)
    val nights = recordedIn(sleep).map(_.minutes)
    // A week row is judged by the weekly rule, whatever its length at the month's edges.
    val gate = MinimumSample.WeeklyMeanDays
    MonthWeekFigures(
      days = days,
      doses = MedicationStats.total(recordedIn(medication)),
      waterMeanMl = Stat.gated(water.size, gate, days, Basis.Recorded)(
        Math.round(water.sum.toDouble / water.size.toDouble).toInt
      ),
      moodMean = Stat.gated(moods.size, gate, days, Basis.Recorded)(moods.sum / moods.size.toDouble),
      sleepMeanMinutes = Stat.gated(nights.size, gate, days, Basis.SelfReported)(nights.sum.toDouble / nights.size)
    )

  private def maxDate(a: LocalDate, b: LocalDate): LocalDate = if a.isAfter(b) then a else b
  private def minDate(a: LocalDate, b: LocalDate): LocalDate = if a.isBefore(b) then a else b
end MonthStats
