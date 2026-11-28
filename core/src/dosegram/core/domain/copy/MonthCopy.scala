package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MinimumSample
import dosegram.core.domain.insights.MonthDomain
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.MonthStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.Stat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** The text of a calendar month (BOT_UX section 12.5, VISUALS section 6.6): one block per domain that met the section
  * gate of PRODUCT_SPEC R-INS-11, and the gate with its progress for a domain that did not. The figures are the lines
  * of [[StatsCopy]], so a month reads like any other period.
  */
object MonthCopy:

  import StatsCopy.more

  def name(month: YearMonth): String =
    tr(
      s"${month.getMonth.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH)} ${month.getYear}",
      s"${month.getMonth.getDisplayName(TextStyle.FULL_STANDALONE, Formats.locale).capitalize} ${month.getYear}"
    )

  def header(month: YearMonth, zone: ZoneId): String = s"${name(month)} · ${zone.getId}"

  /** R-INS-11: a month without a domain at the gate has no monthly summary; the text says what is still needed. */
  def gateTitle: String =
    tr(
      s"A monthly summary needs one area with records on at least ${MinimumSample.MonthlyReportDays.required} days.",
      s"Для місячного підсумку потрібен розділ, у якому записи є щонайменше за ${UkNouns.Days.count(MinimumSample.MonthlyReportDays.required)}."
    )

  def daysWithRecords(days: Int): String =
    tr(s"${StatsCopy.count(days, "day")} with records", s"${UkNouns.Days.count(days)} із записами")

  /** The days with records and, when the month has days whose only doses have no record, the line that says they are
    * not counted (the year's line, supervisor decision of 2026-11-28).
    */
  def summary(figures: MonthFigures): List[String] =
    daysWithRecords(figures.daysWithRecords) ::
      Option.when(figures.outageOnlyDays > 0)(StatsCopy.outageDaysNotCounted(figures.outageOnlyDays)).toList

  def gate(domainName: String, domain: MonthDomain[?]): String =
    tr(
      s"$domainName: ${more(domain.missingDays, "day")} with records for the month's figures " +
        s"(${domain.recordedDays} of ${MinimumSample.MonthlyReportDays.required})",
      s"$domainName: для цифр місяця потрібно ще ${UkNouns.Days.count(domain.missingDays)} із записами " +
        s"(${domain.recordedDays} з ${MinimumSample.MonthlyReportDays.required})"
    )

  /** The user's own previous month, stated, never judged (VISUALS VA-PR-07). */
  def previous(month: YearMonth, adherence: Stat[dosegram.core.domain.insights.Ratio]): Option[String] =
    adherence.shown.map(ratio =>
      tr(
        s"Previous month (${name(month.minusMonths(1))}): ${ratio.numerator} of ${ratio.denominator} taken " +
          s"(${ratio.percent}%)",
        s"Попередній місяць (${name(month.minusMonths(1))}): прийнято ${ratio.numerator} з ${ratio.denominator} " +
          s"(${ratio.percent}%)"
      )
    )

  /** Every line of the month's text: the header, the days with records, then one block per domain. */
  def lines(figures: MonthFigures, zone: ZoneId): List[String] = render(figures, zone, keepGates = true)

  /** The month text with every minimum-data gate left out (R-GAM-5). A domain below its gate is absent. */
  def linesOmittingGates(figures: MonthFigures, zone: ZoneId): List[String] = render(figures, zone, keepGates = false)

  private def render(figures: MonthFigures, zone: ZoneId, keepGates: Boolean): List[String] =
    def closed[A](domain: MonthDomain[A], name: String)(shown: A => List[String]): List[String] =
      if domain.meetsGate then shown(domain.figures)
      else if keepGates then List(gate(name, domain))
      else Nil
    val blocks =
      figures.medication.toList.flatMap(domain =>
        closed(domain, tr("Doses", "Дози"))(period =>
          StatsCopy.doses(period.total) ++
            previous(figures.month, figures.previousAdherence).filter(_ => period.total.adherence.shown.isDefined)
        )
      ) ++
        figures.hydration.toList.flatMap(domain =>
          closed(domain, tr("Water", "Вода"))(period =>
            (if keepGates then StatsCopy.water(period) else StatsCopy.waterOmittingGates(period)).toList
          )
        ) ++
        figures.mood.toList.flatMap(domain =>
          closed(domain, tr("Mood", "Настрій"))(period =>
            (if keepGates then StatsCopy.mood(period) else StatsCopy.moodOmittingGates(period)).toList
          )
        ) ++
        figures.sleep.toList.flatMap(domain =>
          closed(domain, tr("Sleep", "Сон"))(period =>
            (if keepGates then StatsCopy.sleep(period) else StatsCopy.sleepOmittingGates(period)).toList
          )
        ) ++
        figures.food.toList.flatMap(domain =>
          closed(domain, tr("Food", "Їжа"))(period => FoodCopy.statsPeriod(period).toList)
        )
    val intro = if figures.isReportable || !keepGates then Nil else List(gateTitle)
    header(figures.month, zone) :: summary(figures) ++ intro ++ blocks

  def entries: List[CopyEntry] = Samples.entries

  private object Samples:
    private val month = YearMonth.of(2026, 10)
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

    private def days[A](count: Int)(value: Int => A): Map[LocalDate, A] =
      (1 to count).map(day => month.atDay(day) -> value(day)).toMap

    private def figures(recorded: Int): MonthFigures =
      val doses = days(recorded)(day =>
        MedicationDay(
          Map(
            medication -> DoseBuckets.ofResolved(
              AdherenceCounts(if day % 9 == 0 then 0 else 2, 0, 0, if day % 9 == 0 then 2 else 0)
            )
          )
        )
      )
      val previous = (1 to 30)
        .map(day =>
          month.minusMonths(1).atDay(day) -> MedicationDay(
            Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(2, 0, 0, 0)))
          )
        )
        .toMap
      MonthStats.of(
        month,
        DayOfWeek.MONDAY,
        medication = Some(doses -> Nil),
        hydration = Some(days(recorded)(day => HydrationDay(1500 + 20 * day, 4, Some(2000)))),
        mood = Some(days(recorded / 2)(day => MoodDay(1, 3 + day % 2, 3, 4, 0, 0))),
        sleep = Some(days(recorded)(day => SleepNight(400 + day, None))),
        previousMedication = Some(previous)
      )

    def entries: List[CopyEntry] =
      List(
        CopyEntry("stats.month", lines(figures(22), kyiv).mkString("\n")),
        CopyEntry("stats.month.gate", lines(figures(9), kyiv).mkString("\n"))
      )
  end Samples
end MonthCopy
