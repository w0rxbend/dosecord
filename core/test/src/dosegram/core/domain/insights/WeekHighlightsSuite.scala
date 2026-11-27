package dosegram.core.domain.insights

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The week highlights of image round 5 (VISUALS IMG-25): the highest day per domain from three recorded days, every
  * day that shares it, and nothing for medication.
  */
class WeekHighlightsSuite extends munit.ScalaCheckSuite:

  private val week = List(Some(1200.0), None, Some(2100.0), Some(900.0), None, Some(2100.0), None)

  test("the highest day of a week of three or more recorded days, with every day that shares it"):
    assertEquals(
      WeekHighlights.of(HighlightDomain.Water, week),
      Some(DayHighlight(HighlightDomain.Water, List(0, 2, 3, 5), Some(List(2, 5) -> 2100.0)))
    )

  test("below three recorded days only the recorded days are given"):
    val sparse = List(None, Some(7.5), None, None, Some(8.0), None, None)
    assertEquals(
      WeekHighlights.of(HighlightDomain.Sleep, sparse),
      Some(DayHighlight(HighlightDomain.Sleep, List(1, 4), None))
    )

  test("a week without a record has no row, and a highest value of zero is no highlight"):
    assertEquals(WeekHighlights.of(HighlightDomain.Mood, List.fill(7)(None)), None)
    val snacksOnly = List(Some(0.0), Some(0.0), Some(0.0), None, None, None, None)
    assertEquals(WeekHighlights.of(HighlightDomain.Food, snacksOnly).flatMap(_.top), None)

  test("medication is not a highlight domain (R-GAM-2)"):
    assertEquals(HighlightDomain.values.map(_.toString).toSet, Set("Water", "Food", "Mood", "Sleep"))

  property("the highlighted days are recorded days that hold the week's highest value"):
    val day = Gen.option(Gen.chooseNum(1, 5).map(_.toDouble))
    forAll(Gen.listOfN(7, day)) { days =>
      WeekHighlights.of(HighlightDomain.Mood, days).forall { highlight =>
        val values = days.flatten
        highlight.recorded.size == values.size &&
        highlight.top.forall { (top, value) =>
          values.size >= WeekHighlights.MinimumDays && value == values.max &&
          top == highlight.recorded.filter(index => days(index).contains(value))
        }
      }
    }
