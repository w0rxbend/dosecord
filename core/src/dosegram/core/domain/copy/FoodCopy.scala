package dosegram.core.domain.copy

import dosegram.core.domain.nutrition.MealType

/** The meal copy of the chat (BOT_UX sections 11.3 and 22.2). It states what the user recorded and nothing else: no
  * word rates a meal or a day, no energy value and no target appears, and a meal that was not logged is never mentioned
  * as missing (PRODUCT_SPEC R-NUT-3, R-NUT-5; BUX-FOOD-04).
  */
object FoodCopy:
  val which = "Which meal?"
  val photoLater = "Photos cannot be saved yet. Send the meal as text, for example: lunch: pasta."
  val ambiguousHint = "To save your words with it, start with the meal and a colon, for example: lunch: soup."
  val usage = "Send /food, or the meal and a colon, for example: /food lunch: soup."
  val undone = "Meal removed."
  val unknown = "That meal is no longer there."
  val undoWindowPassed = "This can no longer be undone here. Change it in the app."
  val noneToday = "No meal logged today."
  val EditInApp = "Edit in app"

  def label(mealType: MealType): String =
    mealType match
      case MealType.Breakfast => "Breakfast"
      case MealType.Lunch     => "Lunch"
      case MealType.Dinner    => "Dinner"
      case MealType.Snack     => "Snack"

  /** `food.saved`: "Lunch saved at 13:05." The words themselves are not echoed. */
  def saved(mealType: MealType, time: String, withNote: Boolean): String =
    if withNote then s"${label(mealType)} saved at $time, with your note."
    else s"${label(mealType)} saved at $time."

  def tooLong(limit: Int): String = s"A meal note can be at most $limit characters. Send a shorter one."

  /** The meals logged today in time order: "Today: breakfast 08:10, lunch 13:05." */
  def today(meals: List[(MealType, String)]): String =
    if meals.isEmpty then noneToday
    else
      meals
        .map((mealType, time) => s"${label(mealType).toLowerCase(java.util.Locale.ROOT)} $time")
        .mkString("Today: ", ", ", ".")

  val entries: List[CopyEntry] =
    List(
      CopyEntry("food.which", which),
      CopyEntry("food.saved", saved(MealType.Lunch, "13:05", withNote = false)),
      CopyEntry("food.saved.note", saved(MealType.Lunch, "13:05", withNote = true)),
      CopyEntry("food.photo.later", photoLater),
      CopyEntry("food.ambiguous", ambiguousHint),
      CopyEntry("food.usage", usage),
      CopyEntry("food.undone", undone),
      CopyEntry("food.unknown", unknown),
      CopyEntry("food.undo_window_passed", undoWindowPassed),
      CopyEntry("food.none_today", noneToday),
      CopyEntry("food.today", today(List(MealType.Breakfast -> "08:10", MealType.Lunch -> "13:05"))),
      CopyEntry("food.too_long", tooLong(300)),
      CopyEntry("food.edit_in_app", EditInApp)
    ) ++ MealType.values.toList.map(mealType => CopyEntry(s"food.button.${mealType.dbValue}", label(mealType)))
end FoodCopy
