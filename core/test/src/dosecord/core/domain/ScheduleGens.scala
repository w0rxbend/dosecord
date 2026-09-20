package dosecord.core.domain

import java.time.LocalDate
import java.time.LocalTime

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday

/** ScalaCheck generators for the M1.1 schedule domain types. Values are generated inside the constructors' bounds so
  * the round-trip properties exercise shape; the rejection paths are covered by RuleSuite and ReminderPolicySuite.
  */
object ScheduleGens:

  val genHhMm: Gen[HhMm] =
    for
      h <- Gen.choose(0, 23)
      m <- Gen.choose(0, 59)
    yield HhMm.unsafe(f"$h%02d:$m%02d")

  val genWeekday: Gen[Weekday] = Gen.oneOf(Weekday.values.toList)
  val genLocalDate: Gen[LocalDate] = Gen.choose(0L, 30000L).map(LocalDate.ofEpochDay)
  val genLocalTime: Gen[LocalTime] =
    for
      h <- Gen.choose(0, 23)
      m <- Gen.choose(0, 59)
    yield LocalTime.of(h, m)

  val genDays: Gen[List[Weekday]] = Gen.choose(1, 7).flatMap(n => Gen.pick(n, Weekday.values).map(_.toList))
  val genTimes: Gen[List[HhMm]] = Gen.choose(1, 4).flatMap(Gen.listOfN(_, genHhMm)).map(_.distinct)

  val genSlotGroup: Gen[SlotGroup] = Gen.zip(genDays, genTimes).map(SlotGroup.apply)
  given Arbitrary[SlotGroup] = Arbitrary(genSlotGroup)

  val genFixedTimes: Gen[Rule.FixedTimes] =
    Gen.choose(1, 3).flatMap(Gen.listOfN(_, genSlotGroup)).map(Rule.FixedTimes.apply)
  val genAsNeeded: Gen[Rule.AsNeeded] = Gen.zip(Gen.choose(1, 12), Gen.choose(0, 720)).map(Rule.AsNeeded.apply)
  val genEveryNDays: Gen[Rule.EveryNDays] = Gen.zip(Gen.choose(1, 90), genTimes).map(Rule.EveryNDays.apply)
  val genEveryNWeeks: Gen[Rule.EveryNWeeks] =
    Gen.zip(Gen.choose(1, 12), genDays, genTimes).map(Rule.EveryNWeeks.apply)
  val genCycle: Gen[Rule.Cycle] =
    Gen.zip(Gen.choose(1, 30), Gen.choose(1, 30), genTimes, genLocalDate).map(Rule.Cycle.apply)
  val genIntervalFixedStart: Gen[Rule.IntervalFixedStart] =
    Gen.zip(genHhMm, Gen.choose(15, 720), Gen.choose(1, 12), genDays).map(Rule.IntervalFixedStart.apply)
  val genChainAnchor: Gen[ChainAnchor] = Gen.oneOf(ChainAnchor.values.toList)
  given Arbitrary[ChainAnchor] = Arbitrary(genChainAnchor)
  val genChain: Gen[Rule.Chain] =
    Gen
      .zip(genChainAnchor, Gen.choose(15, 720), Gen.choose(1, 8), genHhMm, genHhMm, genHhMm)
      .map(Rule.Chain.apply)

  val genTaperPhase: Gen[TaperPhase] =
    for
      from <- genLocalDate
      days <- Gen.choose(1, 30)
      inner <- Gen.oneOf(genFixedTimes, genAsNeeded)
    yield TaperPhase(from, from.plusDays(days), inner)
  given Arbitrary[TaperPhase] = Arbitrary(genTaperPhase)

  /** A valid taper: sorted, contiguous, non-overlapping phases with `FixedTimes`/`AsNeeded` inner rules.
    */
  val genTaper: Gen[Rule.Taper] =
    for
      start <- genLocalDate
      count <- Gen.choose(1, 4)
      lengths <- Gen.listOfN(count, Gen.choose(1, 14))
      inner <- Gen.oneOf(genFixedTimes, genAsNeeded)
    yield
      val phases = lengths
        .foldLeft((start, List.empty[TaperPhase])) { case ((cursor, acc), length) =>
          (cursor.plusDays(length), acc :+ TaperPhase(cursor, cursor.plusDays(length), inner))
        }
        ._2
      Rule.taper(phases)

  given Arbitrary[Rule] = Arbitrary(
    Gen.oneOf(
      genFixedTimes,
      genAsNeeded,
      genEveryNDays,
      genEveryNWeeks,
      genCycle,
      genIntervalFixedStart,
      genChain,
      genTaper,
      Gen.const(Rule.Paused)
    )
  )

  given Arbitrary[QuietHoursMode] = Arbitrary(Gen.oneOf(QuietHoursMode.values.toList))

  given Arbitrary[ReminderPolicy] = Arbitrary(
    for
      offset <- Gen.choose(-1440, 1440)
      repeatEvery <- Gen.choose(1, 1440)
      maxReminders <- Gen.choose(1, 10)
      missAfter <- Gen.choose(1, 10080)
      onTimeGrace <- Gen.choose(1, 10080)
      snoozes <- Gen.choose(1, 4).flatMap(Gen.listOfN(_, Gen.choose(1, 1440))).map(_.distinct.sorted)
      maxSnoozes <- Gen.choose(0, 10)
      missAfterSnooze <- Gen.choose(1, 1440)
      maxLate <- Gen.choose(1, 10080)
      lateLogWindow <- Gen.choose(1, 43200)
      undoWindow <- Gen.choose(1, 60)
      mode <- Gen.oneOf(QuietHoursMode.values.toList)
      discreet <- Gen.oneOf(true, false)
    yield ReminderPolicy(
      offset,
      repeatEvery,
      maxReminders,
      missAfter,
      onTimeGrace,
      snoozes,
      maxSnoozes,
      missAfterSnooze,
      maxLate,
      lateLogWindow,
      undoWindow,
      mode,
      discreet
    )
  )

  given Arbitrary[QuietHours] = Arbitrary(Gen.zip(genHhMm, genHhMm).map(QuietHours.apply))
end ScheduleGens
