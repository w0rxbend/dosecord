package dosegram.core.domain.insights

import java.time.LocalDate
import java.time.YearMonth

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
  */
final case class YearFigures(
    year: Int,
    window: DateRange,
    daysWithRecords: Int,
    rows: List[YearRow],
    recorded: Map[TrendDomain, Set[LocalDate]]
):
  def hasRecords: Boolean = daysWithRecords > 0
  def previousHasRecords: Boolean = rows.exists(_.previous.recordedDays > 0)

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
    val areas = List(
      sources.medication.map(days => Area(TrendDomain.Medication, recorded(days)(!_.total.isEmpty), medication)),
      sources.hydration.map(days => Area(TrendDomain.Hydration, recorded(days)(_.entries > 0), water)),
      sources.mood.map(days => Area(TrendDomain.Mood, recorded(days)(_.n > 0), mood)),
      sources.sleep.map(days => Area(TrendDomain.Sleep, days, sleep)),
      sources.food.map(days => Area(TrendDomain.Nutrition, recorded(days)(_.meals > 0), food))
    ).flatten
    val rows = areas.map(_.row(range, before))
    val recordedDays = areas.map(area => area.domain -> area.days.keySet.filter(range.contains)).toMap
    YearFigures(year, range, recordedDays.values.flatten.toSet.size, rows, recordedDays)

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
      figure: (List[A], DateRange) => Stat[YearFigure]
  ):
    def cell(range: DateRange): YearCell =
      val inside = days.view.filterKeys(range.contains).values.toList
      YearCell(inside.size, figure(inside, range))

    def row(range: DateRange, before: DateRange): YearRow =
      YearRow(domain, months(range).map(month => YearMonth.from(month.start) -> cell(month)), cell(range), cell(before))

  private def gated(days: Int, range: DateRange, basis: Basis)(figure: => YearFigure): Stat[YearFigure] =
    Stat.gated(days, Gate, range, basis)(figure)

  private def mean(values: List[Double]): Double = values.sum / values.size.toDouble

  private def medication(days: List[MedicationDay], range: DateRange): Stat[YearFigure] =
    if days.size < Gate.required then Stat.Insufficient(days.size, Gate.required, range)
    else
      val counts = MedicationStats.total(days).resolved
      Stat.gated(counts.resolved, MinimumSample.PercentageDenominator, range, Basis.Recorded)(
        YearFigure.Taken(Ratio(counts.taken, counts.resolved))
      )

  private def water(days: List[HydrationDay], range: DateRange): Stat[YearFigure] =
    gated(days.size, range, Basis.Recorded)(YearFigure.WaterMean(Math.round(mean(days.map(_.totalMl.toDouble))).toInt))

  private def mood(days: List[MoodDay], range: DateRange): Stat[YearFigure] =
    gated(days.size, range, Basis.SelfReported)(YearFigure.MoodMean(mean(days.map(_.mean))))

  private def sleep(nights: List[SleepNight], range: DateRange): Stat[YearFigure] =
    gated(nights.size, range, Basis.SelfReported)(
      YearFigure.SleepMean(Math.round(mean(nights.map(_.minutes.toDouble))).toInt)
    )

  private def food(days: List[MealDay], range: DateRange): Stat[YearFigure] =
    gated(days.size, range, Basis.Recorded)(YearFigure.MealsMean(mean(days.map(_.mainMeals.toDouble))))
end YearStats
