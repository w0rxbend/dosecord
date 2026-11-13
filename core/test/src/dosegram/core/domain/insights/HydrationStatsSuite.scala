package dosegram.core.domain.insights

import dosegram.core.domain.hydration.GoalChange
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant
import java.time.ZoneId

/** VISUALS_AND_ANALYTICS section 7.4 and ROADMAP G5.3c, G5.6: daily total, goal in force, completion, goal days and the
  * period figures with their minimum samples.
  */
class HydrationStatsSuite extends munit.ScalaCheckSuite:

  import TrackingGens.*

  private val day = windowEnd

  test("2300 ml against a goal of 2000 ml is 115 percent, a full vessel and a goal day (AC-HYD-6)"):
    val entries = List(waterOn(day, 1500), waterOn(day, 800))
    val goals = List(GoalChange(day.minusDays(10), Some(2000)))
    val today = HydrationStats.byDay(entries, goals)(day)
    assertEquals(today, HydrationDay(totalMl = 2300, entries = 2, goalMl = Some(2000)))
    val progress = today.progress.get
    assertEquals(progress.percent, 115)
    assertEqualsDouble(progress.completion, 1.0, 0.0)
    assert(today.isGoalDay)

  test("progress toward a goal is floored"):
    assertEquals(GoalProgress(totalMl = 1999, goalMl = 2000).percent, 99)
    assertEquals(GoalProgress(totalMl = 250, goalMl = 2000).percent, 12)

  test("a day without a goal has a total and no progress"):
    val today = HydrationStats.byDay(List(waterOn(day, 300)), Nil)(day)
    assertEquals(today.progress, None)
    assert(!today.isGoalDay)

  test("a day without an entry is no record, not zero"):
    assertEquals(HydrationStats.byDay(List(waterOn(day, 300)), Nil).get(day.minusDays(1)), None)

  test("a voided entry leaves the total"):
    val voided = waterOn(day, 500).copy(voidedAt = Some(Instant.parse("2026-06-30T13:00:00Z")))
    assertEquals(HydrationStats.byDay(List(waterOn(day, 300), voided), Nil)(day).totalMl, 300)

  test("a goal change applies from its day on; past days keep their goal; a removed goal is no goal"):
    val goals = List(
      GoalChange(day.minusDays(5), Some(1500)),
      GoalChange(day.minusDays(2), Some(2500)),
      GoalChange(day, None)
    )
    assertEquals(HydrationStats.goalOn(day.minusDays(6), goals), None)
    assertEquals(HydrationStats.goalOn(day.minusDays(5), goals), Some(1500))
    assertEquals(HydrationStats.goalOn(day.minusDays(3), goals), Some(1500))
    assertEquals(HydrationStats.goalOn(day.minusDays(2), goals), Some(2500))
    assertEquals(HydrationStats.goalOn(day, goals), None)

  test("an entry at 00:10 belongs to the new local day, and keeps its day after a zone change (AC-HYD-5)"):
    val kyiv = ZoneId.of("Europe/Kyiv")
    val lateEvening = water(Instant.parse("2026-06-29T20:50:00Z"), kyiv, 200)
    val afterMidnight = water(Instant.parse("2026-06-29T21:10:00Z"), kyiv, 300)
    val days = HydrationStats.byDay(List(lateEvening, afterMidnight), Nil)
    assertEquals(days(day.minusDays(1)).totalMl, 200)
    assertEquals(days(day).totalMl, 300)
    assertEquals(lateEvening.localDate, day.minusDays(1))

  test("a week with two recorded days has counts and coverage but no mean"):
    val entries = List(waterOn(day, 1000), waterOn(day.minusDays(1), 3000))
    val period = HydrationStats.period(HydrationStats.byDay(entries, Nil), week)
    assertEquals(period.recordedDays, 2)
    assertEquals(period.totalMl, 4000)
    assertEquals(period.meanMl, Stat.Insufficient(2, 3, week))
    assertEquals(period.goalDays, None)

  test("goal days are counted out of recorded days with a goal"):
    val goals = List(GoalChange(week.start, Some(2000)))
    val entries = List(waterOn(day, 2000), waterOn(day.minusDays(1), 1999), waterOn(day.minusDays(2), 2600))
    val period = HydrationStats.period(HydrationStats.byDay(entries, goals), week)
    assertEquals(period.goalDays.flatMap(_.shown), Some(Ratio(2, 3)))
    assertEquals(period.meanMl.shown, Some(2200))

  test("a period only counts days inside its window"):
    val entries = List(waterOn(week.start.minusDays(1), 900), waterOn(week.start, 100))
    assertEquals(HydrationStats.period(HydrationStats.byDay(entries, Nil), week).totalMl, 100)

  property("the day total is the sum of the live entries of the day"):
    forAll(genWaters, genGoals) { (entries, goals) =>
      val days = HydrationStats.byDay(entries, goals)
      assertEquals(days.keySet, entries.map(_.localDate).toSet)
      days.foreach { (date, summary) =>
        val own = entries.filter(_.localDate == date)
        assertEquals(summary.totalMl, own.map(_.amount.millilitres).sum, clue(date))
        assertEquals(summary.entries, own.size, clue(date))
        assertEquals(summary.goalMl, HydrationStats.goalOn(date, goals), clue(date))
      }
    }

  property("completion stays within 0 and 1, and one more entry never lowers the total or the completion"):
    forAll(genWaters, genGoals, genWater) { (entries, goals, extra) =>
      val before = HydrationStats.byDay(entries, goals)
      val after = HydrationStats.byDay(extra :: entries, goals)
      after.values.flatMap(_.progress).foreach(p => assert(p.completion >= 0.0 && p.completion <= 1.0, clue(p)))
      val date = extra.localDate
      assert(after(date).totalMl > before.get(date).map(_.totalMl).getOrElse(0))
      for
        was <- before.get(date).flatMap(_.progress)
        is <- after(date).progress
      do
        assert(is.completion >= was.completion)
        assert(is.percent >= was.percent)
    }

  property("no period figure is shown below its minimum of recorded days"):
    forAll(genWaters, genGoals, Gen.oneOf(week, month)) { (entries, goals, window) =>
      val period = HydrationStats.period(HydrationStats.byDay(entries, goals), window)
      val required = if window == week then 3 else 10
      assert(period.recordedDays <= window.days)
      assertEquals(period.meanMl.shown.isDefined, period.recordedDays >= required)
      period.goalDays.flatMap(_.shown).foreach { goalDays =>
        assert(goalDays.denominator >= required && goalDays.denominator <= period.recordedDays, clue(goalDays))
      }
      period.meanMl.shown.foreach(mean => assert(mean >= 1 && mean <= 60 * 5000, clue(mean)))
    }

  property("on a daylight-saving day of 23 or 25 hours every entry made inside the day counts toward it"):
    forAll(
      InsightGens.genDstDay.flatMap(d => Gen.listOfN(8, InsightGens.genInstantOn(d._2, d._1)).map(d -> _))
    ) { case ((zone, date, _), instants) =>
      val days = HydrationStats.byDay(instants.map(water(_, zone, 250)), Nil)
      assertEquals(days.keySet, Set(date))
      assertEquals(days(date).totalMl, 8 * 250)
    }
end HydrationStatsSuite
