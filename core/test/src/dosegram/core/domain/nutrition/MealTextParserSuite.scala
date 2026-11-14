package dosegram.core.domain.nutrition

/** PRODUCT_SPEC R-NUT-4 and AC-NUT-2, BOT_UX section 11.3: a meal word and a colon record a meal with the rest as its
  * words; a meal word without the colon, or "ate" and "food", ask which meal; anything else is not a meal.
  */
class MealTextParserSuite extends munit.FunSuite:

  private def logged(mealType: MealType, text: Option[String]): MealLine =
    MealLine.Logged(mealType, text.map(raw => MealText.of(raw).toOption.flatten.get))

  test("\"lunch: soup and bread\" is a lunch with its words (AC-NUT-2)"):
    assertEquals(MealTextParser.free("lunch: soup and bread"), logged(MealType.Lunch, Some("soup and bread")))

  test("the Ukrainian meal words record the same meals (AC-NUT-2)"):
    assertEquals(MealTextParser.free("обід: суп і хліб"), logged(MealType.Lunch, Some("суп і хліб")))
    assertEquals(MealTextParser.free("Сніданок: каша"), logged(MealType.Breakfast, Some("каша")))
    assertEquals(MealTextParser.free("вечеря:"), logged(MealType.Dinner, None))
    assertEquals(MealTextParser.free("перекус: яблуко"), logged(MealType.Snack, Some("яблуко")))

  test("\"soup and bread\" alone is not a meal (AC-NUT-2)"):
    assertEquals(MealTextParser.free("soup and bread"), MealLine.NotMeal)
    assertEquals(MealTextParser.free("note: buy milk"), MealLine.NotMeal)
    assertEquals(MealTextParser.free(""), MealLine.NotMeal)

  test("the words keep the case and inner spacing they were typed with; the meal word may be in any case"):
    assertEquals(MealTextParser.free("  DINNER :  Pasta with  Salad  "), logged(MealType.Dinner, Some("Pasta with  Salad")))
    assertEquals(MealTextParser.free("breakfast：oatmeal"), logged(MealType.Breakfast, Some("oatmeal")))

  test("a meal word with nothing after the colon is that meal without words"):
    assertEquals(MealTextParser.free("snack:"), logged(MealType.Snack, None))

  test("a meal word without the colon, and \"ate\" or \"food\", ask which meal (BOT_UX section 11.3)"):
    assertEquals(MealTextParser.free("lunch was late today"), MealLine.Ambiguous)
    assertEquals(MealTextParser.free("lunch pasta"), MealLine.Ambiguous)
    assertEquals(MealTextParser.free("ate a sandwich"), MealLine.Ambiguous)
    assertEquals(MealTextParser.free("Food: pizza"), MealLine.Ambiguous)

  test("words of 300 characters are kept; 301 are refused rather than cut (R-NUT-1)"):
    assertEquals(MealTextParser.free("lunch: " + "a" * 300), logged(MealType.Lunch, Some("a" * 300)))
    assertEquals(MealTextParser.free("lunch: " + "a" * 301), MealLine.TooLong(300))

  test("words over several lines are one meal"):
    assertEquals(MealTextParser.free("dinner: soup\nbread"), logged(MealType.Dinner, Some("soup\nbread")))

  test("the argument of /food may name the meal alone"):
    assertEquals(MealTextParser.argument("lunch"), logged(MealType.Lunch, None))
    assertEquals(MealTextParser.argument("lunch: pasta"), logged(MealType.Lunch, Some("pasta")))
    assertEquals(MealTextParser.argument("pasta"), MealLine.NotMeal)

  test("the meal that fits a time is the nearest main meal within 90 minutes, a snack otherwise"):
    val times = MealTimes(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(13, 0), java.time.LocalTime.of(19, 0))
    assertEquals(times.suggest(java.time.LocalTime.of(7, 40)), MealType.Breakfast)
    assertEquals(times.suggest(java.time.LocalTime.of(14, 30)), MealType.Lunch)
    assertEquals(times.suggest(java.time.LocalTime.of(16, 0)), MealType.Snack)
    assertEquals(times.suggest(java.time.LocalTime.of(20, 20)), MealType.Dinner)
    assertEquals(times.suggest(java.time.LocalTime.of(2, 0)), MealType.Snack)
