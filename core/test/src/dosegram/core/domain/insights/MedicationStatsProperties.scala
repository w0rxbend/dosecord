package dosegram.core.domain.insights

import dosegram.core.domain.Adherence
import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Duration
import java.time.LocalDate

/** ROADMAP G4.4 and VISUALS VA-ME-03, VA-ME-10: bounds, monotonicity, the minimum-sample gate, equality with the
  * inherited `Adherence` oracle, and day attribution on daylight-saving days of 23 and 25 hours.
  */
class MedicationStatsProperties extends munit.ScalaCheckSuite:

  private val window: DateRange = DateRange.endingOn(LocalDate.parse("2026-12-31"), 30)

  private val genCounts: Gen[AdherenceCounts] =
    for
      onTime <- Gen.choose(0, 60)
      late <- Gen.choose(0, 60)
      skipped <- Gen.choose(0, 60)
      missed <- Gen.choose(0, 60)
    yield AdherenceCounts(onTime, late, skipped, missed)

  private def rates(counts: AdherenceCounts): List[Stat[Ratio]] =
    List(
      MedicationStats.adherence(counts, window),
      MedicationStats.onTimeRate(counts, window),
      MedicationStats.recordedRate(counts, window)
    )

  property("every rate is a share between 0 and 1 whenever it is shown"):
    forAll(genCounts) { counts =>
      rates(counts).flatMap(_.shown).foreach { ratio =>
        assert(ratio.fraction >= 0.0 && ratio.fraction <= 1.0, s"$ratio out of range")
        assert(ratio.percent >= 0 && ratio.percent <= 100, s"$ratio displayed out of range")
      }
    }

  property("no rate is shown when its denominator is below five"):
    forAll(genCounts) { counts =>
      assertEquals(MedicationStats.adherence(counts, window).shown.isDefined, counts.resolved >= 5)
      assertEquals(MedicationStats.recordedRate(counts, window).shown.isDefined, counts.resolved >= 5)
      assertEquals(MedicationStats.onTimeRate(counts, window).shown.isDefined, counts.taken >= 5)
    }

  property("the recorded rate is never below adherence"):
    forAll(genCounts) { counts =>
      for
        adherence <- MedicationStats.adherence(counts, window).shown
        recorded <- MedicationStats.recordedRate(counts, window).shown
      do assert(recorded.fraction >= adherence.fraction)
    }

  property("one more taken dose never lowers adherence; one more missed or skipped dose never raises it"):
    forAll(genCounts) { counts =>
      def fraction(c: AdherenceCounts): Double = Adherence.adherence(c).getOrElse(0.0)
      if counts.resolved > 0 then
        assert(fraction(counts.copy(takenOnTime = counts.takenOnTime + 1)) >= fraction(counts))
        assert(fraction(counts.copy(takenLate = counts.takenLate + 1)) >= fraction(counts))
        assert(fraction(counts.copy(missed = counts.missed + 1)) <= fraction(counts))
        assert(fraction(counts.copy(skipped = counts.skipped + 1)) <= fraction(counts))
    }

  property("every occurrence lands in at most one bucket, and exactly one unless it is cancelled or a manual non-log"):
    forAll(InsightGens.genRecord) { record =>
      val bucket = MedicationStats.bucket(record.input)
      val size = bucket.resolved.resolved + bucket.unknown + bucket.open + bucket.asNeeded
      val inNoBucket =
        record.input.status == OccurrenceStatus.Cancelled ||
          (record.input.manual && record.input.status != OccurrenceStatus.Taken)
      assertEquals(size, if inNoBucket then 0 else 1)
    }

  property("the resolved buckets pooled over days equal the inherited Adherence oracle"):
    forAll(InsightGens.genRecords, InsightGens.genZone) { (records, zone) =>
      val days = MedicationStats.byDay(records, zone)
      val inputs = records.map(_.input)
      assertEquals(MedicationStats.total(days.values).resolved, Adherence.counts(inputs))
      val oracle = Adherence.byDay(inputs, zone)
      oracle.foreach { (date, expected) =>
        val day = days.get(date).map(_.total).getOrElse(DoseBuckets.zero)
        assertEquals(day.resolved, expected.counts, clue(date))
        assertEquals(day.open, expected.open, clue(date))
      }
    }

  property("the total over medications pools the bucket counts"):
    forAll(InsightGens.genRecords, InsightGens.genZone) { (records, zone) =>
      val days = MedicationStats.byDay(records, zone).values
      val pooled = MedicationStats.perMedication(days).values.foldLeft(DoseBuckets.zero)(_.combine(_))
      assertEquals(pooled, MedicationStats.total(days))
      MedicationStats.perMedication(days).foreach { (medication, buckets) =>
        val own = records.filter(_.medicationId == medication).map(_.input)
        assertEquals(buckets.resolved, Adherence.counts(own), clue(medication))
      }
    }

  property("unknown, cancelled and as-needed rows never change a rate"):
    forAll(InsightGens.genRecords, InsightGens.genRecords, InsightGens.genZone) { (records, extras, zone) =>
      val excluded = extras.filter { extra =>
        extra.input.manual ||
        extra.input.status == OccurrenceStatus.Unknown || extra.input.status == OccurrenceStatus.Cancelled
      }
      val before = MedicationStats.total(MedicationStats.byDay(records, zone).values).resolved
      val after = MedicationStats.total(MedicationStats.byDay(records ++ excluded, zone).values).resolved
      assertEquals(after, before)
    }

  property("a daylight-saving day has 23 or 25 hours and keeps every dose taken inside it"):
    forAll(InsightGens.genDstDay.flatMap(day => Gen.listOfN(12, InsightGens.genInstantOn(day._2, day._1)).map(day -> _))) {
      case ((zone, date, hours), takenAt) =>
        val length = Duration.between(LocalDays.startOf(date, zone), LocalDays.endOf(date, zone))
        assertEquals(length, Duration.ofHours(hours.toLong))
        val days = MedicationStats.byDay(takenAt.map(takenOnTimeAt), zone)
        assertEquals(days.keySet, Set(date))
        assertEquals(days(date).total.takenOnTime, 12)
    }

  property("the instants around a daylight-saving day belong to its neighbours"):
    forAll(InsightGens.genDstDay) { (zone, date, _) =>
      assertEquals(LocalDays.dateOf(LocalDays.startOf(date, zone).minusSeconds(1), zone), date.minusDays(1))
      assertEquals(LocalDays.dateOf(LocalDays.startOf(date, zone), zone), date)
      assertEquals(LocalDays.dateOf(LocalDays.endOf(date, zone).minusSeconds(1), zone), date)
      assertEquals(LocalDays.dateOf(LocalDays.endOf(date, zone), zone), date.plusDays(1))
    }

  property("every instant lies inside the bounds of its own local day"):
    forAll(InsightGens.genInstant, InsightGens.genZone) { (instant, zone) =>
      val date = LocalDays.dateOf(instant, zone)
      assert(!instant.isBefore(LocalDays.startOf(date, zone)))
      assert(instant.isBefore(LocalDays.endOf(date, zone)))
    }

  private def takenOnTimeAt(at: java.time.Instant): DoseRecord =
    val taken = AdherenceInput(
      scheduledFor = at,
      status = OccurrenceStatus.Taken,
      effectiveAt = Some(at),
      resolvedAt = None,
      dueWindowEnd = at.plusSeconds(3600),
      manual = false,
      corrected = false
    )
    DoseRecord(InsightGens.medications.head, taken)
end MedicationStatsProperties
