package dosegram.core.domain.copy

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealType

import java.time.LocalDate
import java.util.Locale

/** PRODUCT_SPEC AC-NUT-3 and R-NUT-5, BOT_UX BUX-FOOD-04: the meal copy rates nothing, names no energy or target, and
  * never calls a meal that was not logged skipped or missed.
  */
class FoodCopySuite extends munit.FunSuite:

  private val judgement = List("skipped", "missed", "good", "bad", "cheat", "healthy", "unhealthy", "junk", "guilt")
  private val counting = List("kcal", "calorie", "kj", "protein", "carb", "fat", "target", "limit", "too much")

  private def foodTexts: List[String] =
    FoodCopy.entries.map(_.text) ++ ConsentCopy.entries.filter(_.id.contains("nutrition")).map(_.text)

  test("no meal string uses a judgement word (AC-NUT-3)"):
    foodTexts.foreach: text =>
      val lower = text.toLowerCase(Locale.ROOT)
      judgement.foreach(word => assert(!lower.contains(word), s"'$word' in: $text"))

  test("no meal string names energy, a nutrient or a target (BUX-FOOD-04)"):
    foodTexts.foreach: text =>
      val words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+").toSet
      counting.foreach(word => assert(!words.exists(_.startsWith(word)), s"'$word' in: $text"))

  test("the nutrition consent names what it stores"):
    assert(ConsentCopy.entries.exists(e => e.id == "consent.nutrition.stage1" && e.text.contains("meal records")))

  private val date = LocalDate.parse("2026-11-12")

  test("the regular-meal view counts planned meals; with no plan a day is a count, never a rate (G8.3)"):
    val day = Some(MealDay(breakfast = 1, lunch = 0, dinner = 2, snacks = 1))
    val planned = MealStats.regular(date, day, MealPlan.Default)
    assertEquals(FoodCopy.day(planned), "2 of 3 planned meals logged, 1 snack")
    assertEquals(planned.notLogged, List(MealType.Lunch))
    assertEquals(planned.progress, Some(2.0 / 3.0))
    val unplanned = MealStats.regular(date, day, MealPlan(Set.empty))
    assertEquals(FoodCopy.day(unplanned), "2 meals logged, 1 snack")
    assertEquals(unplanned.progress, None)
    assertEquals(unplanned.notLogged, Nil)
    assertEquals(FoodCopy.day(MealStats.regular(date, None, MealPlan(Set.empty))), "No meal logged")

  test("the weekly line counts the days per main meal, without a percentage (MA-FOOD-4)"):
    val days = Map(
      date -> MealDay(1, 1, 1, 0),
      date.minusDays(1) -> MealDay(1, 0, 1, 2),
      date.minusDays(9) -> MealDay(1, 1, 1, 0)
    )
    val period = MealStats.period(days, DateRange.endingOn(date, 7))
    assertEquals((period.meals, period.daysWithMeals), (7, 2))
    assertEquals(FoodCopy.week(period), "Breakfast 2, Lunch 1, Dinner 2 of 7 days")
