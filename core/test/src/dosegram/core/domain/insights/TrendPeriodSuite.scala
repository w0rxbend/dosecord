package dosegram.core.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate

/** The dashboard periods of MINI_APP MA-INS-1 and VISUALS section 9.2: 7, 30 and 90 days ending today, each compared
  * with the window of the same length just before it (MA-INS-3); 90 days is bucketed in weeks.
  */
class TrendPeriodSuite extends munit.FunSuite:

  import TrackingGens.*

  private val today = LocalDate.parse("2026-11-14")

  test("the offered periods are 7, 30 and 90 days, and no other length is a period"):
    assertEquals(TrendPeriod.values.toList.map(_.days), List(7, 30, 90))
    assertEquals(TrendPeriod.of(30), Some(TrendPeriod.Month))
    assertEquals(TrendPeriod.of(14), None)

  test("the current window ends today and the previous one ends the day before it starts"):
    val period = TrendPeriod.Week
    assertEquals(period.current(today), DateRange.between(LocalDate.parse("2026-11-08"), today))
    assertEquals(
      period.previous(today),
      DateRange.between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07"))
    )
    assertEquals(TrendPeriod.Quarter.previous(today).days, 90)

  test("7 and 30 days are drawn by day, 90 days by week"):
    assert(!TrendPeriod.Week.byWeek)
    assert(!TrendPeriod.Month.byWeek)
    assert(TrendPeriod.Quarter.byWeek)

  test("weeks are cut from the last day backwards: the latest week is whole, the oldest takes the rest"):
    val window = TrendPeriod.Quarter.current(today)
    val weeks = TrendPeriod.weeks(window)
    assertEquals(weeks.size, 13)
    assertEquals(weeks.last, DateRange.endingOn(today, 7))
    assertEquals(weeks.head.start, window.start)
    assertEquals(weeks.head.days, 6)
    assertEquals(weeks.map(_.days).sum, 90)
    weeks.zip(weeks.drop(1)).foreach((earlier, later) => assertEquals(earlier.end.plusDays(1), later.start))

  test("mood by day of the week averages the daily means of each weekday, behind its own gate of 3 days"):
    // windowEnd 2026-06-30 is a Tuesday.
    val tuesdays = (0 until 3).map(n => moodOn(windowEnd.minusDays(7L * n), 2 + n)).toList
    val monday = List(moodOn(windowEnd.minusDays(1), 5))
    val weekdays = MoodStats.byWeekday(MoodStats.byDay(tuesdays ++ monday), month)
    assertEquals(weekdays.map(_.day), DayOfWeek.values.toList)
    val tuesday = weekdays.find(_.day == DayOfWeek.TUESDAY).get
    assertEquals(tuesday.days, 3)
    assertEquals(tuesday.mean.shown, Some(3.0))
    val mondayRow = weekdays.find(_.day == DayOfWeek.MONDAY).get
    assertEquals(mondayRow.days, 1)
    assertEquals(mondayRow.mean, Stat.Insufficient(1, MinimumSample.WeekdayMeanDays.required, month))

  test("a check-in outside the window does not count for its weekday"):
    val outside = List(moodOn(month.start.minusDays(7), 5), moodOn(month.start.minusDays(14), 5))
    val inside = List(moodOn(month.start, 5))
    val weekdays = MoodStats.byWeekday(MoodStats.byDay(outside ++ inside), month)
    assertEquals(weekdays.find(_.day == month.start.getDayOfWeek).get.days, 1)
