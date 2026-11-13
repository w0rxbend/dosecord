package dosegram.core.domain.insights

import java.time.LocalDate

/** A chart of days is a statistic like any other: it is shown only from one recorded day on, and a day without a record
  * is a gap, never a zero (VISUALS_AND_ANALYTICS section 7.1, VA-ME-03).
  */
class DaySeriesSuite extends munit.FunSuite:

  private val end = LocalDate.parse("2026-11-13")
  private val week = DateRange.endingOn(end, 7)

  test("every day of the window is a point in order, and a day without a record has no value"):
    val series = DaySeries.of(Map(end -> 1250, end.minusDays(2) -> 900), week).shown.get
    assertEquals(series.map(_.date), week.dates)
    assertEquals(series.flatMap(_.value), List(900, 1250))
    assertEquals(series.count(_.value.isEmpty), 5)

  test("a window without a record is not a series of zeros: it is insufficient"):
    assertEquals(DaySeries.of(Map.empty[LocalDate, Int], week), Stat.Insufficient(0, 1, week))

  test("a record outside the window does not open the gate"):
    assertEquals(DaySeries.of(Map(end.minusDays(7) -> 400), week), Stat.Insufficient(0, 1, week))
