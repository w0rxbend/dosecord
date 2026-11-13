package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Table tests for the edge cases of VISUALS_AND_ANALYTICS section 7.2 (VA-ME-11) and the display rules of PRODUCT_SPEC
  * R-INT-11.
  */
class MedicationStatsSuite extends munit.FunSuite:

  private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
  private val day: LocalDate = LocalDate.parse("2026-03-10")
  private val week: DateRange = DateRange.endingOn(day, 7)
  private val scheduledFor: Instant = Instant.parse("2026-03-10T07:00:00Z")
  private val dueWindowEnd: Instant = scheduledFor.plusSeconds(3600)
  private val medication: UUID = UUID.fromString("00000000-0000-7000-8000-000000000001")

  private def input(
      status: OccurrenceStatus,
      effectiveAt: Option[Instant] = None,
      resolvedAt: Option[Instant] = None,
      manual: Boolean = false
  ): AdherenceInput =
    AdherenceInput(scheduledFor, status, effectiveAt, resolvedAt, dueWindowEnd, manual, corrected = false)

  private def counts(taken: Int, skipped: Int, missed: Int): AdherenceCounts =
    AdherenceCounts(takenOnTime = taken, takenLate = 0, skipped = skipped, missed = missed)

  test("8 taken, 1 skipped and 1 missed is 80 percent of 10 resolved doses (AC-INT-8)"):
    MedicationStats.adherence(counts(8, 1, 1), week) match
      case Stat.Shown(ratio, n, window, basis) =>
        assertEquals((ratio.numerator, ratio.denominator, ratio.percent), (8, 10, 80))
        assertEquals((n, window, basis), (10, week, Basis.Recorded))
      case other => fail(s"expected a shown ratio, got $other")

  test("a denominator of 1 to 4 has no percentage and a denominator of 0 has no data"):
    assertEquals(MedicationStats.adherence(counts(3, 0, 1), week), Stat.Insufficient(4, 5, week))
    assertEquals(MedicationStats.adherence(AdherenceCounts.zero, week), Stat.Insufficient(0, 5, week))

  test("skipped lowers adherence and counts as an answer in the recorded rate"):
    val resolved = counts(4, 1, 1)
    assertEquals(MedicationStats.adherence(resolved, week).shown, Some(Ratio(4, 6)))
    assertEquals(MedicationStats.recordedRate(resolved, week).shown, Some(Ratio(5, 6)))

  test("the on-time rate is over taken doses only"):
    val resolved = AdherenceCounts(takenOnTime = 4, takenLate = 2, skipped = 3, missed = 3)
    assertEquals(MedicationStats.onTimeRate(resolved, week).shown, Some(Ratio(4, 6)))
    assertEquals(MedicationStats.onTimeRate(counts(0, 5, 5), week), Stat.Insufficient(0, 5, week))

  test("a dose taken before its scheduled time is on time"):
    val early = input(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.minusSeconds(1800)))
    assertEquals(MedicationStats.bucket(early), DoseBuckets.zero.copy(takenOnTime = 1))

  test("a dose taken after the due window is late, and counts on the day it was taken"):
    val late = input(OccurrenceStatus.Taken, effectiveAt = Some(Instant.parse("2026-03-10T22:30:00Z")))
    assertEquals(MedicationStats.bucket(late), DoseBuckets.zero.copy(takenLate = 1))
    assertEquals(MedicationStats.dayOf(late, kyiv), Some(day.plusDays(1)))

  test("unknown and open doses are counted beside the rates, on their scheduled day"):
    val unknown = input(OccurrenceStatus.Unknown)
    val due = input(OccurrenceStatus.Due)
    assertEquals(MedicationStats.bucket(unknown), DoseBuckets.zero.copy(unknown = 1))
    assertEquals(MedicationStats.bucket(due), DoseBuckets.zero.copy(open = 1))
    assertEquals(MedicationStats.dayOf(unknown, kyiv), Some(day))
    assertEquals(MedicationStats.dayOf(due, kyiv), Some(day))

  test("a cancelled occurrence is in no bucket and on no day"):
    val cancelled = input(OccurrenceStatus.Cancelled)
    assertEquals(MedicationStats.bucket(cancelled), DoseBuckets.zero)
    assertEquals(MedicationStats.dayOf(cancelled, kyiv), None)

  test("an as-needed log is a descriptive count and never enters a rate"):
    val log = input(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor), manual = true)
    val days = MedicationStats.byDay(List(DoseRecord(medication, log)), kyiv)
    assertEquals(days(day).total, DoseBuckets.zero.copy(asNeeded = 1))
    assertEquals(days(day).total.resolved, AdherenceCounts.zero)
    assertEquals(MedicationStats.daysWithAsNeededLog(days), 1)

  test("the day fraction is defined from one resolved dose and undefined without one"):
    val oneTaken = DoseBuckets.zero.copy(takenOnTime = 1, skipped = 1)
    assertEquals(MedicationStats.dayFraction(oneTaken, day).shown, Some(Ratio(1, 2)))
    val onlyOpen = DoseBuckets.zero.copy(open = 2, unknown = 1)
    assertEquals(MedicationStats.dayFraction(onlyOpen, day), Stat.Insufficient(0, 1, DateRange.single(day)))

  test("delay statistics need five samples"):
    val taken = input(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(600)))
    assertEquals(MedicationStats.delay(List.fill(4)(taken), week), Stat.Insufficient(4, 5, week))
    assertEquals(MedicationStats.delay(Nil, week), Stat.Insufficient(0, 5, week))
    assertEquals(MedicationStats.delay(List.fill(5)(taken), week).shown.map(_.samples), Some(5))
end MedicationStatsSuite
