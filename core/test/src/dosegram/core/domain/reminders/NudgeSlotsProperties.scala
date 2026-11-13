package dosegram.core.domain.reminders

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Interval generation of the nudge plans (ADR-025): windows of every shape, midnight-crossing windows, and the days of
  * 23 and 25 hours.
  */
class NudgeSlotsProperties extends munit.ScalaCheckSuite:

  private val profile = DayProfile.Default
  private val zones = List("Europe/Kyiv", "Europe/Berlin", "America/New_York", "Australia/Lord_Howe", "UTC")
    .map(ZoneId.of)

  private val timeGen: Gen[LocalTime] =
    Gen.choose(0, 24 * 4 - 1).map(quarter => LocalTime.of(quarter / 4, (quarter % 4) * 15))
  private val everyGen: Gen[Int] = Gen.oneOf(30, 45, 60, 90, 120, 180)
  private val dateGen: Gen[LocalDate] = Gen.choose(0L, 365L * 3).map(LocalDate.of(2025, 1, 1).plusDays)
  private val zoneGen: Gen[ZoneId] = Gen.oneOf(zones)

  private def plan(from: LocalTime, until: LocalTime, every: Int): NudgePlan =
    NudgePlan.Interval(TimeRef.At(from), TimeRef.At(until), every, DaySet.Every, onlyWhenBehind = false)

  /** The wall-clock slots the rule promises: one interval after the opening, strictly before the close. */
  private def expectedLocals(date: LocalDate, from: LocalTime, until: LocalTime, every: Int): List[LocalDateTime] =
    val open = date.atTime(from)
    val close = if until.isAfter(from) then date.atTime(until) else date.plusDays(1).atTime(until)
    Iterator.iterate(open.plusMinutes(every.toLong))(_.plusMinutes(every.toLong)).takeWhile(_.isBefore(close)).toList

  property("slots are strictly increasing instants, keyed uniquely, inside the window"):
    forAll(timeGen, timeGen, everyGen, dateGen, zoneGen) { (from, until, every, date, zone) =>
      val slots = NudgeSlots.on(plan(from, until, every), profile, date, zone, seed = 1L)
      val instants = slots.map(_.at)
      assertEquals(instants, instants.sorted.distinct)
      assertEquals(slots.map(_.slotKey).distinct.size, slots.size)
      val expected = expectedLocals(date, from, until, every)
      assert(slots.forall(slot => expected.contains(slot.local)), s"$slots outside $expected")
      assert(slots.forall(_.planDate == date))
    }

  property("on a day without a transition every promised wall time is a slot"):
    forAll(timeGen, timeGen, everyGen, dateGen) { (from, until, every, date) =>
      val zone = ZoneId.of("UTC")
      val slots = NudgeSlots.on(plan(from, until, every), profile, date, zone, seed = 1L)
      assertEquals(slots.map(_.local), expectedLocals(date, from, until, every))
    }

  property("a window that crosses midnight keeps its slots on the day it opened"):
    forAll(everyGen, dateGen, zoneGen) { (every, date, zone) =>
      val slots = NudgeSlots.on(plan(LocalTime.of(20, 0), LocalTime.of(4, 0), every), profile, date, zone, 1L)
      assert(slots.nonEmpty)
      assert(slots.forall(_.planDate == date))
      assert(slots.exists(_.local.toLocalDate == date.plusDays(1)) || every > 240)
      assert(slots.filter(_.local.toLocalDate != date).forall(_.slotKey.startsWith("n")))
    }

  test("the 23-hour day of Kyiv: the slot in the gap moves forward and merges with the next one"):
    val zone = ZoneId.of("Europe/Kyiv")
    val springForward = LocalDate.of(2026, 3, 29) // 03:00 -> 04:00
    val slots = NudgeSlots.on(plan(LocalTime.of(2, 0), LocalTime.of(6, 0), 30), profile, springForward, zone, 1L)
    // Wall times 02:30 .. 05:30 = 7; 03:00 and 03:30 fall in the gap and resolve to 04:00 and 04:30, which exist.
    assertEquals(slots.size, 5)
    assertEquals(slots.map(_.at), slots.map(_.at).sorted.distinct)
    assert(slots.forall(slot => Duration.between(slots.head.at, slot.at).toMinutes % 30 == 0))

  test("the 25-hour day of Kyiv: the folded wall time is one slot, at the earlier instant"):
    val zone = ZoneId.of("Europe/Kyiv")
    val fallBack = LocalDate.of(2026, 10, 25) // 04:00 -> 03:00
    val slots = NudgeSlots.on(plan(LocalTime.of(2, 0), LocalTime.of(6, 0), 30), profile, fallBack, zone, 1L)
    assertEquals(slots.size, 7)
    val atThree = slots.find(_.local.toLocalTime == LocalTime.of(3, 0)).get
    assertEquals(atThree.at, LocalDateTime.of(fallBack, LocalTime.of(3, 0)).atZone(zone).withEarlierOffsetAtOverlap.toInstant)

  test("the owner's preset: sixteen slots from 09:30 to 17:00 on a work day, none at the weekend"):
    val zone = ZoneId.of("Europe/Kyiv")
    val monday = LocalDate.of(2026, 11, 16)
    val slots = NudgeSlots.on(PromptPresets.WaterWorkHours.plan, profile, monday, zone, 1L)
    assertEquals(slots.size, 16)
    assertEquals(slots.head.local.toLocalTime, LocalTime.of(9, 30))
    assertEquals(slots.last.local.toLocalTime, LocalTime.of(17, 0))
    assertEquals(NudgeSlots.on(PromptPresets.WaterWorkHours.plan, profile, monday.plusDays(5), zone, 1L), Nil)

  test("moving an anchor moves the preset"):
    val zone = ZoneId.of("UTC")
    val later = profile.workHours(WorkHours.Weekdays, LocalTime.of(10, 0), LocalTime.of(19, 0)).toOption.get
    val slots = NudgeSlots.on(PromptPresets.WaterWorkHours.plan, later, LocalDate.of(2026, 11, 16), zone, 1L)
    assertEquals(slots.head.local.toLocalTime, LocalTime.of(10, 30))
    assertEquals(slots.last.local.toLocalTime, LocalTime.of(18, 0))

  test("random moments are stable for a seed and day, one in each part of the window"):
    val zone = ZoneId.of("UTC")
    val date = LocalDate.of(2026, 11, 16)
    val first = NudgeSlots.on(PromptPresets.MoodRandom.plan, profile, date, zone, 42L)
    assertEquals(first, NudgeSlots.on(PromptPresets.MoodRandom.plan, profile, date, zone, 42L))
    assertEquals(first.size, 3)
    first.zipWithIndex.foreach: (slot, index) =>
      val t = slot.local.toLocalTime
      assert(!t.isBefore(LocalTime.of(10, 0).plusMinutes(index * 200L)), s"$t in part $index")
      assert(t.isBefore(LocalTime.of(10, 0).plusMinutes((index + 1) * 200L)), s"$t in part $index")

  test("weekly and monthly plans fall on their day only"):
    val zone = ZoneId.of("UTC")
    val sunday = LocalDate.of(2026, 11, 15)
    assertEquals(sunday.getDayOfWeek, DayOfWeek.SUNDAY)
    assertEquals(NudgeSlots.on(PromptPresets.WeeklyReport.plan, profile, sunday, zone, 1L).size, 1)
    assertEquals(NudgeSlots.on(PromptPresets.WeeklyReport.plan, profile, sunday.plusDays(1), zone, 1L), Nil)
    assertEquals(NudgeSlots.on(PromptPresets.MonthlyReport.plan, profile, LocalDate.of(2026, 12, 1), zone, 1L).size, 1)

  test("the plan survives its jsonb round trip"):
    PromptPresets.all.foreach: preset =>
      assertEquals(NudgePlan.fromJson(NudgePlan.toJson(preset.plan)), preset.plan)
