package dosegram.core.domain.insights

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.LocalDate

/** ROADMAP G4.4 and ARCHITECTURE section 11.3: a statistic below its minimum sample is `Insufficient`, and the display
  * rounding of VISUALS section 7.1 holds for every ratio.
  */
class StatSuite extends munit.ScalaCheckSuite:

  private val window: DateRange = DateRange.endingOn(LocalDate.parse("2026-03-07"), 7)

  private val genMinimum: Gen[MinimumSample] = Gen.oneOf(MinimumSample.values.toList)

  private val genRatio: Gen[Ratio] =
    for
      denominator <- Gen.choose(1, 500)
      numerator <- Gen.choose(0, denominator)
    yield Ratio(numerator, denominator)

  property("a sample below the minimum is Insufficient with the count and the requirement"):
    forAll(genMinimum, Gen.choose(0, 20)) { (minimum, n) =>
      val stat = Stat.gated(n, minimum, window, Basis.Recorded)(42)
      if n < minimum.required then assertEquals(stat, Stat.Insufficient(n, minimum.required, window))
      else assertEquals(stat.shown, Some(42))
    }

  test("the value of an insufficient statistic is never evaluated"):
    val stat = Stat.gated(0, MinimumSample.PercentageDenominator, window, Basis.Recorded)(fail("evaluated"))
    assertEquals(stat, Stat.Insufficient(0, 5, window))

  test("a mean over at most seven days needs three recorded days, a longer one ten"):
    assertEquals(MinimumSample.meanOver(window), MinimumSample.WeeklyMeanDays)
    assertEquals(MinimumSample.meanOver(DateRange.endingOn(window.end, 30)), MinimumSample.MonthlyMeanDays)

  property("a displayed percentage is 100 only for all, 0 only for none, otherwise 1 to 99"):
    forAll(genRatio) { ratio =>
      val percent = ratio.percent
      if ratio.numerator == ratio.denominator then assertEquals(percent, 100)
      else if ratio.numerator == 0 then assertEquals(percent, 0)
      else assert(percent >= 1 && percent <= 99, s"$ratio displayed as $percent")
    }

  property("the displayed percentage never falls when the numerator grows"):
    forAll(genRatio) { ratio =>
      if ratio.numerator < ratio.denominator then
        assert(Ratio(ratio.numerator + 1, ratio.denominator).percent >= ratio.percent)
    }

  test("percentages round half up"):
    assertEquals(Ratio(26, 28).percent, 93)
    assertEquals(Ratio(1, 8).percent, 13)
    assertEquals(Ratio(8, 10).percent, 80)
    assertEquals(Ratio(199, 200).percent, 99)
    assertEquals(Ratio(1, 400).percent, 1)

  test("a date range counts both of its ends"):
    assertEquals(window.start, LocalDate.parse("2026-03-01"))
    assertEquals(window.days, 7)
    assertEquals(window.dates.size, 7)
    assert(window.contains(window.start) && window.contains(window.end))
    assert(!window.contains(window.end.plusDays(1)))
    assertEquals(DateRange.single(window.end).days, 1)

  test("a date range refuses an end before its start"):
    intercept[IllegalArgumentException](DateRange.between(window.end, window.start))
    intercept[IllegalArgumentException](DateRange.endingOn(window.end, 0))
end StatSuite
