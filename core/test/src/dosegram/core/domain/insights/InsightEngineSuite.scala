package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.sleep.SleepGoalChange

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** VISUALS_AND_ANALYTICS sections 8.4 and 8.5 beyond IR-04: the pairing of IR-01, IR-02 and IR-06, the timing statement
  * IR-07, the own-baseline statement IR-11, and the engine's consent gate, ranking and list of rules short of data.
  */
class InsightEngineSuite extends munit.FunSuite:

  private val zone = ZoneId.of("UTC")
  private val today = LocalDate.parse("2026-11-08")
  private val window = DateRange.endingOn(today, 28)
  private val weekdays =
    Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

  private def moodDay(valence: Int): MoodDay = MoodDay(1, valence, valence, valence, 0, 0)

  private def night(minutes: Int, bedtime: Int = 23 * 60, goal: Option[Int] = Some(450), shift: Boolean = false) =
    NightFacts(minutes, bedtime, shift, goal)

  private def inputs(
      hydration: Option[Map[LocalDate, HydrationDay]] = None,
      mood: Option[Map[LocalDate, MoodDay]] = None,
      nights: Option[Map[LocalDate, NightFacts]] = None,
      doses: Option[List[AdherenceInput]] = None,
      crossDomain: Boolean = true
  ): InsightInputs =
    InsightInputs(today, window, zone, hydration, mood, nights, doses, weekdays, crossDomain)

  private def pairs(rule: InsightRule, nights: Map[LocalDate, NightFacts], mood: Map[LocalDate, MoodDay]) =
    Comparisons.pairedDays(rule, inputs(nights = Some(nights), mood = Some(mood))).get

  // ---------- IR-01 ----------

  test("IR-01 pairs a sleep day with the mood of the same day and groups by the user's own goal"):
    val nights = window.dates.zipWithIndex.map((date, i) => date -> night(if i % 2 == 0 then 480 else 400)).toMap
    val mood = window.dates.zipWithIndex.map((date, i) => date -> moodDay(if i % 2 == 0 then 4 else 3)).toMap
    Comparisons.compare(InsightRule.SleepGoalAndMood, pairs(InsightRule.SleepGoalAndMood, nights, mood), window) match
      case Comparison.Shown(_, first, second, _) =>
        assertEquals((first, second), (GroupMean(14, 4.0), GroupMean(14, 3.0)))
      case other => fail(s"expected a shown comparison, got $other")

  test("IR-01 pairs no night without a goal in force"):
    val nights = window.dates.map(_ -> night(480, goal = None)).toMap
    val mood = window.dates.map(_ -> moodDay(4)).toMap
    assertEquals(pairs(InsightRule.SleepGoalAndMood, nights, mood), Nil)

  test("the goal in force on a sleep day is the latest change on or before it"):
    val goals = List(
      SleepGoalChange(today.minusDays(10), Some(420)),
      SleepGoalChange(today.minusDays(2), Some(480))
    )
    assertEquals(NightFacts.goalOn(today.minusDays(11), goals), None)
    assertEquals(NightFacts.goalOn(today.minusDays(5), goals), Some(420))
    assertEquals(NightFacts.goalOn(today, goals), Some(480))

  // ---------- IR-02 ----------

  test("IR-02 groups by the distance from the user's own median bedtime across midnight"):
    // The median is 00:00; 23:50 and 23:30 are within 30 minutes of it across midnight, 01:00 is not.
    val bedtimes = List(23 * 60 + 50, 0, 23 * 60 + 30, 60)
    val nights = window.dates.zipWithIndex.map((date, i) => date -> night(450, bedtimes(i % 4))).toMap
    val mood = window.dates.map(_ -> moodDay(3)).toMap
    val grouped = pairs(InsightRule.BedtimeAndMood, nights, mood).map(_.inFirstGroup)
    assertEquals(grouped.count(identity), 21)
    assertEquals(grouped.count(!_), 7)

  test("IR-02 drops a night whose clock shifted (the evidence filter of section 8.5)"):
    val nights = window.dates.zipWithIndex.map((date, i) => date -> night(450, shift = i == 0)).toMap
    val mood = window.dates.map(_ -> moodDay(3)).toMap
    assertEquals(pairs(InsightRule.BedtimeAndMood, nights, mood).size, 27)

  // ---------- IR-06 ----------

  private def waterByDayKind(work: Int, free: Int): Map[LocalDate, HydrationDay] =
    window.dates.map(date => date -> HydrationDay(if weekdays(date.getDayOfWeek) then work else free, 3, None)).toMap

  test("IR-06 compares the user's own work days with the free days"):
    val paired =
      Comparisons.pairedDays(InsightRule.WorkDaysAndWater, inputs(hydration = Some(waterByDayKind(2200, 1500)))).get
    Comparisons.compare(InsightRule.WorkDaysAndWater, paired, window) match
      case Comparison.Shown(_, first, second, _) =>
        assertEquals((first, second), (GroupMean(20, 2200.0), GroupMean(8, 1500.0)))
      case other => fail(s"expected a shown comparison, got $other")

  test("IR-06 under 300 ml is withheld"):
    val paired =
      Comparisons.pairedDays(InsightRule.WorkDaysAndWater, inputs(hydration = Some(waterByDayKind(1750, 1500)))).get
    assertEquals(
      Comparisons.compare(InsightRule.WorkDaysAndWater, paired, window),
      Comparison.Withheld(InsightRule.WorkDaysAndWater, WithheldReason.BelowMinimumDifference, window)
    )

  // ---------- IR-07 ----------

  private def dose(date: LocalDate, at: LocalTime, status: OccurrenceStatus, late: Boolean = false): AdherenceInput =
    val scheduled = date.atTime(at).atZone(zone).toInstant
    AdherenceInput(
      scheduledFor = scheduled,
      status = status,
      effectiveAt = Option.when(status == OccurrenceStatus.Taken)(scheduled.plusSeconds(if late then 7200 else 60)),
      resolvedAt = Option.when(status == OccurrenceStatus.Missed || status == OccurrenceStatus.Skipped)(
        scheduled.plusSeconds(3600)
      ),
      dueWindowEnd = scheduled.plusSeconds(3600),
      manual = false,
      corrected = false
    )

  private val morning = LocalTime.of(8, 0)
  private val evening = LocalTime.of(20, 0)
  private val lastFortnight = window.dates.drop(14)

  /** 14 days with a morning and an evening dose; the evening one late or missed on the days in `lateEvenings`. */
  private def twoSlots(lateEvenings: Set[Int]): List[AdherenceInput] =
    lastFortnight.zipWithIndex.flatMap { (date, i) =>
      val eveningDose =
        if !lateEvenings(i) then dose(date, evening, OccurrenceStatus.Taken)
        else if i % 2 == 0 then dose(date, evening, OccurrenceStatus.Taken, late = true)
        else dose(date, evening, OccurrenceStatus.Missed)
      List(dose(date, morning, OccurrenceStatus.Taken), eveningDose)
    }

  test("IR-07 states the slot answered late or not at all most often against the least, as counts"):
    TimingStatements.of(twoSlots(Set(0, 1, 4, 7, 10, 13)), zone, window) match
      case TimingStatement.Shown(highest, lowest, _) =>
        assertEquals(highest, SlotShare(DoseSlot.Evening, 6, 14))
        assertEquals(lowest, SlotShare(DoseSlot.Morning, 0, 14))
      case other => fail(s"expected a shown statement, got $other")

  test("IR-07 needs 10 resolved doses in each of two slots"):
    val nineDays = lastFortnight.takeRight(9).toSet
    val nine = twoSlots(Set(0, 1, 4, 7)).filter(input => nineDays(LocalDays.dateOf(input.scheduledFor, zone)))
    assertEquals(TimingStatements.of(nine, zone, window), TimingStatement.TooFewDoses(9, 10, window))

  test("IR-07 below 15 percentage points is withheld"):
    assertEquals(
      TimingStatements.of(twoSlots(Set(3)), zone, window),
      TimingStatement.Withheld(WithheldReason.BelowMinimumDifference, window)
    )

  test("IR-07 is withheld when the order of the slots holds in the earlier half only"):
    assertEquals(
      TimingStatements.of(twoSlots(Set(0, 1, 2, 3, 4)), zone, window),
      TimingStatement.Withheld(WithheldReason.UnstableBetweenHalves, window)
    )

  test("IR-07 leaves a skip out of the late share: a skip is an answer"):
    val skipped =
      lastFortnight.flatMap(date =>
        List(dose(date, morning, OccurrenceStatus.Taken), dose(date, evening, OccurrenceStatus.Skipped))
      )
    assertEquals(
      TimingStatements.of(skipped, zone, window),
      TimingStatement.Withheld(WithheldReason.BelowMinimumDifference, window)
    )

  test("the slots of IR-07 by the local clock time"):
    assertEquals(
      List(0, 4, 10, 11, 15, 16, 20, 21, 23).map(DoseSlot.of),
      List(
        DoseSlot.Night,
        DoseSlot.Morning,
        DoseSlot.Morning,
        DoseSlot.Midday,
        DoseSlot.Midday,
        DoseSlot.Evening,
        DoseSlot.Evening,
        DoseSlot.Night,
        DoseSlot.Night
      )
    )

  // ---------- IR-11 ----------

  private def fiveWeeks(values: Int => Option[Double]): Map[LocalDate, Double] =
    (0 until 35).flatMap(back => values(back).map(today.minusDays(back.toLong) -> _)).toMap

  private val currentWeek = DateRange.endingOn(today, 7)

  test("IR-11 compares the last 7 days with the mean of the previous weeks that meet the weekly gate"):
    BaselineStatements.of(BaselineMetric.SleepMinutes, fiveWeeks(back => Some(if back < 7 then 430 else 380)), today) match
      case BaselineStatement.Shown(_, current, baseline, weeks, _) =>
        assertEqualsDouble(current, 430.0, 1e-9)
        assertEqualsDouble(baseline, 380.0, 1e-9)
        assertEquals(weeks, 4)
      case other => fail(s"expected a shown statement, got $other")

  test("IR-11 needs two earlier weeks with three recorded days each"):
    val values = fiveWeeks(back => Option.when(back < 10)(400.0))
    assertEquals(
      BaselineStatements.of(BaselineMetric.SleepMinutes, values, today),
      BaselineStatement.TooFewWeeks(BaselineMetric.SleepMinutes, 1, 2, currentWeek)
    )

  test("IR-11 needs three recorded days in the current week"):
    val values = fiveWeeks(back => Option.when(back >= 5)(400.0))
    assertEquals(
      BaselineStatements.of(BaselineMetric.WaterMillilitres, values, today),
      BaselineStatement.TooFewDays(BaselineMetric.WaterMillilitres, 2, 3, currentWeek)
    )

  test("IR-11 under the about-the-same limit is not stated"):
    val values = fiveWeeks(back => Some(if back < 7 then 3.5 else 3.4))
    assertEquals(
      BaselineStatements.of(BaselineMetric.MoodLevel, values, today),
      BaselineStatement.AboutTheSame(BaselineMetric.MoodLevel, currentWeek)
    )

  // ---------- the engine ----------

  private val waterAndMood: InsightInputs =
    val water = window.dates.zipWithIndex
      .map((date, i) => date -> HydrationDay(if i % 2 == 0 then 2500 else 900, 2, Some(2000)))
      .toMap
    val mood = window.dates.zipWithIndex.map((date, i) => date -> moodDay(if i % 2 == 0 then 5 else 3)).toMap
    inputs(hydration = Some(water), mood = Some(mood))

  private def isWaterRule(insight: Insight): Boolean =
    insight match
      case Insight.Pattern(comparison, _) => comparison.rule == InsightRule.WaterGoalAndMood
      case _                              => false

  test("without the cross-domain consent no rule that pairs two areas runs (VA-IN-01)"):
    val list = InsightEngine.evaluate(waterAndMood.copy(crossDomain = false))
    assert(list.shown.forall(!_.isInstanceOf[Insight.Pattern]), clue(list))
    assert(list.gaps.forall(!_.isInstanceOf[InsightGap.Pattern]), clue(list))

  test("with the consent a passing comparison is listed"):
    assert(InsightEngine.evaluate(waterAndMood).shown.exists(isWaterRule))

  test("a source without consent is read by no rule"):
    val list = InsightEngine.evaluate(waterAndMood.copy(mood = None))
    assert(
      list.shown.forall {
        case Insight.Pattern(comparison, _) => comparison.rule.outcome != InsightVariable.DailyMoodMean
        case _                              => true
      },
      clue(list)
    )
    assert(!list.shown.exists(isWaterRule))

  test("the list is ranked by standardised size, at most five, ties by rule number"):
    val list = InsightEngine.evaluate(waterAndMood)
    assert(list.shown.size <= InsightEngine.MaximumShown)
    val order = list.shown.map(insight => (-insight.size, insight.number))
    assertEquals(order, order.sorted)
    assertEquals(InsightEngine.forReport(list), list.shown.headOption)

  test("a rule with at least half of its paired days is listed with the days it still needs"):
    val few = window.dates.takeRight(9)
    val list = InsightEngine.evaluate(
      inputs(
        hydration = Some(few.map(_ -> HydrationDay(2500, 1, Some(2000))).toMap),
        mood = Some(few.map(_ -> moodDay(4)).toMap)
      )
    )
    assert(list.gaps.contains(InsightGap.Pattern(InsightRule.WaterGoalAndMood, 5, inSmallerGroup = false)), clue(list))
    assertEquals(list.shown, Nil)

  test("a rule far from its data is not listed"):
    val few = window.dates.takeRight(3)
    val list = InsightEngine.evaluate(
      inputs(
        hydration = Some(few.map(_ -> HydrationDay(2500, 1, Some(2000))).toMap),
        mood = Some(few.map(_ -> moodDay(4)).toMap)
      )
    )
    assert(!list.gaps.exists {
      case InsightGap.Pattern(rule, _, _) => rule == InsightRule.WaterGoalAndMood
      case _                              => false
    })

  test("the timing statement needs no cross-domain consent (IR-07 is single-domain)"):
    val list = InsightEngine.evaluate(inputs(doses = Some(twoSlots(Set(0, 1, 4, 7, 10, 13))), crossDomain = false))
    assert(list.shown.exists(_.isInstanceOf[Insight.Timing]), clue(list))
end InsightEngineSuite
