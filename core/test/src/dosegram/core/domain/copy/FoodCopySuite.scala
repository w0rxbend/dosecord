package dosegram.core.domain.copy

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
