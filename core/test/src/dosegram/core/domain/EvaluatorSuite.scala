package dosegram.core.domain

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EvaluatorSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private def hm(value: String): HhMm = HhMm.unsafe(value)
  private val everyDay = Weekday.values.toList

  test("slot_key scheme: t0900 for fixed wall times (DESIGN.md section 7.1)"):
    assertEquals(Evaluator.fixedSlotKey(hm("09:00")), "t0900")
    assertEquals(Evaluator.fixedSlotKey(hm("00:05")), "t0005")
    assertEquals(Evaluator.fixedSlotKey(hm("23:45")), "t2345")

  test("the materialisation horizon is 48 hours (DESIGN.md section 7.2)"):
    assertEquals(Evaluator.MaterialisationHorizon, Duration.ofHours(48))

  test("FixedTimes with multiple slot groups produces per-weekday wall times"):
    val revision = ScheduleRevision(
      Rule.FixedTimes(
        List(
          SlotGroup(List(Weekday.Mon, Weekday.Tue, Weekday.Wed, Weekday.Thu, Weekday.Fri), List(hm("08:00"))),
          SlotGroup(List(Weekday.Sat, Weekday.Sun), List(hm("10:00")))
        )
      ),
      kyiv
    )
    val occurrences =
      Evaluator.occurrences(revision, Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"))
    assertEquals(
      occurrences.map(c => (c.localDate, c.slotKey)),
      List(
        (LocalDate.of(2026, 6, 15), "t0800"), // Monday
        (LocalDate.of(2026, 6, 16), "t0800"),
        (LocalDate.of(2026, 6, 17), "t0800"),
        (LocalDate.of(2026, 6, 18), "t0800"),
        (LocalDate.of(2026, 6, 19), "t0800"),
        (LocalDate.of(2026, 6, 20), "t1000"), // Saturday
        (LocalDate.of(2026, 6, 21), "t1000")
      )
    )

  test("the window is half-open: a slot exactly at `from` is included, one exactly at `to` is excluded"):
    val revision = ScheduleRevision(Rule.FixedTimes(List(SlotGroup(everyDay, List(hm("08:00"))))), kyiv)
    // 08:00 EEST = 05:00Z in June 2026.
    val from = Instant.parse("2026-06-15T05:00:00Z")
    val to = Instant.parse("2026-06-17T05:00:00Z")
    val occurrences = Evaluator.occurrences(revision, from, to)
    assertEquals(
      occurrences.map(_.localDate),
      List(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16))
    )

  test("Kyiv spring-forward: the 03:30 slot on 2026-03-29 fires at 04:30 local with dst_kind gap"):
    val revision = ScheduleRevision(Rule.FixedTimes(List(SlotGroup(everyDay, List(hm("03:30"))))), kyiv)
    val occurrences =
      Evaluator.occurrences(revision, Instant.parse("2026-03-28T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"))
    val transitionDay = occurrences.find(_.localDate == LocalDate.of(2026, 3, 29)).get
    assertEquals(transitionDay.dstKind, DstKind.Gap)
    assertEquals(transitionDay.scheduledFor.atZone(kyiv).toLocalTime, LocalTime.of(4, 30))
    assertEquals(transitionDay.scheduledFor, Instant.parse("2026-03-29T01:30:00Z")) // 04:30 EEST

  test("Kyiv fall-back: the 03:30 slot on 2026-10-25 picks the earlier instant with dst_kind fold"):
    val revision = ScheduleRevision(Rule.FixedTimes(List(SlotGroup(everyDay, List(hm("03:30"))))), kyiv)
    val occurrences =
      Evaluator.occurrences(revision, Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-27T00:00:00Z"))
    val transitionDay = occurrences.find(_.localDate == LocalDate.of(2026, 10, 25)).get
    assertEquals(transitionDay.dstKind, DstKind.Fold)
    assertEquals(transitionDay.scheduledFor, Instant.parse("2026-10-25T00:30:00Z")) // 03:30 EEST, the earlier 03:30

  test("AsNeeded produces no scheduled occurrences (manual-log driven)"):
    val revision = ScheduleRevision(Rule.AsNeeded(maxPerDay = 3, minGapMinutes = 60), kyiv)
    assertEquals(
      Evaluator.occurrences(revision, Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z")),
      Nil
    )

  test("G9.8: every other day falls on the anchor and every second local date, before and after it"):
    val revision = ScheduleRevision(Rule.EveryNDays(2, List(hm("09:00")), LocalDate.of(2026, 6, 17)), kyiv)
    val dates = Evaluator
      .occurrences(revision, Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"))
      .map(_.localDate)
    assertEquals(dates, List(15, 17, 19, 21).map(LocalDate.of(2026, 6, _)))

  test("G9.8: a cycle of 3 days on and 2 off repeats from its anchor and keeps the fixed-time slot key"):
    val revision = ScheduleRevision(Rule.Cycle(3, 2, List(hm("08:00")), LocalDate.of(2026, 6, 1)), kyiv)
    val candidates =
      Evaluator.occurrences(revision, Instant.parse("2026-05-31T21:00:00Z"), Instant.parse("2026-06-10T21:00:00Z"))
    assertEquals(candidates.map(_.localDate.getDayOfMonth), List(1, 2, 3, 6, 7, 8))
    assert(candidates.forall(_.slotKey == "t0800"))

  test("G9.8: an every-other-day 03:30 slot on the Kyiv spring-forward date is reminded once, at 04:30"):
    val springForward = LocalDate.of(2026, 3, 29)
    val revision = ScheduleRevision(Rule.EveryNDays(2, List(hm("03:30")), springForward), kyiv)
    val day = Evaluator.occurrences(revision, Instant.parse("2026-03-28T22:00:00Z"), Instant.parse("2026-03-29T21:00:00Z"))
    assertEquals(day.size, 1)
    assertEquals(day.head.dstKind, DstKind.Gap)
    assertEquals(day.head.scheduledFor.atZone(kyiv).toLocalTime, java.time.LocalTime.of(4, 30))

  test("Paused and the inert M7 kinds produce no occurrences"):
    val rules = List(
      Rule.Paused,
      Rule.EveryNWeeks(2, everyDay, List(hm("09:00"))),
      Rule.IntervalFixedStart(hm("08:00"), 240, 4, everyDay),
      Rule.Chain(ChainAnchor.EachTaken, 360, 3, hm("07:00"), hm("23:00"), hm("08:00")),
      Rule.taper(List(TaperPhase(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), Rule.AsNeeded(1, 0))))
    )
    rules.foreach { rule =>
      assertEquals(
        Evaluator.occurrences(ScheduleRevision(rule, kyiv), Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z")),
        Nil,
        clue(rule)
      )
    }

  test("the validity window clips candidates to [effectiveFrom, effectiveUntil) over local dates"):
    val revision = ScheduleRevision(
      Rule.FixedTimes(List(SlotGroup(everyDay, List(hm("09:00"))))),
      kyiv,
      effectiveFrom = LocalDate.of(2026, 6, 17),
      effectiveUntil = Some(LocalDate.of(2026, 6, 20))
    )
    val occurrences =
      Evaluator.occurrences(revision, Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"))
    assertEquals(
      occurrences.map(_.localDate),
      List(LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19))
    )

  test("overlapping slot groups dedupe on (local_date, slot_key)"):
    val revision = ScheduleRevision(
      Rule.FixedTimes(
        List(
          SlotGroup(everyDay, List(hm("08:00"))),
          SlotGroup(List(Weekday.Mon), List(hm("08:00"), hm("20:00")))
        )
      ),
      kyiv
    )
    val occurrences =
      Evaluator.occurrences(revision, Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-16T00:00:00Z"))
    assertEquals(occurrences.map(_.slotKey), List("t0800", "t2000"))
    assertEquals(occurrences.map(_.localDate), List(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15)))
