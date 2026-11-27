package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MinimumSample
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearCell
import dosegram.core.domain.insights.YearFigure
import dosegram.core.domain.insights.YearFigures
import dosegram.core.domain.insights.YearRow
import dosegram.core.domain.insights.YearSources
import dosegram.core.domain.insights.YearStats

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** The text of `/stats year` (supervisor decision, 2026-11-28, analytics round 6): the year and the days with records,
  * then per shown area one line of months and one line of the year and the year before. A month below the 14-day gate
  * of PRODUCT_SPEC R-INS-11 shows its recorded days in brackets, never a figure; a month without a record shows a dash.
  * The leading months before an area's first record are left out.
  */
object YearCopy:

  import StatsCopy.duration
  import StatsCopy.litres
  import StatsCopy.oneDecimal

  private val NoRecord = "—"

  def header(year: Int, zone: ZoneId): String = s"$year · ${zone.getId}"

  def daysWithRecords(days: Int, of: Int): String =
    tr(s"Days with records: $days of $of", s"Днів із записами: $days з $of")

  def gateNote: String = tr(
    s"A month shows its figure once it has records on ${MinimumSample.MonthlyReportDays.required} days; " +
      "fewer show those days in brackets.",
    s"Місяць показує цифру, коли записи є щонайменше за ${UkNouns.Days.count(MinimumSample.MonthlyReportDays.required)}; " +
      "якщо менше, у дужках — кількість днів із записами."
  )

  def none(year: Int): String = tr(s"Nothing was recorded in $year.", s"У $year році нічого не записано.")

  def PreviousYear(year: Int): String = tr(s"◀ $year", s"◀ $year")
  def NextYear(year: Int): String = tr(s"$year ▶", s"$year ▶")

  /** What an area's figures are, before its months. */
  def areaName(domain: TrendDomain): String =
    domain match
      case TrendDomain.Medication => tr("Doses taken of those answered", "Прийняті дози з тих, на які є відповідь")
      case TrendDomain.Hydration  => tr("Water, a day with records", "Вода, за день із записами")
      case TrendDomain.Mood  => tr("Mood, average of the days with a check-in", "Настрій, середнє днів із позначкою")
      case TrendDomain.Sleep => tr("Sleep, a night with records", "Сон, за ніч із записами")
      case TrendDomain.Nutrition =>
        tr("Main meals logged, a day with a meal", "Записані основні прийоми їжі, за день із їжею")

  def figure(value: YearFigure): String =
    value match
      case YearFigure.Taken(ratio)      => s"${ratio.percent}%"
      case YearFigure.WaterMean(ml)     => tr(s"${litres(ml, 1)} l", s"${litres(ml, 1)} л")
      case YearFigure.MoodMean(mean)    => oneDecimal(mean)
      case YearFigure.SleepMean(minute) => duration(minute)
      case YearFigure.MealsMean(perDay) => oneDecimal(perDay)

  def monthName(month: YearMonth): String =
    tr(
      month.getMonth.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.ENGLISH),
      month.getMonth.getDisplayName(TextStyle.SHORT_STANDALONE, Formats.locale)
    )

  private def daysOf(domain: TrendDomain, days: Int): String =
    domain match
      case TrendDomain.Sleep => tr(StatsCopy.count(days, "night"), UkNouns.Nights.count(days))
      case _                 => tr(StatsCopy.count(days, "day"), UkNouns.Days.count(days))

  private def month(cell: YearCell, keepGates: Boolean): String =
    cell.figure.shown match
      case Some(value)                                => figure(value)
      case None if cell.recordedDays > 0 && keepGates => s"(${cell.recordedDays})"
      case None                                       => NoRecord

  /** "2026: 1.8 l (180 days)", with the year before after it only when that year has a figure (VISUALS VA-PR-07). */
  private def years(year: Int, row: YearRow): Option[String] =
    def one(name: Int, cell: YearCell): Option[String] =
      cell.figure.shown.map(value => s"$name: ${figure(value)} (${daysOf(row.domain, cell.recordedDays)})")
    val both = one(year, row.year).toList ++ one(year - 1, row.previous).toList
    Option.when(both.nonEmpty)(both.mkString(" · "))

  private def block(figures: YearFigures, row: YearRow, keepGates: Boolean): List[String] =
    val name = areaName(row.domain)
    val months = row.months.dropWhile(_._2.recordedDays == 0)
    val line = Option.when(months.nonEmpty)(
      s"$name: ${months.map((month, cell) => s"${monthName(month)} ${this.month(cell, keepGates)}").mkString(" · ")}"
    )
    (line, years(figures.year, row)) match
      case (Some(first), total) => first :: total.toList
      case (None, Some(total))  => List(s"$name: $total")
      case (None, None)         => Nil

  /** Every line of `/stats year`. */
  def lines(figures: YearFigures, zone: ZoneId): List[String] = render(figures, zone, keepGates = true)

  /** The year with every minimum-data gate left out (R-GAM-5): a month below its gate shows a dash. */
  def linesOmittingGates(figures: YearFigures, zone: ZoneId): List[String] = render(figures, zone, keepGates = false)

  private def render(figures: YearFigures, zone: ZoneId, keepGates: Boolean): List[String] =
    val blocks = figures.rows.flatMap(block(figures, _, keepGates))
    val head = List(header(figures.year, zone), daysWithRecords(figures.daysWithRecords, figures.window.days))
    if blocks.isEmpty then head :+ none(figures.year)
    else head ++ (if keepGates then List(gateNote) else Nil) ++ blocks

  def entries: List[CopyEntry] = Samples.entries

  private object Samples:
    private val today = LocalDate.parse("2026-11-08")
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

    private def days[A](from: LocalDate, count: Int, every: Int = 1)(value: Int => A): Map[LocalDate, A] =
      (0 until count).map(index => from.plusDays(index.toLong * every) -> value(index)).toMap

    private def figures: YearFigures =
      val march = LocalDate.parse("2026-03-01")
      YearStats.of(
        2026,
        today,
        YearSources(
          medication = Some(
            days(march, 240)(index =>
              MedicationDay(
                Map(
                  medication -> DoseBuckets.ofResolved(
                    AdherenceCounts(if index % 10 == 0 then 0 else 1, 0, 0, if index % 10 == 0 then 1 else 0)
                  )
                )
              )
            )
          ),
          hydration = Some(
            days(LocalDate.parse("2025-06-01"), 60, every = 3)(index => HydrationDay(1500 + 10 * index, 3, None)) ++
              days(march, 120, every = 2)(index => HydrationDay(1700 + 5 * index, 4, Some(2000)))
          ),
          mood = Some(days(LocalDate.parse("2026-10-20"), 12)(index => MoodDay(1, 3 + index % 2, 3, 4, 0, 0))),
          sleep = Some(days(march, 200)(index => SleepNight(410 + index % 40, None))),
          food = None
        )
      )

    def entries: List[CopyEntry] =
      List(
        CopyEntry("stats.year", lines(figures, kyiv).mkString("\n")),
        CopyEntry("stats.year.none", none(2026)),
        CopyEntry("stats.year.gate", gateNote)
      ) ++ TrendDomain.values.toList.map(domain =>
        CopyEntry(s"stats.year.area.${domain.toString.toLowerCase(Locale.ROOT)}", areaName(domain))
      )
  end Samples
end YearCopy
