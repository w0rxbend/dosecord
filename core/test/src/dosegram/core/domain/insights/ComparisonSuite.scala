package dosegram.core.domain.insights

import dosegram.core.domain.hydration.GoalChange
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** VISUALS_AND_ANALYTICS sections 8.3 to 8.5 (VA-IN-12, VA-IN-13) and PRODUCT_SPEC AC-INS-3, AC-INS-5: the closed rule
  * registry, the minimum data, the minimum difference and the two stability checks.
  */
class ComparisonSuite extends munit.ScalaCheckSuite:

  import TrackingGens.*

  private val window: DateRange = DateRange.endingOn(windowEnd, 28)
  private val rule = InsightRule.WaterGoalAndMood

  /** Paired days on consecutive dates from the window start, alternating between the groups so both halves see both. */
  private def paired(first: List[Double], second: List[Double]): List[PairedDay] =
    val firstDays = first.map(outcome => (true, outcome))
    val secondDays = second.map(outcome => (false, outcome))
    val interleaved = firstDays.zipAll(secondDays, null, null).flatMap((a, b) => List(Option(a), Option(b)).flatten)
    interleaved.zipWithIndex.map { case ((inFirst, outcome), index) =>
      PairedDay(window.start.plusDays(index.toLong), inFirst, outcome)
    }

  test("no rule of the registry reads a medication measure (VA-IN-13, AC-INS-5)"):
    InsightRule.values.foreach { rule =>
      assertNotEquals(rule.grouping.domain, RollupDomain.Medication, clue(rule))
      assertNotEquals(rule.outcome.domain, RollupDomain.Medication, clue(rule))
    }
    assertEquals(InsightRule.values.map(_.id).toList, List("IR-04"))

  test("a stable difference of at least 0.3 levels is shown with both group sizes and means"):
    Comparisons.compare(rule, paired(List.fill(8)(4.0), List.fill(8)(3.0)), window) match
      case Comparison.Shown(shownRule, first, second, shownWindow) =>
        assertEquals((shownRule, shownWindow), (rule, window))
        assertEquals((first, second), (GroupMean(8, 4.0), GroupMean(8, 3.0)))
      case other => fail(s"expected a shown comparison, got $other")

  test("13 paired days are not enough (AC-INS-3)"):
    val result = Comparisons.compare(rule, paired(List.fill(7)(4.0), List.fill(6)(3.0)), window)
    assertEquals(result, Comparison.TooFewDays(rule, 13, 14, window))

  test("4 days in one group are not enough (AC-INS-3)"):
    val result = Comparisons.compare(rule, paired(List.fill(4)(4.0), List.fill(12)(3.0)), window)
    assertEquals(result, Comparison.TooFewInGroup(rule, 4, 5, window))

  test("days that all fall in one group have no variation"):
    val result = Comparisons.compare(rule, paired(List.fill(16)(4.0), Nil), window)
    assertEquals(result, Comparison.NoVariation(rule, 16, window))

  test("a difference under 0.3 levels is withheld, not shown as no link"):
    val result = Comparisons.compare(rule, paired(List.fill(8)(3.2), List.fill(8)(3.0)), window)
    assertEquals(result, Comparison.Withheld(rule, WithheldReason.BelowMinimumDifference, window))

  test("a difference of exactly 0.3 levels is shown"):
    val result = Comparisons.compare(rule, paired(List.fill(8)(3.3), List.fill(8)(3.0)), window)
    assert(result.isInstanceOf[Comparison.Shown], clue(result))

  test("a difference that changes sign between the earlier and the later half is withheld"):
    val earlier = paired(List.fill(4)(5.0), List.fill(4)(2.0))
    val later = paired(List.fill(4)(3.0), List.fill(4)(3.4)).map(day => day.copy(date = day.date.plusDays(8)))
    val result = Comparisons.compare(rule, earlier ++ later, window)
    assertEquals(result, Comparison.Withheld(rule, WithheldReason.UnstableBetweenHalves, window))

  test("a half with fewer than two days in a group is withheld"):
    val earlier = List.tabulate(8)(index => PairedDay(window.start.plusDays(index.toLong), index < 7, 4.0))
    val later = List.tabulate(8)(index => PairedDay(window.start.plusDays(8L + index), false, 3.0))
    val result = Comparisons.compare(rule, earlier ++ later, window)
    assertEquals(result, Comparison.Withheld(rule, WithheldReason.UnstableBetweenHalves, window))

  test("a difference that rests on a single day is withheld (VA-IN-12)"):
    val first = List(3.0, 3.0, 5.0, 3.0, 3.0, 5.0)
    val second = List.fill(8)(3.35)
    val flipsWithoutOneDay = paired(first, second)
    val result = Comparisons.compare(rule, flipsWithoutOneDay, window)
    assertEquals(result, Comparison.Withheld(rule, WithheldReason.UnstableWithoutOneDay, window))

  test("the water rule pairs a day only when it has a goal, an entry and a check-in"):
    val goals = List(GoalChange(window.start, Some(2000)))
    val reached = List.tabulate(8)(index => window.start.plusDays(2L * index))
    val notReached = List.tabulate(8)(index => window.start.plusDays(2L * index + 1L))
    val waterOnly = window.end
    val moodOnly = window.end.minusDays(1)
    val entries = reached.map(waterOn(_, 2400)) ++ notReached.map(waterOn(_, 900)) :+ waterOn(waterOnly, 2400)
    val checkins = reached.map(moodOn(_, 4)) ++ notReached.map(moodOn(_, 3)) :+ moodOn(moodOnly, 1)
    val result =
      Comparisons.waterGoalAndMood(HydrationStats.byDay(entries, goals), MoodStats.byDay(checkins), window)
    result match
      case Comparison.Shown(_, first, second, _) => assertEquals((first, second), (GroupMean(8, 4.0), GroupMean(8, 3.0)))
      case other                                  => fail(s"expected a shown comparison, got $other")

  test("without a goal no day is paired"):
    val dates = window.dates
    val result = Comparisons.waterGoalAndMood(
      HydrationStats.byDay(dates.map(waterOn(_, 2000)), Nil),
      MoodStats.byDay(dates.map(moodOn(_, 3))),
      window
    )
    assertEquals(result, Comparison.TooFewDays(rule, 0, 14, window))

  private val genPaired: Gen[List[PairedDay]] =
    Gen
      .listOfN(28, Gen.option(Gen.zip(Gen.oneOf(true, false), Gen.choose(1.0, 5.0))))
      .map(_.zipWithIndex.collect { case (Some((inFirst, outcome)), index) =>
        PairedDay(window.start.plusDays(index.toLong), inFirst, outcome)
      })

  property("a shown comparison always meets every minimum"):
    forAll(genPaired) { days =>
      Comparisons.compare(rule, days, window) match
        case Comparison.Shown(_, first, second, _) =>
          assert(first.days + second.days >= 14 && first.days >= 5 && second.days >= 5, clue(days))
          assert(Math.abs(first.mean - second.mean) >= rule.minimumDifference - 1e-9, clue(days))
          assertEquals(first.days + second.days, days.size)
        case _ => ()
    }

  property("below the minimum data nothing is shown"):
    forAll(genPaired) { days =>
      val (first, second) = days.partition(_.inFirstGroup)
      if days.size < 14 || first.size < 5 || second.size < 5 then
        assert(!Comparisons.compare(rule, days, window).isInstanceOf[Comparison.Shown], clue(days))
    }

  property("a shown comparison survives the removal of any single day"):
    forAll(genPaired) { days =>
      Comparisons.compare(rule, days, window) match
        case Comparison.Shown(_, first, second, _) =>
          val sign = Math.signum(first.mean - second.mean)
          days.indices.foreach { index =>
            val (firstRest, secondRest) = days.patch(index, Nil, 1).partition(_.inFirstGroup)
            val difference = mean(firstRest) - mean(secondRest)
            assertEquals(Math.signum(difference), sign, clue(index))
            assert(Math.abs(difference) >= rule.minimumDifference / 2 - 1e-9, clue(index))
          }
        case _ => ()
    }

  private def mean(days: List[PairedDay]): Double = days.map(_.outcome).sum / days.size.toDouble
end ComparisonSuite
