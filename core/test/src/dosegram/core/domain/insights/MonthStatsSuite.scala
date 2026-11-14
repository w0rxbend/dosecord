package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceCounts

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** VISUALS_AND_ANALYTICS section 6.6 and PRODUCT_SPEC R-INS-11: the month's week rows, the section gate of 14 days, the
  * weekly mean gate of a week row, the previous month as the only baseline, and a domain without consent left out.
  */
class MonthStatsSuite extends munit.FunSuite:

  private val october = YearMonth.of(2026, 10)
  private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

  private def days[A](numbers: Iterable[Int])(value: Int => A): Map[LocalDate, A] =
    numbers.map(day => october.atDay(day) -> value(day)).toMap

  private def doses(taken: Int, missed: Int): MedicationDay =
    MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(taken, 0, 0, missed))))

  private def month(
      medicationDays: Option[Map[LocalDate, MedicationDay]] = None,
      hydration: Option[Map[LocalDate, HydrationDay]] = None,
      mood: Option[Map[LocalDate, MoodDay]] = None,
      sleep: Option[Map[LocalDate, SleepNight]] = None,
      previous: Option[Map[LocalDate, MedicationDay]] = None
  ): MonthFigures =
    MonthStats.of(october, DayOfWeek.MONDAY, medicationDays.map(_ -> Nil), hydration, mood, sleep, previous)

  test("the week rows start on the week start and are cut at the month's edges"):
    val weeks = MonthStats.weeks(october, DayOfWeek.MONDAY)
    assertEquals(weeks.head, DateRange.between(october.atDay(1), october.atDay(4)))
    assertEquals(weeks.last, DateRange.between(october.atDay(26), october.atDay(31)))
    assertEquals(weeks.size, 5)
    assertEquals(weeks.map(_.days).sum, 31)
    assertEquals(MonthStats.weeks(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size, 4)

  test("a domain with records on 14 days meets the gate; 13 does not (R-INS-11)"):
    val fourteen = month(hydration = Some(days(1 to 14)(_ => HydrationDay(1500, 2, None))))
    assert(fourteen.hydration.get.meetsGate)
    assert(fourteen.isReportable)
    val thirteen = month(hydration = Some(days(1 to 13)(_ => HydrationDay(1500, 2, None))))
    assert(!thirteen.hydration.get.meetsGate)
    assertEquals(thirteen.hydration.get.missingDays, 1)
    assert(!thirteen.isReportable)

  test("a month is reportable when one domain meets the gate, whatever the others"):
    val figures = month(
      hydration = Some(days(1 to 20)(_ => HydrationDay(1500, 2, None))),
      mood = Some(days(1 to 3)(_ => MoodDay(1, 3, 3, 3, 0, 0)))
    )
    assert(figures.isReportable)
    assert(!figures.mood.get.meetsGate)

  test("a domain without consent is absent, not empty"):
    val figures = month(hydration = Some(Map.empty))
    assertEquals(figures.medication, None)
    assertEquals(figures.hydration.map(_.recordedDays), Some(0))

  test("days with records are the union over the domains"):
    val figures = month(
      hydration = Some(days(1 to 10)(_ => HydrationDay(1500, 2, None))),
      mood = Some(days(5 to 15)(_ => MoodDay(1, 3, 3, 3, 0, 0)))
    )
    assertEquals(figures.daysWithRecords, 15)

  test("a week row shows a mean only with three recorded days; below that it is a gap, never a zero"):
    val figures = month(hydration = Some(days(List(1, 2, 3, 6, 7))(_ => HydrationDay(1800, 2, None))))
    assertEquals(figures.weeks.head.waterMeanMl.shown, Some(1800))
    assertEquals(figures.weeks(1).waterMeanMl, Stat.Insufficient(2, 3, figures.weeks(1).days))

  test("the week row pools the dose buckets of its days"):
    val figures = month(medicationDays = Some(days(1 to 4)(_ => doses(2, 1))))
    assertEquals(figures.weeks.head.doses.resolved, AdherenceCounts(8, 0, 0, 4))
    assertEquals(figures.medication.get.figures.total.adherence.shown, Some(Ratio(8, 12)))

  test("a medication day with only cancelled doses is not a recorded day"):
    val figures = month(medicationDays = Some(days(1 to 20)(day => if day <= 15 then doses(1, 0) else MedicationDay(Map.empty))))
    assertEquals(figures.medication.get.recordedDays, 15)

  test("records outside the month are left out"):
    val september = Map(october.minusMonths(1).atEndOfMonth -> HydrationDay(5000, 1, None))
    val figures = month(hydration = Some(september ++ days(1 to 2)(_ => HydrationDay(1000, 1, None))))
    assertEquals(figures.hydration.get.figures.totalMl, 2000)

  test("the previous month's adherence is the baseline, behind the percentage gate"):
    val previous = (1 to 30).map(day => october.minusMonths(1).atDay(day) -> doses(1, 0)).toMap
    assertEquals(month(previous = Some(previous)).previousAdherence.shown, Some(Ratio(30, 30)))
    val few = (1 to 4).map(day => october.minusMonths(1).atDay(day) -> doses(1, 0)).toMap
    assertEquals(month(previous = Some(few)).previousAdherence.shown, None)
    assertEquals(month().previousAdherence.shown, None)
end MonthStatsSuite
