package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.CompareFigures
import dosegram.core.domain.insights.CompareFrame
import dosegram.core.domain.insights.CompareRow
import dosegram.core.domain.insights.CompareSelection
import dosegram.core.domain.insights.ComparedPeriod
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.Difference
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MinimumSample
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.PeriodComparisons
import dosegram.core.domain.insights.PeriodName
import dosegram.core.domain.insights.RecordedDays
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearCell
import dosegram.core.domain.insights.YearFigure
import dosegram.core.domain.insights.YearSources

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** The words of `/stats compare`, its card and `GET /insights/compare` (supervisor decision, 2026-11-28): each period
  * with its days, then per shown area the figure of each period and the difference of the second against the first in
  * the words of VISUALS section 7.1 ("about the same", "higher", "longer", never "better"). A figure below the period's
  * gate shows its recorded days while the counts are kept, else a dash (R-GAM-5). The closing sentence is the fixed
  * non-causal one of every comparison (VA-IN-11).
  */
object CompareCopy:

  import StatsCopy.duration
  import StatsCopy.litres
  import StatsCopy.oneDecimal

  private val NoRecord = "—"

  private val PercentagePoints = UkForms("відсотковий пункт", "відсоткові пункти", "відсоткових пунктів")

  def header(zone: ZoneId): String =
    tr(s"Compare two periods · ${zone.getId}", s"Порівняння двох періодів · ${zone.getId}")

  /** The name of a period, as a button, a column head and the start of a line. */
  def label(name: PeriodName): String =
    name match
      case PeriodName.ThisWeek     => tr("This week", "Цей тиждень")
      case PeriodName.LastWeek     => tr("Last week", "Минулий тиждень")
      case PeriodName.ThisMonth    => tr("This month", "Цей місяць")
      case PeriodName.LastMonth    => tr("Last month", "Минулий місяць")
      case PeriodName.Month(month) => monthLabel(month)

  def monthLabel(month: YearMonth): String = s"${YearCopy.monthName(month)} ${month.getYear}"

  /** The days of a period: "16 Nov to 22 Nov", or one day. */
  def dates(window: DateRange): String =
    val start = window.start.format(Formats.dayMonth)
    val end = window.end.format(Formats.dayMonth)
    if window.start == window.end then start else tr(s"$start to $end", s"$start – $end")

  /** "Last week, 16 Nov to 22 Nov: days with records 7 of 7", with the outage days after it when there are any. */
  def periodLine(period: ComparedPeriod, days: RecordedDays): String =
    val count = tr(
      s"days with records ${days.withRecords} of ${period.window.days}",
      s"днів із записами ${days.withRecords} з ${period.window.days}"
    )
    val outage = Option.when(days.outageOnly > 0)(StatsCopy.outageDaysNotCounted(days.outageOnly))
    (s"${label(period.name)}, ${dates(period.window)}: $count" :: outage.toList).mkString(" · ")

  /** What a figure needs, once, while the counts are kept: a week 3 recorded days, a month 14. */
  def gateNote(gate: MinimumSample): String =
    if gate == MinimumSample.WeeklyMeanDays then
      tr(
        s"A week shows its figure once it has records on ${gate.required} days; fewer show those days.",
        s"Тиждень показує цифру, коли записи є щонайменше за ${UkNouns.Days.count(gate.required)}; " +
          "якщо менше, показано дні із записами."
      )
    else
      tr(
        s"A month shows its figure once it has records on ${gate.required} days; fewer show those days.",
        s"Місяць показує цифру, коли записи є щонайменше за ${UkNouns.Days.count(gate.required)}; " +
          "якщо менше, показано дні із записами."
      )

  def differenceNote: String =
    tr("Each difference is the second period against the first.", "Кожна різниця — другий період порівняно з першим.")

  def none: String = tr("Nothing was recorded in either period.", "За жоден із двох періодів нічого не записано.")

  /** A figure as the text prints it; mood with its level word (VISUALS section 7.1). */
  def figure(value: YearFigure): String =
    value match
      case YearFigure.MoodMean(mean) => StatsCopy.meanWithLevel(mean)
      case other                     => YearCopy.figure(other)

  /** A figure as the card draws it, short enough for half a column; the main meals say what they count. */
  def cardFigure(value: YearFigure): String =
    value match
      case YearFigure.MealsMean(perDay) => tr(s"${oneDecimal(perDay)} a day", s"${oneDecimal(perDay)} на день")
      case other                        => YearCopy.figure(other)

  /** The recorded days of an area in a period: "5 days", "5 nights". */
  def recordedDays(domain: TrendDomain, days: Int): String =
    domain match
      case TrendDomain.Sleep => tr(StatsCopy.count(days, "night"), UkNouns.Nights.count(days))
      case _                 => tr(StatsCopy.count(days, "day"), UkNouns.Days.count(days))

  /** One period's cell: the figure with its days while the counts are kept, the recorded days alone below the gate,
    * else a dash.
    */
  def cell(domain: TrendDomain, cell: YearCell, keepGates: Boolean): String =
    (cell.figure.shown, keepGates) match
      case (Some(value), true)                   => s"${figure(value)} (${recordedDays(domain, cell.recordedDays)})"
      case (Some(value), false)                  => figure(value)
      case (None, true) if cell.recordedDays > 0 =>
        tr(
          s"records on ${recordedDays(domain, cell.recordedDays)}",
          s"записи за ${recordedDays(domain, cell.recordedDays)}"
        )
      case (None, _) => NoRecord

  /** The difference of an area, in the unit of its figure. */
  def difference(domain: TrendDomain, difference: Difference): String =
    difference match
      case Difference.AboutTheSame   => tr("about the same", "приблизно так само")
      case Difference.Higher(amount) => change(domain, amount, higher = true)
      case Difference.Lower(amount)  => change(domain, amount, higher = false)

  private def change(domain: TrendDomain, amount: Double, higher: Boolean): String =
    domain match
      case TrendDomain.Medication =>
        val points = Math.round(amount)
        val word = if higher then tr("higher", "більше") else tr("lower", "менше")
        tr(
          s"${points} percentage ${if points == 1 then "point" else "points"} $word",
          s"на $points ${PercentagePoints(points)} $word"
        )
      case TrendDomain.Hydration =>
        val word = if higher then tr("higher", "більше") else tr("lower", "менше")
        tr(s"${litres(Math.round(amount).toInt, 1)} l $word", s"на ${litres(Math.round(amount).toInt, 1)} л $word")
      case TrendDomain.Mood =>
        val word = if higher then tr("higher", "більше") else tr("lower", "менше")
        tr(s"${oneDecimal(amount)} levels $word", s"на ${oneDecimal(amount)} рівня $word")
      case TrendDomain.Sleep =>
        val word = if higher then tr("longer", "довше") else tr("shorter", "коротше")
        tr(s"${duration(Math.round(amount).toInt)} $word", s"на ${duration(Math.round(amount).toInt)} $word")
      case TrendDomain.Nutrition =>
        tr(
          s"${oneDecimal(amount)} ${if higher then "more" else "fewer"} main meals a day",
          s"на ${oneDecimal(amount)} основного прийому їжі на день ${if higher then "більше" else "менше"}"
        )

  /** One area: its name, the cell of each period, and the difference when both show a figure. */
  def rowLine(frame: CompareFrame, row: CompareRow, keepGates: Boolean): String =
    val cells = List(
      s"${label(frame.earlier.name)} ${cell(row.domain, row.earlier, keepGates)}",
      s"${label(frame.later.name)} ${cell(row.domain, row.later, keepGates)}"
    )
    val change = row.difference.map(difference(row.domain, _))
    s"${YearCopy.areaName(row.domain)}: ${(cells ++ change.toList).mkString(" · ")}"

  /** Every line of `/stats compare`, the caption of its card and the API's `lines`. */
  def lines(figures: CompareFigures, zone: ZoneId, keepGates: Boolean): List[String] =
    val frame = figures.frame
    val head = List(
      header(zone),
      periodLine(frame.earlier, figures.earlierDays),
      periodLine(frame.later, figures.laterDays)
    )
    if figures.rows.isEmpty || !figures.hasRecords then head :+ none
    else
      head ++ (if keepGates then List(gateNote(frame.gate)) else Nil) ++ List(differenceNote) ++
        figures.rows.map(rowLine(frame, _, keepGates)) :+ StatsCopy.NotACause

  // ---------- buttons and the month picker ----------

  def ThisWeek: String = label(PeriodName.ThisWeek)
  def ThisMonth: String = label(PeriodName.ThisMonth)
  def PickMonths: String = tr("Pick two months", "Вибрати два місяці")

  /** The way to the comparison from the trends (BOT_UX section 12.5). */
  def ComparePeriods: String = tr("Compare periods", "Порівняти періоди")

  def pickFirst: String = tr("Pick the first month to compare.", "Виберіть перший місяць для порівняння.")

  def pickSecond(first: YearMonth): String =
    tr(
      s"First month: ${monthLabel(first)}. Pick the second month.",
      s"Перший місяць: ${monthLabel(first)}. Виберіть другий місяць."
    )

  def entries: List[CopyEntry] = Samples.entries

  private object Samples:
    private val today = LocalDate.parse("2026-11-26")
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

    private def days[A](from: LocalDate, count: Int)(value: Int => A): Map[LocalDate, A] =
      (0 until count).map(index => from.plusDays(index.toLong) -> value(index)).toMap

    private val sources = YearSources(
      medication = Some(
        days(LocalDate.parse("2026-10-01"), 57)(index =>
          MedicationDay(
            Map(
              medication -> DoseBuckets.ofResolved(
                AdherenceCounts(if index % 9 == 0 then 0 else 1, 0, 0, if index % 9 == 0 then 1 else 0)
              )
            )
          )
        )
      ),
      hydration = Some(days(LocalDate.parse("2026-10-01"), 57)(index => HydrationDay(1500 + 10 * index, 4, None))),
      mood = Some(days(LocalDate.parse("2026-11-10"), 17)(index => MoodDay(1, 3 + index % 2, 3, 4, 0, 0))),
      sleep = Some(days(LocalDate.parse("2026-10-20"), 3)(index => SleepNight(420 + index, None))),
      food = None
    )

    private def figures(selection: CompareSelection): CompareFigures =
      PeriodComparisons.of(CompareFrame.of(selection, today).get, sources)

    def entries: List[CopyEntry] =
      val weeks = figures(CompareSelection.Weeks)
      val months = figures(CompareSelection.Months)
      List(
        CopyEntry("stats.compare.weeks", lines(weeks, kyiv, keepGates = true).mkString("\n")),
        CopyEntry("stats.compare.months", lines(months, kyiv, keepGates = true).mkString("\n")),
        CopyEntry("stats.compare.months.omitting_gates", lines(months, kyiv, keepGates = false).mkString("\n")),
        CopyEntry("stats.compare.none", none),
        CopyEntry("stats.compare.gate.week", gateNote(MinimumSample.WeeklyMeanDays)),
        CopyEntry("stats.compare.gate.month", gateNote(MinimumSample.MonthlyReportDays)),
        CopyEntry("stats.compare.button.week", ThisWeek),
        CopyEntry("stats.compare.button.month", ThisMonth),
        CopyEntry("stats.compare.button.pick", PickMonths),
        CopyEntry("stats.compare.button.open", ComparePeriods),
        CopyEntry("stats.compare.pick.first", pickFirst),
        CopyEntry("stats.compare.pick.second", pickSecond(YearMonth.of(2026, 9))),
        CopyEntry("stats.compare.label.last_week", label(PeriodName.LastWeek)),
        CopyEntry("stats.compare.label.last_month", label(PeriodName.LastMonth)),
        CopyEntry("stats.compare.card.meals", cardFigure(YearFigure.MealsMean(2.3)))
      ) ++ List(
        "medication" -> (TrendDomain.Medication, Difference.Higher(4)),
        "water" -> (TrendDomain.Hydration, Difference.Lower(200)),
        "mood" -> (TrendDomain.Mood, Difference.Higher(0.4)),
        "sleep" -> (TrendDomain.Sleep, Difference.Lower(25)),
        "food" -> (TrendDomain.Nutrition, Difference.Higher(0.5)),
        "same" -> (TrendDomain.Hydration, Difference.AboutTheSame)
      ).map { case (id, (domain, change)) => CopyEntry(s"stats.compare.difference.$id", difference(domain, change)) }
  end Samples
end CompareCopy
