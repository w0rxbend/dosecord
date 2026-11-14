package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationPeriod
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealPeriod
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.insights.MedicationFigures
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodPeriod
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepPeriod
import dosegram.core.domain.insights.SleepRegularity
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.insights.Stat

import java.time.LocalDate

/** The words of the Mini App dashboard (MINI_APP section 7.11): the comparison with the user's own previous period of
  * the same length, the food figures of a period and the bedtime regularity figures. A comparison is written with both
  * figures and uses no arrow, no colour word and no percentage change (MA-INS-3); a previous period without a record is
  * "No data" (MA-INS-4). Nothing here rates a value or names a cause.
  */
object TrendCopy:

  private def previous(days: Int, figure: String): String = s"Previous $days days: $figure"

  /** The pooled doses of the previous period; the percentage only from five resolved doses (VISUALS section 7.1). */
  def doses(days: Int, figures: MedicationFigures): String =
    val resolved = figures.buckets.resolved
    val figure = figures.adherence match
      case Stat.Shown(ratio, _, _, _) => s"${ratio.numerator} of ${ratio.denominator} taken (${ratio.percent}%)"
      case _: Stat.Insufficient[?] | _: Stat.NoVariation[?] =>
        if resolved.resolved == 0 then StatsCopy.NoData else s"${resolved.taken} of ${resolved.resolved} taken"
    previous(days, figure)

  def water(days: Int, period: HydrationPeriod): String =
    val figure =
      if period.recordedDays == 0 then StatsCopy.NoData
      else
        period.meanMl.shown match
          case Some(millilitres) =>
            val goal = period.goalDays
              .flatMap(_.shown)
              .map: ratio =>
                s"goal reached on ${ratio.numerator} of ${ratio.denominator} recorded days"
            (s"average ${StatsCopy.litres(millilitres, 1)} l a day" :: goal.toList).mkString(" · ")
          case None => s"water logged on ${StatsCopy.count(period.recordedDays, "day")}"
    previous(days, figure)

  def mood(days: Int, period: MoodPeriod): String =
    val figure =
      if period.checkInDays == 0 then StatsCopy.NoData
      else
        val onDays = s"check-ins on ${StatsCopy.count(period.checkInDays, "day")}"
        period.average.shown.fold(onDays)(mean => s"average ${StatsCopy.meanWithLevel(mean)} · $onDays")
    previous(days, figure)

  def sleep(days: Int, period: SleepPeriod): String =
    val figure =
      if period.nights == 0 then StatsCopy.NoData
      else
        val logged = s"${StatsCopy.count(period.nights, "night")} logged"
        period.averageMinutes.shown.fold(logged)(minutes =>
          s"average ${StatsCopy.duration(Math.round(minutes).toInt)} · $logged"
        )
    previous(days, figure)

  def food(days: Int, period: MealPeriod): String =
    val figure =
      if period.meals == 0 then StatsCopy.NoData
      else s"${StatsCopy.count(period.meals, "meal")} logged on ${StatsCopy.count(period.daysWithMeals, "day")}"
    previous(days, figure)

  /** The meals of a period and the days per main meal; empty when the period has no meal. */
  def foodFigures(period: MealPeriod): List[String] =
    if period.meals == 0 then Nil
    else
      List(
        s"${StatsCopy.count(period.meals, "meal")} logged on ${period.daysWithMeals} of ${period.window.days} days",
        FoodCopy.week(period)
      )

  /** How much bedtime and wake time varied, and the usual times, once enough nights are logged (VISUALS section 7.7);
    * before that, the nights still needed. A period without a night has no line.
    */
  def regularity(figures: SleepRegularityFigures): List[String] =
    val spread = for
      bedtime <- figures.bedtimeSpreadMinutes
      wake <- figures.wakeSpreadMinutes
    yield s"Bedtime varied by about $bedtime min, wake time by about $wake min"
    val usual = for
      bedtime <- figures.typicalBedtime
      wake <- figures.typicalWake
    yield s"Usual bedtime ${clock(bedtime)} · usual wake time ${clock(wake)}"
    if figures.nights == 0 then Nil
    else if spread.isEmpty then
      val missing = SleepRegularity.consistencyGate(figures.window) - figures.nights
      List(s"${StatsCopy.more(missing, "night")} for bedtime regularity")
    else spread.toList ++ usual.toList

  /** A mood mean with its level word, as every mood figure is printed: "3.4 (slightly pleasant)". */
  def moodMean(mean: Double): String = StatsCopy.meanWithLevel(mean)

  private def clock(minute: Int): String = f"${minute / 60}%02d:${minute % 60}%02d"

  val entries: List[CopyEntry] = Samples.entries

  private object Samples:
    private val end = LocalDate.parse("2026-11-07")
    private val month = DateRange.endingOn(end, 30)

    private def days[A](count: Int)(value: Int => A): Map[LocalDate, A] =
      List.tabulate(count)(back => end.minusDays(back.toLong) -> value(back)).toMap

    private val doseFigures = MedicationStats.figures(DoseBuckets.ofResolved(AdherenceCounts(80, 10, 15, 5)), month)
    private val waterSample =
      HydrationStats.period(days(12)(back => HydrationDay(1500 + 10 * back, 3, Some(1550))), month)
    private val moodSample = MoodStats.period(days(10)(_ => MoodDay(1, 4, 4, 4, 0, 0)), month)
    private val sleepSample = SleepStats.period(days(10)(_ => SleepNight(410, None)), month)
    private val meals = MealStats.period(days(4)(_ => MealDay(1, 1, 0, 1)), month)
    private val regular =
      SleepRegularityFigures(month, Nil, 24, Some(415), Some(35), Some(20), Some(23 * 60 + 10), Some(425), None)

    val entries: List[CopyEntry] = List(
      CopyEntry("trend.previous.doses", doses(30, doseFigures)),
      CopyEntry("trend.previous.water", water(30, waterSample)),
      CopyEntry("trend.previous.mood", mood(30, moodSample)),
      CopyEntry("trend.previous.sleep", sleep(30, sleepSample)),
      CopyEntry("trend.previous.food", food(30, meals)),
      CopyEntry("trend.previous.no_data", food(7, MealStats.period(Map.empty, month))),
      CopyEntry("trend.food", foodFigures(meals).mkString("\n")),
      CopyEntry("trend.sleep.regularity", regularity(regular).mkString("\n")),
      CopyEntry(
        "trend.sleep.regularity.gate",
        regularity(regular.copy(nights = 8, bedtimeSpreadMinutes = None, typicalBedtime = None)).mkString("\n")
      )
    )
  end Samples
end TrendCopy
