package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import org.scalacheck.Gen

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Generators shared by the metric properties. The zones cover no daylight saving, the European rule and the North
  * American rule; `dstDays` are the 23-hour and 25-hour local days of 2026 in the two zones that have them.
  */
object InsightGens:

  val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
  val newYork: ZoneId = ZoneId.of("America/New_York")
  val utc: ZoneId = ZoneId.of("UTC")

  val genZone: Gen[ZoneId] = Gen.oneOf(utc, kyiv, newYork)

  /** (zone, local date, hours in that local day). */
  val dstDays: List[(ZoneId, LocalDate, Int)] = List(
    (kyiv, LocalDate.parse("2026-03-29"), 23),
    (kyiv, LocalDate.parse("2026-10-25"), 25),
    (newYork, LocalDate.parse("2026-03-08"), 23),
    (newYork, LocalDate.parse("2026-11-01"), 25)
  )

  val genDstDay: Gen[(ZoneId, LocalDate, Int)] = Gen.oneOf(dstDays)

  /** An instant inside the local day, uniform over its real length. */
  def genInstantOn(date: LocalDate, zone: ZoneId): Gen[Instant] =
    val start = LocalDays.startOf(date, zone).getEpochSecond
    val end = LocalDays.endOf(date, zone).getEpochSecond
    Gen.choose(start, end - 1).map(Instant.ofEpochSecond)

  /** 2026-01-01T00:00:00Z .. 2026-12-31T00:00:00Z. */
  val genInstant: Gen[Instant] = Gen.choose(1767225600L, 1798675200L).map(Instant.ofEpochSecond)

  val medications: List[UUID] = List.tabulate(3)(i => UUID.fromString(s"00000000-0000-7000-8000-00000000000$i"))

  /** A status-consistent occurrence scheduled at `scheduledFor`: a taken row carries an effective instant (on time or
    * late, sometimes on a later day), a skipped or missed row a resolution instant.
    */
  def genInputAt(scheduledFor: Instant): Gen[AdherenceInput] =
    for
      status <- Gen.oneOf(OccurrenceStatus.values.toList)
      graceSeconds <- Gen.choose(300L, 14400L)
      delta <- Gen.choose(-7200L, 172800L)
      manual <- Gen.frequency(1 -> true, 6 -> false)
      corrected <- Gen.frequency(1 -> true, 6 -> false)
    yield AdherenceInput(
      scheduledFor = scheduledFor,
      status = status,
      effectiveAt = Option.when(status == OccurrenceStatus.Taken)(scheduledFor.plusSeconds(delta)),
      resolvedAt = Option.when(status == OccurrenceStatus.Skipped || status == OccurrenceStatus.Missed)(
        scheduledFor.plusSeconds(delta)
      ),
      dueWindowEnd = scheduledFor.plusSeconds(graceSeconds),
      manual = manual,
      corrected = corrected
    )

  val genRecord: Gen[DoseRecord] =
    for
      medication <- Gen.oneOf(medications)
      scheduledFor <- genInstant
      input <- genInputAt(scheduledFor)
    yield DoseRecord(medication, input)

  val genRecords: Gen[List[DoseRecord]] = Gen.choose(0, 40).flatMap(Gen.listOfN(_, genRecord))
end InsightGens
