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
  def lines(figures: MonthFigures, zone: ZoneId): List[String] =
    val blocks =
      figures.medication.toList.flatMap(domain =>
        if domain.meetsGate then
          StatsCopy.doses(domain.figures.total) ++
            previous(figures.month, figures.previousAdherence).filter(_ =>
              domain.figures.total.adherence.shown.isDefined
            )
        else List(gate(tr("Doses", "Дози"), domain))
      ) ++
        figures.hydration.toList.flatMap(domain =>
          if domain.meetsGate then StatsCopy.water(domain.figures).toList else List(gate(tr("Water", "Вода"), domain))
        ) ++
        figures.mood.toList.flatMap(domain =>
          if domain.meetsGate then StatsCopy.mood(domain.figures).toList else List(gate(tr("Mood", "Настрій"), domain))
        ) ++
        figures.sleep.toList.flatMap(domain =>
          if domain.meetsGate then StatsCopy.sleep(domain.figures).toList else List(gate(tr("Sleep", "Сон"), domain))
        ) ++
        figures.food.toList.flatMap(domain =>
          if domain.meetsGate then FoodCopy.statsPeriod(domain.figures).toList
          else List(gate(tr("Food", "Їжа"), domain))
        )
    val intro = if figures.isReportable then Nil else List(gateTitle)
    header(figures.month, zone) :: daysWithRecords(figures.daysWithRecords) :: intro ++ blocks

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
