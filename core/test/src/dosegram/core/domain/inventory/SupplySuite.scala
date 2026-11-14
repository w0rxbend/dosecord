package dosegram.core.domain.inventory

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.DstGolden
import dosegram.core.domain.Evaluator
import dosegram.core.domain.Rule
import dosegram.core.domain.ScheduleGens
import dosegram.core.domain.ScheduleRevision
import dosegram.core.domain.SlotGroup

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Days of supply by a forward walk of the schedule (PRODUCT_SPEC R-REG-13, AC-REG-7; VISUALS VA-IMG-15b). */
class SupplySuite extends munit.ScalaCheckSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val nine = HhMm.unsafe("09:00")

  private def everyOtherDay(anchor: LocalDate) = ScheduleRevision(Rule.EveryNDays(2, List(nine), anchor), kyiv)

  test("AC-REG-7: every other day, 5 on hand, 1 per intake: 10 days while today's intake is open"):
    val today = LocalDate.of(2026, 11, 16)
    val beforeNine = Instant.parse("2026-11-16T05:00:00Z") // 07:00 in Kyiv, today's 09:00 is still ahead
    val supply = Supply.of(5, 1, List(everyOtherDay(today)), openEarlierToday = 0, beforeNine, kyiv)
    assertEquals(supply.daysOfSupply, Some(10))
    assertEquals(supply.lastCovered, Some(today.plusDays(8)))
    // After 09:00 the open dose of today counts as today's intake.
    val afterNine = Instant.parse("2026-11-16T09:00:00Z")
    assertEquals(Supply.of(5, 1, List(everyOtherDay(today)), 1, afterNine, kyiv).daysOfSupply, Some(10))

  test("AC-REG-7: with the next intake tomorrow the same count covers 11 days, never 5"):
    val tomorrow = LocalDate.of(2026, 11, 17)
    val now = Instant.parse("2026-11-16T10:00:00Z")
    assertEquals(Supply.of(5, 1, List(everyOtherDay(tomorrow)), 0, now, kyiv).daysOfSupply, Some(11))

  test("two intakes a day of 0.5 each: 3 units cover three days; a fraction left over covers nothing more"):
    val twice = ScheduleRevision(
      Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(nine, HhMm.unsafe("21:00"))))),
      kyiv
    )
    val now = Instant.parse("2026-11-16T05:00:00Z")
    assertEquals(Supply.of(BigDecimal("3.4"), BigDecimal("0.5"), List(twice), 0, now, kyiv).daysOfSupply, Some(3))

  test("as needed, and a schedule that covers nothing, have no days figure; a count of 0 has 0 days"):
    val now = Instant.parse("2026-11-16T05:00:00Z")
    val asNeeded = ScheduleRevision(Rule.AsNeeded(3, 240), kyiv)
    assertEquals(Supply.of(20, 1, List(asNeeded), 0, now, kyiv), Supply(20, None, None))
    assertEquals(Supply.of(20, 1, Nil, 0, now, kyiv).daysOfSupply, None)
    assertEquals(Supply.of(0, 1, List(everyOtherDay(LocalDate.of(2026, 11, 16))), 0, now, kyiv).daysOfSupply, Some(0))

  test("a pause with a resume date: nothing is taken before the resume date"):
    val now = Instant.parse("2026-11-16T05:00:00Z")
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(nine))))
    val resumed = ScheduleRevision(daily, kyiv, effectiveFrom = LocalDate.of(2026, 11, 26))
    // 2 doses: the 26th and the 27th; the 28th is the first one not covered.
    assertEquals(Supply.of(2, 1, List(resumed), 0, now, kyiv).daysOfSupply, Some(12))

  test("the threshold: days need a days figure, units compare the count"):
    val now = Instant.parse("2026-11-16T05:00:00Z")
    def inventory(threshold: RefillThreshold, onHand: BigDecimal) =
      Inventory(UUID.randomUUID(), UUID.randomUUID(), onHand, 1, Some(threshold), None, false, false, None, 1, now)
    assert(Supply.belowThreshold(inventory(RefillThreshold.Days(7), 3), Supply(3, Some(6), None)))
    assert(!Supply.belowThreshold(inventory(RefillThreshold.Days(7), 30), Supply(30, Some(30), None)))
    assert(!Supply.belowThreshold(inventory(RefillThreshold.Days(7), 3), Supply(3, None, None)))
    assert(Supply.belowThreshold(inventory(RefillThreshold.Units(5), 5), Supply(5, None, None)))

  private val genRevision: Gen[ScheduleRevision] =
    for
      zone <- Gen.oneOf(DstGolden.Zones)
      rule <- Gen.oneOf(ScheduleGens.genFixedTimes, ScheduleGens.genEveryNDays, ScheduleGens.genCycle)
    yield ScheduleRevision(rule, zone)

  property("VA-IMG-15b: the last covered day equals the day found by simulating the occurrences day by day"):
    forAll(genRevision, Gen.choose(0, 60), Gen.choose(1735689600L, 1893456000L)) { (revision, onHand, start) =>
      val now = Instant.ofEpochSecond(start)
      val zone = revision.zone
      val supply = Supply.of(onHand, 1, List(revision), 0, now, zone)
      // Simulation: materialise one day at a time, as the materialiser's rolling window does, and spend one unit per
      // reminder until the count is gone.
      val days = Iterator.iterate(now)(_.plus(Duration.ofDays(1))).take(Supply.HorizonDays)
      val taken = days.flatMap(from => Evaluator.occurrences(revision, from, from.plus(Duration.ofDays(1)))).toList
      val expectedLast = if onHand == 0 then None else taken.lift(onHand - 1).map(_.scheduledFor.atZone(zone).toLocalDate)
      val expectedFirstOut = taken.lift(onHand).map(_.scheduledFor.atZone(zone).toLocalDate)
      assertEquals(supply.lastCovered, expectedFirstOut.flatMap(_ => expectedLast))
      assertEquals(
        supply.daysOfSupply,
        expectedFirstOut.map(d => java.time.temporal.ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate, d).toInt)
      )
    }
end SupplySuite
