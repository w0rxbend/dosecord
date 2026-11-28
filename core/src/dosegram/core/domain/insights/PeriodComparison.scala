package dosegram.core.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** What `/stats compare`, its card and `GET /insights/compare` put side by side (supervisor decision, 2026-11-28): this
  * week so far and the whole week before it, this month so far and the whole month before it, or two calendar months
  * the user picked. `code` is the wire form of the chat's buttons and of the consent card that resumes the image: it
  * must not change for a case once a card with it has been sent.
  */
enum CompareSelection:
  case Weeks
  case Months

  /** Two different calendar months, `earlier` before `later`; build it with [[CompareSelection.twoMonths]]. */
  case TwoMonths private[insights] (earlier: YearMonth, later: YearMonth)

  def code: Int =
    this match
      case Weeks                     => CompareSelection.WeeksCode
      case Months                    => CompareSelection.MonthsCode
      case TwoMonths(earlier, later) =>
        CompareSelection.PairBase + CompareSelection.offset(earlier) * CompareSelection.MonthSpan +
          CompareSelection.offset(later)

object CompareSelection:
  private val WeeksCode = 1
  private val MonthsCode = 2

  /** A pair of months is coded from here, clear of the two fixed choices, and fits the 16 bits of a consent card. */
  private val PairBase = 1024

  /** The first month a pair can name, and how many months from it: January 2025 to October 2045. */
  val FirstMonth: YearMonth = YearMonth.of(2025, 1)
  private val MonthSpan = 250

  private def offset(month: YearMonth): Int = ChronoUnit.MONTHS.between(FirstMonth, month).toInt

  private def inSpan(month: YearMonth): Boolean = offset(month) >= 0 && offset(month) < MonthSpan

  /** Two months in either order; `None` when they are the same month or outside the months a pair can name. */
  /** How many months a picker offers, the current one included. */
  val PickerMonths = 12

  /** The months a picker offers for a pair, newest first: the current one and the eleven before it, none before the
    * first month a pair can name. The chat's buttons and the Mini App's choices are this list.
    */
  def pickable(current: YearMonth): List[YearMonth] =
    List.tabulate(PickerMonths)(back => current.minusMonths(back.toLong)).filterNot(_.isBefore(FirstMonth))

  def twoMonths(first: YearMonth, second: YearMonth): Option[CompareSelection] =
    Option.when(first != second && inSpan(first) && inSpan(second)):
      if first.isBefore(second) then TwoMonths(first, second) else TwoMonths(second, first)

  def of(code: Long): Option[CompareSelection] =
    code match
      case WeeksCode                                                                         => Some(Weeks)
      case MonthsCode                                                                        => Some(Months)
      case pair if pair >= PairBase && pair < PairBase.toLong + MonthSpan.toLong * MonthSpan =>
        val value = (pair - PairBase).toInt
        twoMonths(FirstMonth.plusMonths((value / MonthSpan).toLong), FirstMonth.plusMonths((value % MonthSpan).toLong))
          .filter(_.code == code)
      case _ => None

/** How a compared period is named on every surface. */
enum PeriodName:
  case ThisWeek, LastWeek, ThisMonth, LastMonth
  case Month(month: YearMonth)

/** One side of a comparison: its name and its local days. */
final case class ComparedPeriod(name: PeriodName, window: DateRange)

/** The two windows of a comparison, the earlier first, and the gate each figure is held to: a week shows a figure from
  * 3 recorded days, a month from 14 (VISUALS section 7.1, PRODUCT_SPEC R-INS-11).
  */
final case class CompareFrame(selection: CompareSelection, earlier: ComparedPeriod, later: ComparedPeriod):
  def gate: MinimumSample =
    selection match
      case CompareSelection.Weeks => MinimumSample.WeeklyMeanDays
      case _                      => MinimumSample.MonthlyReportDays

object CompareFrame:

  /** The account's week starts on Monday until it has a week-start setting, as everywhere else. */
  val WeekStart: DayOfWeek = DayOfWeek.MONDAY

  /** The frame of `selection` on the account's current local day; `None` for a month after it. A current week or month
    * ends today; every other period is whole.
    */
  def of(selection: CompareSelection, today: LocalDate): Option[CompareFrame] =
    selection match
      case CompareSelection.Weeks =>
        val start = today.`with`(TemporalAdjusters.previousOrSame(WeekStart))
        Some(
          CompareFrame(
            selection,
            ComparedPeriod(PeriodName.LastWeek, DateRange.between(start.minusWeeks(1), start.minusDays(1))),
            ComparedPeriod(PeriodName.ThisWeek, DateRange.between(start, today))
          )
        )
      case CompareSelection.Months =>
        val current = YearMonth.from(today)
        Some(
          CompareFrame(
            selection,
            ComparedPeriod(PeriodName.LastMonth, monthWindow(current.minusMonths(1), today)),
            ComparedPeriod(PeriodName.ThisMonth, monthWindow(current, today))
          )
        )
      case CompareSelection.TwoMonths(earlier, later) =>
        Option.unless(later.isAfter(YearMonth.from(today))):
          CompareFrame(
            selection,
            ComparedPeriod(PeriodName.Month(earlier), monthWindow(earlier, today)),
            ComparedPeriod(PeriodName.Month(later), monthWindow(later, today))
          )

  /** A month's days, cut at `today` in the current month. */
  private def monthWindow(month: YearMonth, today: LocalDate): DateRange =
    val end = month.atEndOfMonth
    DateRange.between(month.atDay(1), if end.isAfter(today) then today else end)

/** The difference of the later period against the earlier one in the words of VISUALS section 7.1 ("Baseline wording"):
  * about the same under the limit of the figure, otherwise higher or lower by `amount`, in the figure's displayed unit.
  * It never says better or worse.
  */
enum Difference:
  case AboutTheSame
  case Higher(amount: Double)
  case Lower(amount: Double)

/** One area of a comparison: its cell in each period. */
final case class CompareRow(domain: TrendDomain, earlier: YearCell, later: YearCell):

  /** The difference, only when both periods show a figure. */
  def difference: Option[Difference] =
    for
      before <- earlier.figure.shown
      after <- later.figure.shown
    yield PeriodComparisons.difference(before, after)

/** A comparison of two periods: the frame, one row per area whose consent is active in the order medication, water,
  * mood, sleep, food, and each period's days with records under the one definition (verification of 2026-11-28).
  */
final case class CompareFigures(
    frame: CompareFrame,
    rows: List[CompareRow],
    earlierDays: RecordedDays,
    laterDays: RecordedDays
):
  def hasRecords: Boolean = earlierDays.withRecords > 0 || laterDays.withRecords > 0

/** The comparison of two periods, pure over the records of both windows. Each figure is the year view's figure of the
  * same area over the period's recorded days, behind the period's gate; a day without a record enters no figure
  * (VA-PR-03), and nothing is compared with anyone but the user.
  */
object PeriodComparisons:

  /** The limits of "about the same" (VISUALS section 7.1): 2 percentage points, 100 ml, 0.2 mood levels, 10 minutes;
    * and, for the main meals logged a day, 0.2 meals (supervisor decision, 2026-11-28).
    */
  private val PercentagePoints = BigDecimal(2)
  private val Millilitres = BigDecimal(100)
  private val MoodLevels = BigDecimal("0.2")
  private val Minutes = BigDecimal(10)
  private val Meals = BigDecimal("0.2")

  def of(frame: CompareFrame, sources: YearSources): CompareFigures =
    val earlier = YearStats.cells(sources, frame.earlier.window, frame.gate)
    val later = YearStats.cells(sources, frame.later.window, frame.gate).toMap
    CompareFigures(
      frame,
      earlier.map((domain, cell) => CompareRow(domain, cell, later(domain))),
      YearStats.daysWithRecords(sources, frame.earlier.window),
      YearStats.daysWithRecords(sources, frame.later.window)
    )

  /** The difference of two figures of one area, read from the values every surface prints (whole percentages, litres to
    * 0.1, one decimal), so the words never disagree with the two figures beside them.
    */
  def difference(earlier: YearFigure, later: YearFigure): Difference =
    (earlier, later) match
      case (YearFigure.Taken(before), YearFigure.Taken(after)) =>
        worded(BigDecimal(after.percent - before.percent), PercentagePoints)
      case (YearFigure.WaterMean(before), YearFigure.WaterMean(after)) =>
        worded(tenth(BigDecimal(after) / 1000) * 1000 - tenth(BigDecimal(before) / 1000) * 1000, Millilitres)
      case (YearFigure.MoodMean(before), YearFigure.MoodMean(after)) =>
        worded(tenth(BigDecimal(after)) - tenth(BigDecimal(before)), MoodLevels)
      case (YearFigure.SleepMean(before), YearFigure.SleepMean(after)) => worded(BigDecimal(after - before), Minutes)
      case (YearFigure.MealsMean(before), YearFigure.MealsMean(after)) =>
        worded(tenth(BigDecimal(after)) - tenth(BigDecimal(before)), Meals)
      case _ => throw IllegalArgumentException("two figures of one area are compared")

  /** The value a bar or a dot is drawn at, on the area's own scale: the printed percentage, millilitres, the mood mean,
    * minutes, main meals a day.
    */
  def valueOf(figure: YearFigure): Double =
    figure match
      case YearFigure.Taken(ratio)       => ratio.percent.toDouble
      case YearFigure.WaterMean(ml)      => ml.toDouble
      case YearFigure.MoodMean(mean)     => mean
      case YearFigure.SleepMean(minutes) => minutes.toDouble
      case YearFigure.MealsMean(perDay)  => perDay

  private def tenth(value: BigDecimal): BigDecimal = value.setScale(1, BigDecimal.RoundingMode.HALF_UP)

  private def worded(change: BigDecimal, limit: BigDecimal): Difference =
    if change.abs < limit then Difference.AboutTheSame
    else if change > 0 then Difference.Higher(change.abs.toDouble)
    else Difference.Lower(change.abs.toDouble)
end PeriodComparisons
