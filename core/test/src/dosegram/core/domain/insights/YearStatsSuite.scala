package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceCounts

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The year of `/stats year` (supervisor decision, 2026-11-28, analytics round 6): per area and month the recorded days
  * and a figure only from 14 of them, the year over all its recorded days behind the same gate, and the year before
  * only when it has a figure of its own.
  */
class YearStatsSuite extends munit.FunSuite:

  private val today = LocalDate.parse("2026-03-20")
  private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

  private def days[A](month: YearMonth, count: Int)(value: Int => A): Map[LocalDate, A] =
    (1 to count).map(day => month.atDay(day) -> value(day)).toMap

  private def water(month: YearMonth, count: Int, ml: Int): Map[LocalDate, HydrationDay] =
    days(month, count)(_ => HydrationDay(ml, 2, None))

  private def sources(
      medication: Option[Map[LocalDate, MedicationDay]] = None,
      hydration: Option[Map[LocalDate, HydrationDay]] = None,
      mood: Option[Map[LocalDate, MoodDay]] = None,
      sleep: Option[Map[LocalDate, SleepNight]] = None,
      food: Option[Map[LocalDate, MealDay]] = None
  ): YearSources = YearSources(medication, hydration, mood, sleep, food)

  private def row(figures: YearFigures, domain: TrendDomain): YearRow =
    figures.rows.find(_.domain == domain).getOrElse(fail(s"no $domain row in $figures"))

  test("the window of the current year ends today, a past year's covers all of it"):
    assertEquals(YearStats.window(2026, today), DateRange.between(LocalDate.parse("2026-01-01"), today))
    assertEquals(
      YearStats.window(2025, today),
      DateRange.between(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))
    )

  test("a month shows its figure from 14 recorded days; below that only its recorded days"):
    val hydration = water(YearMonth.of(2026, 1), 14, 2000) ++ water(YearMonth.of(2026, 2), 9, 1000)
    val figures = YearStats.of(2026, today, sources(hydration = Some(hydration)))
    val months = row(figures, TrendDomain.Hydration).months.toMap
    assertEquals(months.keySet, Set(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)))
    assertEquals(months(YearMonth.of(2026, 1)).recordedDays, 14)
    assertEquals(months(YearMonth.of(2026, 1)).figure.shown, Some(YearFigure.WaterMean(2000)))
    assertEquals(months(YearMonth.of(2026, 2)).figure, Stat.Insufficient(9, 14, MonthStats.window(YearMonth.of(2026, 2))))
    assertEquals(months(YearMonth.of(2026, 3)).recordedDays, 0)

  test("the year's figure is the mean of all its recorded days, behind the same 14-day gate"):
    val hydration = water(YearMonth.of(2026, 1), 14, 2000) ++ water(YearMonth.of(2026, 2), 7, 1300)
    val year = row(YearStats.of(2026, today, sources(hydration = Some(hydration))), TrendDomain.Hydration).year
    assertEquals((year.recordedDays, year.figure.shown), (21, Some(YearFigure.WaterMean(1767))))
    val few = row(YearStats.of(2026, today, sources(hydration = Some(water(YearMonth.of(2026, 1), 13, 900)))), TrendDomain.Hydration)
    assertEquals(few.year.figure.shown, None)

  test("the previous year is read in full and has a figure only from 14 recorded days of its own"):
    val now = water(YearMonth.of(2026, 1), 14, 2000)
    val shown = YearStats.of(2026, today, sources(hydration = Some(now ++ water(YearMonth.of(2025, 12), 20, 1500))))
    assertEquals(row(shown, TrendDomain.Hydration).previous.figure.shown, Some(YearFigure.WaterMean(1500)))
    val thin = YearStats.of(2026, today, sources(hydration = Some(now ++ water(YearMonth.of(2025, 12), 5, 1500))))
    assertEquals(row(thin, TrendDomain.Hydration).previous.recordedDays, 5)
    assertEquals(row(thin, TrendDomain.Hydration).previous.figure.shown, None)
    assert(thin.previousHasRecords)

  test("an area without its consent has no row; the days with records join every shown area"):
    val figures = YearStats.of(
      2026,
      today,
      sources(
        hydration = Some(water(YearMonth.of(2026, 1), 3, 500)),
        sleep = Some(days(YearMonth.of(2026, 1), 5)(_ => SleepNight(420, None)))
      )
    )
    assertEquals(figures.rows.map(_.domain), List(TrendDomain.Hydration, TrendDomain.Sleep))
    assertEquals(figures.daysWithRecords, 5)
    assertEquals(figures.window.days, 79)

  test("medication counts the doses taken of those answered and needs five of them as well as 14 days"):
    val month = YearMonth.of(2026, 1)
    def dosesOn(count: Int, counts: AdherenceCounts) =
      days(month, count)(_ => MedicationDay(Map(medication -> DoseBuckets.ofResolved(counts))))
    val shown = YearStats.of(2026, today, sources(medication = Some(dosesOn(20, AdherenceCounts(1, 0, 0, 0)))))
    assertEquals(
      row(shown, TrendDomain.Medication).months.head._2.figure.shown,
      Some(YearFigure.Taken(Ratio(20, 20)))
    )
    // Fourteen days on which every dose was cancelled or open are no answered dose at all.
    val none = YearStats.of(
      2026,
      today,
      sources(medication = Some(days(month, 14)(_ => MedicationDay(Map(medication -> DoseBuckets.zero.copy(open = 1))))))
    )
    assertEquals(row(none, TrendDomain.Medication).months.head._2.figure.shown, None)

  test("mood, sleep and food average their own recorded days"):
    val month = YearMonth.of(2026, 1)
    val figures = YearStats.of(
      2026,
      today,
      sources(
        mood = Some(days(month, 14)(day => MoodDay(1, if day <= 7 then 4 else 3, 3, 4, 0, 0))),
        sleep = Some(days(month, 14)(day => SleepNight(if day <= 7 then 400 else 440, None))),
        food = Some(days(month, 14)(day => if day <= 7 then MealDay(1, 1, 1, 0) else MealDay(0, 1, 0, 2)))
      )
    )
    assertEquals(row(figures, TrendDomain.Mood).months.head._2.figure.shown.map(_.toString), Some(YearFigure.MoodMean(3.5).toString))
    assertEquals(row(figures, TrendDomain.Sleep).months.head._2.figure.shown, Some(YearFigure.SleepMean(420)))
    assertEquals(row(figures, TrendDomain.Nutrition).months.head._2.figure.shown, Some(YearFigure.MealsMean(2.0)))

  test("a day without a record is never a zero: a water or meal rollup with no entry is no recorded day"):
    val month = YearMonth.of(2026, 1)
    val figures = YearStats.of(
      2026,
      today,
      sources(
        hydration = Some(days(month, 14)(_ => HydrationDay(0, 0, None))),
        food = Some(days(month, 14)(_ => MealDay(0, 0, 0, 0)))
      )
    )
    assertEquals(row(figures, TrendDomain.Hydration).year.recordedDays, 0)
    assertEquals(row(figures, TrendDomain.Nutrition).year.recordedDays, 0)
    assertEquals(figures.daysWithRecords, 0)

  test("a year after today has no window"):
    intercept[IllegalArgumentException](YearStats.window(2027, today))
end YearStatsSuite
