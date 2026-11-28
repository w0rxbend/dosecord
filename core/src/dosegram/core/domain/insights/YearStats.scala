package dosegram.core.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** The figure of one area over a month or a year of `/stats year` (supervisor decision, 2026-11-28, analytics round 6).
  * Each is the user's own record, never judged against a norm.
  */
enum YearFigure:
  /** Medication: the doses recorded as taken of the answered ones (VISUALS section 7.2). */
  case Taken(ratio: Ratio)

  /** Water: the mean of the recorded days. */
  case WaterMean(millilitres: Int)

  /** Mood: the mean of the days with a check-in. */
  case MoodMean(mean: Double)

  /** Sleep: the mean of the recorded nights. */
  case SleepMean(minutes: Int)

  /** Food: the main meals logged a day, over the days with a meal. A count of logging, never an amount. */
  case MealsMean(perDay: Double)

/** One area over one month or one year: the days with a record and the figure behind its gate. */
final case class YearCell(recordedDays: Int, figure: Stat[YearFigure])

/** One area of the year: each month from January to the year's last day, the year, and the full year before. */
final case class YearRow(domain: TrendDomain, months: List[(YearMonth, YearCell)], year: YearCell, previous: YearCell)

/** The year of `/stats year` and of the one-year card: a row per area whose consent is active, in the order medication,
  * water, mood, sleep, food; `recorded` holds each shown area's recorded days of the year, the cells of the card.
  * `outageOnlyDays` are the days of the year with no record but doses with no record (a service outage), which are not
  * the user's records and are left out of `daysWithRecords` (verification of 2026-11-28).
  */
final case class YearFigures(
    year: Int,
    window: DateRange,
    daysWithRecords: Int,
    rows: List[YearRow],
    recorded: Map[TrendDomain, Set[LocalDate]],
    outageOnlyDays: Int
):
  def hasRecords: Boolean = daysWithRecords > 0
  def previousHasRecords: Boolean = rows.exists(_.previous.recordedDays > 0)

/** The days of a window with a record in any shown area, and those whose only doses have no record (an outage). */
final case class RecordedDays(withRecords: Int, outageOnly: Int)

/** What the year reads, per area: `None` without its consent. Each map holds the days of the year and of the year
  * before it; a day outside both is ignored.
  */
final case class YearSources(
    medication: Option[Map[LocalDate, MedicationDay]],
    hydration: Option[Map[LocalDate, HydrationDay]],
    mood: Option[Map[LocalDate, MoodDay]],
    sleep: Option[Map[LocalDate, SleepNight]],
    food: Option[Map[LocalDate, MealDay]]
)

/** The year view (supervisor decision, 2026-11-28): the month gate of PRODUCT_SPEC R-INS-11 applied to every month, so
  * a month shows its figure only from 14 recorded days; the year is the mean over all its recorded days behind the same
  * gate; the year before is a baseline only when it has a figure of its own (VISUALS VA-PR-07). A day without a record
  * enters no figure (VA-PR-03).
  */
object YearStats:

  private val Gate = MinimumSample.MonthlyReportDays

  /** The local days of `year`: to `today` in the current year, the whole year before it. */
  def window(year: Int, today: LocalDate): DateRange =
    require(year <= today.getYear, s"the year $year is after today, $today")
    val start = LocalDate.of(year, 1, 1)
    val end = LocalDate.of(year, 12, 31)
    DateRange.between(start, if end.isAfter(today) then today else end)

  def of(year: Int, today: LocalDate, sources: YearSources): YearFigures =
    val range = window(year, today)
    val before = DateRange.between(LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31))
    val areas = areasOf(sources)
    val rows = areas.map(_.row(range, before))
    val recordedDays = recordedIn(areas, range)
    val records = daysWithRecords(sources, range)
    YearFigures(year, range, records.withRecords, rows, recordedDays, records.outageOnly)

  /** One cell per area whose source is given, over `range`, each figure behind `gate`: the columns of the comparison of
    * two periods (supervisor decision, 2026-11-28), in the order of the year's rows.
    */
  def cells(sources: YearSources, range: DateRange, gate: MinimumSample): List[(TrendDomain, YearCell)] =
    areasOf(sources).map(area => area.domain -> area.cell(range, gate))

  /** The days of `range` with a record in any area whose source is given, and the days whose only doses have no record
    * (a service outage), which are not the user's records (verification of 2026-11-28).
    */
  def daysWithRecords(sources: YearSources, range: DateRange): RecordedDays =
    val withRecords = recordedIn(areasOf(sources), range).values.flatten.toSet
    val outageOnly = sources.medication.fold(Set.empty[LocalDate])(days =>
      MedicationDay.outageOnly(days.filter((date, _) => range.contains(date)), withRecords)
    )
    RecordedDays(withRecords.size, outageOnly.size)

  private def areasOf(sources: YearSources): List[Area[?]] =
    List(
      sources.medication.map(days => Area(TrendDomain.Medication, MedicationDay.recorded(days), medication)),
      sources.hydration.map(days => Area(TrendDomain.Hydration, recorded(days)(_.entries > 0), water)),
      sources.mood.map(days => Area(TrendDomain.Mood, recorded(days)(_.n > 0), mood)),
      sources.sleep.map(days => Area(TrendDomain.Sleep, days, sleep)),
      sources.food.map(days => Area(TrendDomain.Nutrition, recorded(days)(_.meals > 0), food))
    ).flatten

  private def recordedIn(areas: List[Area[?]], range: DateRange): Map[TrendDomain, Set[LocalDate]] =
    areas.map(area => area.domain -> area.days.keySet.filter(range.contains)).toMap

  /** The weeks of a window from the week of its first day, each starting on `weekStart` and cut at the window's edges:
    * the columns of the one-year card (VISUALS IMG-30) and of the Mini App's year (MINI_APP section 7.11).
    */
  def weeks(range: DateRange, weekStart: DayOfWeek): List[DateRange] =
    Iterator
      .iterate(range.start.`with`(TemporalAdjusters.previousOrSame(weekStart)))(_.plusWeeks(1))
      .takeWhile(!_.isAfter(range.end))
      .map(start =>
        DateRange
          .between(Ordering[LocalDate].max(start, range.start), Ordering[LocalDate].min(start.plusDays(6), range.end))
      )
      .toList

  /** The months of a window from January: the last one is cut at the window's end. */
  private def months(range: DateRange): List[DateRange] =
    Iterator
      .iterate(YearMonth.from(range.start))(_.plusMonths(1))
      .takeWhile(month => !month.atDay(1).isAfter(range.end))
      .map(month => DateRange.between(month.atDay(1), Ordering[LocalDate].min(month.atEndOfMonth, range.end)))
      .toList

  private def recorded[A](days: Map[LocalDate, A])(isRecord: A => Boolean): Map[LocalDate, A] =
    days.filter((_, day) => isRecord(day))

  /** One area: its recorded days and how a figure is made from those of one window. */
  private final case class Area[A](
      domain: TrendDomain,
      days: Map[LocalDate, A],
      figure: (List[A], DateRange, MinimumSample) => Stat[YearFigure]
  ):
    def cell(range: DateRange, gate: MinimumSample = Gate): YearCell =
      val inside = days.view.filterKeys(range.contains).values.toList
      YearCell(inside.size, figure(inside, range, gate))

    def row(range: DateRange, before: DateRange): YearRow =
      YearRow(domain, months(range).map(month => YearMonth.from(month.start) -> cell(month)), cell(range), cell(before))

  private def mean(values: List[Double]): Double = values.sum / values.size.toDouble

  private def medication(days: List[MedicationDay], range: DateRange, gate: MinimumSample): Stat[YearFigure] =
    if days.size < gate.required then Stat.Insufficient(days.size, gate.required, range)
    else
      val counts = MedicationStats.total(days).resolved
      Stat.gated(counts.resolved, MinimumSample.PercentageDenominator, range, Basis.Recorded)(
        YearFigure.Taken(Ratio(counts.taken, counts.resolved))
      )

  private def water(days: List[HydrationDay], range: DateRange, gate: MinimumSample): Stat[YearFigure] =
    Stat.gated(days.size, gate, range, Basis.Recorded)(
      YearFigure.WaterMean(Math.round(mean(days.map(_.totalMl.toDouble))).toInt)
    )

  private def mood(days: List[MoodDay], range: DateRange, gate: MinimumSample): Stat[YearFigure] =
    Stat.gated(days.size, gate, range, Basis.SelfReported)(YearFigure.MoodMean(mean(days.map(_.mean))))

  private def sleep(nights: List[SleepNight], range: DateRange, gate: MinimumSample): Stat[YearFigure] =
    Stat.gated(nights.size, gate, range, Basis.SelfReported)(
      YearFigure.SleepMean(Math.round(mean(nights.map(_.minutes.toDouble))).toInt)
    )

  private def food(days: List[MealDay], range: DateRange, gate: MinimumSample): Stat[YearFigure] =
    Stat.gated(days.size, gate, range, Basis.Recorded)(YearFigure.MealsMean(mean(days.map(_.mainMeals.toDouble))))
end YearStats
