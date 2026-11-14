package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.SleepStats

import java.time.LocalDate

/** The comparison with the user's own previous period (MINI_APP MA-INS-3): in words, with both figures, no arrow, no
  * colour word and no percentage change; a zero denominator is "No data" (MA-INS-4).
  */
class TrendCopySuite extends munit.FunSuite:

  private val end = LocalDate.parse("2026-11-07")
  private val month = DateRange.endingOn(end, 30)

  private def days[A](count: Int)(value: Int => A): Map[LocalDate, A] =
    List.tabulate(count)(back => end.minusDays(back.toLong) -> value(back)).toMap

  test("the previous period of doses is counted with both figures and the percentage only from five doses"):
    val figures = MedicationStats.figures(DoseBuckets.ofResolved(AdherenceCounts(80, 10, 15, 5)), month)
    assertEquals(TrendCopy.doses(30, figures), "Previous 30 days: 90 of 110 taken (82%)")
    val few = MedicationStats.figures(DoseBuckets.ofResolved(AdherenceCounts(2, 1, 0, 1)), month)
    assertEquals(TrendCopy.doses(30, few), "Previous 30 days: 3 of 4 taken")
    assertEquals(TrendCopy.doses(7, MedicationStats.figures(DoseBuckets.zero, month)), "Previous 7 days: No data")

  test("water, mood, sleep and food each name the previous period and its own figures"):
    val water = HydrationStats.period(days(12)(back => HydrationDay(1500 + 10 * back, 3, Some(1550))), month)
    assertEquals(
      TrendCopy.water(30, water),
      "Previous 30 days: average 1.6 l a day · goal reached on 7 of 12 recorded days"
    )
    val mood = MoodStats.period(days(10)(_ => MoodDay(1, 4, 4, 4, 0, 0)), month)
    assertEquals(TrendCopy.mood(30, mood), "Previous 30 days: average 4.0 (pleasant) · check-ins on 10 days")
    val sleep = SleepStats.period(days(10)(_ => SleepNight(410, None)), month)
    assertEquals(TrendCopy.sleep(30, sleep), "Previous 30 days: average 6 h 50 min · 10 nights logged")
    val food = MealStats.period(days(4)(_ => MealDay(1, 1, 0, 1)), month)
    assertEquals(TrendCopy.food(30, food), "Previous 30 days: 12 meals logged on 4 days")

  test("a previous period below its gate gives counts and no average, and an empty one says No data"):
    val water = HydrationStats.period(days(2)(_ => HydrationDay(1500, 3, None)), month)
    assertEquals(TrendCopy.water(30, water), "Previous 30 days: water logged on 2 days")
    assertEquals(TrendCopy.water(30, HydrationStats.period(Map.empty, month)), "Previous 30 days: No data")
    assertEquals(TrendCopy.mood(30, MoodStats.period(Map.empty, month)), "Previous 30 days: No data")
    assertEquals(TrendCopy.sleep(30, SleepStats.period(Map.empty, month)), "Previous 30 days: No data")
    assertEquals(TrendCopy.food(30, MealStats.period(Map.empty, month)), "Previous 30 days: No data")

  test("bedtime regularity is printed with its spreads and usual times, or with the nights still needed"):
    val shown = SleepRegularityFigures(month, Nil, 24, Some(415), Some(35), Some(20), Some(23 * 60 + 10), Some(425), None)
    assertEquals(
      TrendCopy.regularity(shown),
      List("Bedtime varied by about 35 min, wake time by about 20 min", "Usual bedtime 23:10 · usual wake time 07:05")
    )
    val gated = shown.copy(nights = 8, bedtimeSpreadMinutes = None, wakeSpreadMinutes = None, typicalBedtime = None)
    assertEquals(TrendCopy.regularity(gated), List("14 more nights for bedtime regularity"))
    assertEquals(TrendCopy.regularity(gated.copy(nights = 0)), Nil)

  test("the food figures of a period are counts per main meal, with no rate"):
    val food = MealStats.period(days(4)(back => MealDay(1, if back < 2 then 1 else 0, 0, 0)), month)
    assertEquals(TrendCopy.foodFigures(food), List("6 meals logged on 4 of 30 days", "Breakfast 4, Lunch 2, Dinner 0 of 30 days"))
    assertEquals(TrendCopy.foodFigures(MealStats.period(Map.empty, month)), Nil)

  test("every sample is free of forbidden and causal phrases"):
    TrendCopy.entries.foreach: entry =>
      assertEquals(ForbiddenPhrases.violations(entry.text), Nil, entry.id)
      assertEquals(InsightPhrases.violations(entry.text), Nil, entry.id)
