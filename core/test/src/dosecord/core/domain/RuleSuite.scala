package dosecord.core.domain

import java.time.LocalDate

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday

/** ROADMAP M1.1: every `Rule` kind of DESIGN.md section 7.1 is declared; only `FixedTimes` and `AsNeeded` pass
  * validation until M7; invalid taper phases are rejected.
  */
class RuleSuite extends munit.FunSuite:

  private val nine = HhMm.unsafe("09:00")
  private val weekdays = List(Weekday.Mon, Weekday.Wed, Weekday.Fri)
  private val validFixed = Rule.FixedTimes(List(SlotGroup(weekdays, List(nine))))
  private val validAsNeeded = Rule.AsNeeded(maxPerDay = 4, minGapMinutes = 120)

  test("every kind of DESIGN.md section 7.1 is declared"):
    // One value per kind; if a kind is missing this list does not cover `Rule.values`…
    // …enforced structurally by the exhaustive matches in `object Rule` (see the M1.1 negative compile test).
    val kinds: List[Rule] = List(
      validFixed,
      Rule.EveryNDays(2, List(nine)),
      Rule.EveryNWeeks(2, weekdays, List(nine)),
      Rule.Cycle(21, 7, List(nine), LocalDate.of(2026, 9, 1)),
      Rule.IntervalFixedStart(nine, 240, 4, weekdays),
      Rule.Chain(ChainAnchor.EachTaken, 360, 4, nine, HhMm.unsafe("23:00"), HhMm.unsafe("08:00")),
      Rule.taper(List(TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), validAsNeeded))),
      validAsNeeded,
      Rule.Paused
    )
    assertEquals(kinds.size, 9)

  test("valid FixedTimes and AsNeeded pass validation"):
    assertEquals(Rule.validate(validFixed), Nil)
    assertEquals(Rule.validate(validAsNeeded), Nil)
    assert(Rule.isSupportedInMvp(validFixed))
    assert(Rule.isSupportedInMvp(validAsNeeded))

  test("FixedTimes validation rejects empty groups, empty or duplicate days and times"):
    assert(Rule.validate(Rule.FixedTimes(Nil)).nonEmpty)
    assert(Rule.validate(Rule.FixedTimes(List(SlotGroup(Nil, List(nine))))).nonEmpty)
    assert(Rule.validate(Rule.FixedTimes(List(SlotGroup(weekdays, Nil)))).nonEmpty)
    assert(Rule.validate(Rule.FixedTimes(List(SlotGroup(List(Weekday.Mon, Weekday.Mon), List(nine))))).nonEmpty)
    assert(Rule.validate(Rule.FixedTimes(List(SlotGroup(weekdays, List(nine, nine))))).nonEmpty)

  test("AsNeeded validation rejects a non-positive maxPerDay and a negative minGapMinutes"):
    assert(Rule.validate(Rule.AsNeeded(0, 60)).nonEmpty)
    assert(Rule.validate(Rule.AsNeeded(4, -1)).nonEmpty)

  test("M7 kinds are declared, inert (validate to Nil) and not accepted by validation"):
    val inert: List[Rule] = List(
      Rule.EveryNDays(2, List(nine)),
      Rule.EveryNWeeks(2, weekdays, List(nine)),
      Rule.Cycle(21, 7, List(nine), LocalDate.of(2026, 9, 1)),
      Rule.IntervalFixedStart(nine, 240, 4, weekdays),
      Rule.Chain(ChainAnchor.FirstTaken, 360, 4, nine, HhMm.unsafe("23:00"), HhMm.unsafe("08:00")),
      Rule.taper(List(TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), validFixed))),
      Rule.Paused
    )
    inert.foreach { rule =>
      assertEquals(Rule.validate(rule), Nil, s"unexpected validation errors for $rule")
      assert(!Rule.isSupportedInMvp(rule), s"$rule must not be accepted before M7")
    }

  test("taper rejects non-contiguous phases"):
    val phaseA = TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), validFixed)
    val gap = TaperPhase(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 17), validAsNeeded)
    val contiguous = TaperPhase(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 15), validAsNeeded)
    intercept[IllegalArgumentException](Rule.taper(List(phaseA, gap)))
    assertEquals(Rule.taper(List(phaseA, contiguous)).phases.size, 2)

  test("taper rejects overlapping and unsorted phases"):
    val phaseA = TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), validFixed)
    val overlap = TaperPhase(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 12), validAsNeeded)
    val contiguous = TaperPhase(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 15), validAsNeeded)
    intercept[IllegalArgumentException](Rule.taper(List(phaseA, overlap)))
    intercept[IllegalArgumentException](Rule.taper(List(contiguous, phaseA)))
    intercept[IllegalArgumentException](Rule.taper(Nil))

  test("taper phase rejects an inverted range and a Taper or Chain inner rule"):
    intercept[IllegalArgumentException](
      TaperPhase(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 1), validFixed)
    )
    val chain = Rule.Chain(ChainAnchor.EachTaken, 360, 4, nine, HhMm.unsafe("23:00"), HhMm.unsafe("08:00"))
    intercept[IllegalArgumentException](TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), chain))
    val innerTaper = Rule.taper(List(TaperPhase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), validFixed)))
    intercept[IllegalArgumentException](
      TaperPhase(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), innerTaper)
    )
end RuleSuite
