package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.Lang
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Two periods side by side (supervisor decision, 2026-11-28): the windows of each pair, the wire codes, the gates of a
  * week (3 recorded days) and a month (14), the one definition of days with records, and the difference in the words of
  * VISUALS section 7.1, read from the printed figures.
  */
class PeriodComparisonSuite extends munit.ScalaCheckSuite:

  // A Thursday.
  private val today = LocalDate.parse("2026-11-26")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val medication = UUID.fromString("00000000-0000-7000-8000-0000000000d1")

  private def days[A](from: LocalDate, to: LocalDate)(value: LocalDate => A): Map[LocalDate, A] =
    DateRange.between(from, to).dates.map(date => date -> value(date)).toMap

  private val empty = YearSources(None, None, None, None, None)

  test("this week runs from Monday to today and is compared with the whole week before it"):
    val frame = CompareFrame.of(CompareSelection.Weeks, today).get
    assertEquals(frame.earlier.window, DateRange.between(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22")))
    assertEquals(frame.later.window, DateRange.between(LocalDate.parse("2026-11-23"), today))
    assertEquals((frame.earlier.name, frame.later.name), (PeriodName.LastWeek, PeriodName.ThisWeek))
    assertEquals(frame.gate, MinimumSample.WeeklyMeanDays)

  test("this month runs from the first to today and is compared with the whole month before it"):
    val frame = CompareFrame.of(CompareSelection.Months, today).get
    assertEquals(frame.earlier.window, DateRange.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")))
    assertEquals(frame.later.window, DateRange.between(LocalDate.parse("2026-11-01"), today))
    assertEquals(frame.gate, MinimumSample.MonthlyReportDays)

  test("two months are ordered, whole, the current one cut at today, and a month after today is refused"):
    val pair = CompareSelection.twoMonths(YearMonth.of(2026, 11), YearMonth.of(2026, 3)).get
    val frame = CompareFrame.of(pair, today).get
    assertEquals(frame.earlier.name, PeriodName.Month(YearMonth.of(2026, 3)))
    assertEquals(frame.earlier.window.days, 31)
    assertEquals(frame.later.window, DateRange.between(LocalDate.parse("2026-11-01"), today))
    assertEquals(CompareSelection.twoMonths(YearMonth.of(2026, 5), YearMonth.of(2026, 5)), None)
    val ahead = CompareSelection.twoMonths(YearMonth.of(2026, 10), YearMonth.of(2026, 12)).get
    assertEquals(CompareFrame.of(ahead, today), None)

  property("every selection's code reads back as the same selection, and fits the 16 bits of a consent card"):
    val months = Gen.choose(0, 249).map(offset => CompareSelection.FirstMonth.plusMonths(offset.toLong))
    forAll(months, months) { (first, second) =>
      CompareSelection.twoMonths(first, second).forall { selection =>
        CompareSelection.of(selection.code) == Some(selection) && selection.code > 2 && selection.code <= 0xffff
      }
    }

  test("the fixed pairs have the codes 1 and 2, and any other code is no selection"):
    assertEquals(CompareSelection.of(1), Some(CompareSelection.Weeks))
    assertEquals(CompareSelection.of(2), Some(CompareSelection.Months))
    assertEquals(List(0L, 3L, 1023L, 70000L).flatMap(CompareSelection.of), Nil)
    assertEquals(CompareSelection.twoMonths(YearMonth.of(2024, 12), YearMonth.of(2025, 2)), None)

  test("a week shows a figure from 3 recorded days and a month from 14; below that only the recorded days"):
    val water = days(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"))(_ => HydrationDay(1500, 3, None)) ++
      days(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-24"))(_ => HydrationDay(1800, 3, None))
    val sources = empty.copy(hydration = Some(water))
    val weeks = PeriodComparisons.of(CompareFrame.of(CompareSelection.Weeks, today).get, sources)
    val row = weeks.rows.head
    assertEquals(row.earlier.figure.shown, Some(YearFigure.WaterMean(1500)))
    assertEquals(row.later.figure, Stat.Insufficient(2, 3, weeks.frame.later.window))
    assertEquals(row.difference, None)
    val months = PeriodComparisons.of(CompareFrame.of(CompareSelection.Months, today).get, sources)
    assertEquals(months.rows.head.later.figure.shown, None)
    assertEquals(months.rows.head.later.recordedDays, 5)

  test("an area without its source is absent; the rows keep the order medication, water, mood, sleep, food"):
    val sources = YearSources(
      Some(Map.empty),
      None,
      Some(Map.empty),
      Some(Map.empty),
      Some(Map.empty)
    )
    val figures = PeriodComparisons.of(CompareFrame.of(CompareSelection.Weeks, today).get, sources)
    assertEquals(
      figures.rows.map(_.domain),
      List(TrendDomain.Medication, TrendDomain.Mood, TrendDomain.Sleep, TrendDomain.Nutrition)
    )
    assert(!figures.hasRecords)

  test("a day whose only doses have no record is no day with records in either period"):
    val outage = MedicationDay(Map(medication -> DoseBuckets(0, 0, 0, 0, 2, 0, 0)))
    val taken = MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(1, 0, 0, 0))))
    val sources = empty.copy(
      medication = Some(Map(LocalDate.parse("2026-11-17") -> outage, LocalDate.parse("2026-11-23") -> taken))
    )
    val figures = PeriodComparisons.of(CompareFrame.of(CompareSelection.Weeks, today).get, sources)
    assertEquals(figures.earlierDays, RecordedDays(0, 1))
    assertEquals(figures.laterDays, RecordedDays(1, 0))
    assertEquals(figures.rows.head.earlier.recordedDays, 0)

  test("about the same under 2 points, 100 ml, 0.2 levels, 10 minutes and 0.2 meals; otherwise higher or lower"):
    def taken(n: Int, of: Int) = YearFigure.Taken(Ratio(n, of))
    assertEquals(PeriodComparisons.difference(taken(90, 100), taken(91, 100)), Difference.AboutTheSame)
    assertEquals(PeriodComparisons.difference(taken(90, 100), taken(94, 100)), Difference.Higher(4))
    assertEquals(PeriodComparisons.difference(YearFigure.WaterMean(1800), YearFigure.WaterMean(1760)), Difference.AboutTheSame)
    assertEquals(PeriodComparisons.difference(YearFigure.WaterMean(1800), YearFigure.WaterMean(1600)), Difference.Lower(200))
    assertEquals(PeriodComparisons.difference(YearFigure.MoodMean(3.2), YearFigure.MoodMean(3.4)), Difference.Higher(0.2))
    assertEquals(PeriodComparisons.difference(YearFigure.MoodMean(3.21), YearFigure.MoodMean(3.34)), Difference.AboutTheSame)
    assertEquals(PeriodComparisons.difference(YearFigure.SleepMean(420), YearFigure.SleepMean(415)), Difference.AboutTheSame)
    assertEquals(PeriodComparisons.difference(YearFigure.SleepMean(420), YearFigure.SleepMean(395)), Difference.Lower(25))
    assertEquals(PeriodComparisons.difference(YearFigure.MealsMean(2.0), YearFigure.MealsMean(2.5)), Difference.Higher(0.5))

  test("the difference agrees with the two printed figures, not with the unrounded means"):
    // 3.35 prints 3.4 and 3.14 prints 3.1: the words say 0.3, the gap of the printed figures.
    PeriodComparisons.difference(YearFigure.MoodMean(3.14), YearFigure.MoodMean(3.35)) match
      case Difference.Higher(amount) => assertEqualsDouble(amount, 0.3, 1e-9)
      case other                     => fail(s"expected higher, got $other")

  test("the text names both periods, keeps the gate while the counts are on, and closes with the non-causal line"):
    val water = days(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22"))(_ => HydrationDay(1600, 3, None)) ++
      days(LocalDate.parse("2026-11-23"), today)(_ => HydrationDay(1800, 3, None))
    val figures = PeriodComparisons.of(CompareFrame.of(CompareSelection.Weeks, today).get, empty.copy(hydration = Some(water)))
    val lines = Lang.within(Lang.En)(CompareCopy.lines(figures, kyiv, keepGates = true))
    assertEquals(
      lines,
      List(
        "Compare two periods · Europe/Kyiv",
        "Last week, 16 Nov to 22 Nov: days with records 7 of 7",
        "This week, 23 Nov to 26 Nov: days with records 4 of 4",
        "A week shows its figure once it has records on 3 days; fewer show those days.",
        "Each difference is the second period against the first.",
        "Water, a day with records: Last week 1.6 l (7 days) · This week 1.8 l (4 days) · 0.2 l higher",
        "This compares your own days. It does not show a cause."
      )
    )
    val omitting = Lang.within(Lang.En)(CompareCopy.lines(figures, kyiv, keepGates = false))
    assert(!omitting.exists(_.startsWith("A week shows")), omitting)
    assert(omitting.contains("Water, a day with records: Last week 1.6 l · This week 1.8 l · 0.2 l higher"), omitting)
    val uk = Lang.within(Lang.Uk)(CompareCopy.lines(figures, kyiv, keepGates = true))
    assert(uk.contains("Вода, за день із записами: Минулий тиждень 1,6 л (7 днів) · Цей тиждень 1,8 л (4 дні) · на 0,2 л більше"), uk)
    (lines ++ uk).foreach(line => assertEquals(InsightPhrases.violations(line), Nil, line))

  test("a pair with nothing recorded in either period says so"):
    val figures = PeriodComparisons.of(CompareFrame.of(CompareSelection.Months, today).get, empty.copy(mood = Some(Map.empty)))
    assertEquals(Lang.within(Lang.En)(CompareCopy.lines(figures, kyiv, keepGates = true)).last, "Nothing was recorded in either period.")
end PeriodComparisonSuite
