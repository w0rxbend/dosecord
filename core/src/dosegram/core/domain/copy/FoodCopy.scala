package dosegram.core.domain.copy

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MealPeriod
import dosegram.core.domain.insights.RegularMealDay
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealType

/** The meal copy of the chat (BOT_UX sections 11.3 and 22.2). It states what the user recorded and nothing else: no
  * word rates a meal or a day, no energy value and no target appears, and a meal that was not logged is never mentioned
  * as missing (PRODUCT_SPEC R-NUT-3, R-NUT-5; BUX-FOOD-04).
  */
object FoodCopy:
  import UkNouns.{DaysOf, Meals, PlannedMeals, Snacks}
  def which = tr("Which meal?", "Який прийом їжі?")
  def photoLater = tr(
    "Photos cannot be saved yet. Send the meal as text, for example: lunch: pasta.",
    "Фото поки не зберігаються. Надішліть прийом їжі текстом, наприклад: обід: паста."
  )
  def ambiguousHint = tr(
    "To save your words with it, start with the meal and a colon, for example: lunch: soup.",
    "Щоб зберегти й ваші слова, почніть із назви прийому їжі та двокрапки, наприклад: обід: суп."
  )
  def usage = tr(
    "Send /food, or the meal and a colon, for example: /food lunch: soup.",
    "Надішліть /food або назву прийому їжі з двокрапкою, наприклад: /food обід: суп."
  )
  def undone = tr("Meal removed.", "Прийом їжі видалено.")
  def unknown = tr("That meal is no longer there.", "Цього прийому їжі вже немає.")
  def undoWindowPassed = tr(
    "This can no longer be undone here. Change it in the app.",
    "Тут це вже не скасувати. Змініть запис у застосунку."
  )
  def noneToday = tr("No meal logged today.", "Сьогодні прийомів їжі не записано.")
  def EditInApp = tr("Edit in app", "Змінити в застосунку")
  def ShowAsImage = tr("Show as image", "Показати зображенням")
  def imageUnavailable = tr(
    "The image is not available right now. The meals above are complete.",
    "Зображення зараз недоступне. Перелік прийомів їжі вище повний."
  )
  def imageEmpty = tr(
    "No meal logged today, so there is nothing to draw yet.",
    "Сьогодні прийомів їжі не записано, тож зображувати поки нічого."
  )

  def label(mealType: MealType): String =
    mealType match
      case MealType.Breakfast => tr("Breakfast", "Сніданок")
      case MealType.Lunch     => tr("Lunch", "Обід")
      case MealType.Dinner    => tr("Dinner", "Вечеря")
      case MealType.Snack     => tr("Snack", "Перекус")

  /** `food.saved`: "Lunch saved at 13:05." The words themselves are not echoed. */
  def saved(mealType: MealType, time: String, withNote: Boolean): String =
    if withNote then
      tr(
        s"${label(mealType)} saved at $time, with your note.",
        s"${label(mealType)}: збережено о $time, разом із вашою нотаткою."
      )
    else tr(s"${label(mealType)} saved at $time.", s"${label(mealType)}: збережено о $time.")

  def tooLong(limit: Int): String = tr(
    s"A meal note can be at most $limit characters. Send a shorter one.",
    s"Нотатка до прийому їжі може мати щонайбільше $limit символів. Надішліть коротшу."
  )

  /** The meals logged today in time order: "Today: breakfast 08:10, lunch 13:05." */
  def today(meals: List[(MealType, String)]): String =
    if meals.isEmpty then noneToday
    else
      meals
        .map((mealType, time) => s"${label(mealType).toLowerCase(java.util.Locale.ROOT)} $time")
        .mkString(tr("Today: ", "Сьогодні: "), ", ", ".")

  def notLogged = tr("Not logged", "Не записано")

  private def count(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  /** The regular-meal view of a day (R-NUT-3): planned meals as "2 of 3 planned meals", without a plan a count only. */
  def day(view: RegularMealDay): String =
    val main = view.logged.size
    val meals =
      if view.plan.planned.isEmpty then
        if main == 0 then tr("No meal logged", "Прийомів їжі не записано")
        else tr(s"${count(main, "meal")} logged", s"записано: ${Meals.count(main)}")
      else
        tr(
          s"${view.plannedLogged} of ${count(view.plan.planned.size, "planned meal")} logged",
          s"записано ${view.plannedLogged} з ${PlannedMeals.count(view.plan.planned.size)}"
        )
    if view.snacks == 0 then meals
    else tr(s"$meals, ${count(view.snacks, "snack")}", s"$meals, ${Snacks.count(view.snacks)}")

  /** Per main meal, the days it was logged on (MINI_APP MA-FOOD-4): counts, no percentages. */
  def week(period: MealPeriod): String =
    val parts = MealType.main.map(meal => s"${label(meal)} ${period.daysPerMeal.getOrElse(meal, 0)}")
    tr(
      s"${parts.mkString(", ")} of ${count(period.window.days, "day")}",
      s"${parts.mkString(", ")} з ${DaysOf.count(period.window.days)}"
    )

  /** The food line of `/stats` for today; absent on a day without a meal. */
  def statsDay(view: RegularMealDay): String = tr(s"Food: ${day(view)}", s"Їжа: ${day(view)}")

  /** The food line of `/stats` for a period, or `None` when it has no meal (BUX-STAT-02). */
  def statsPeriod(period: MealPeriod): Option[String] =
    Option.when(period.meals > 0)(
      tr(
        s"Food: ${count(period.meals, "meal")} logged · ${week(period)}",
        s"Їжа: записано ${Meals.count(period.meals)} · ${week(period)}"
      )
    )

  /** A meal nudge (G8.4): it names the meal and offers the tap; it says nothing about what or how much to eat. */
  def nudge(meal: Option[MealType]): String =
    meal.fold(
      tr(
        "Meal reminder. Tap the meal you had to log it.",
        "Нагадування про їжу. Натисніть на прийом їжі, щоб його записати."
      )
    )(mealType =>
      tr(
        s"Meal reminder: ${label(mealType).toLowerCase(java.util.Locale.ROOT)}. Tap to log it.",
        s"Нагадування про їжу: ${label(mealType).toLowerCase(java.util.Locale.ROOT)}. Натисніть, щоб записати."
      )
    )

  def logButton(mealType: MealType): String = tr(
    s"Log ${label(mealType).toLowerCase(java.util.Locale.ROOT)}",
    s"Записати: ${label(mealType).toLowerCase(java.util.Locale.ROOT)}"
  )

  def entries: List[CopyEntry] =
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
      CopyEntry("food.edit_in_app", EditInApp),
      CopyEntry("food.image.button", ShowAsImage),
      CopyEntry("food.image.unavailable", imageUnavailable),
      CopyEntry("food.image.empty", imageEmpty),
      CopyEntry("food.not_logged", notLogged),
      CopyEntry("food.day", day(Samples.day(MealPlan.Default))),
      CopyEntry("food.day.no_plan", day(Samples.day(MealPlan(Set.empty)))),
      CopyEntry("food.week", week(Samples.week)),
      CopyEntry("food.nudge", nudge(Some(MealType.Lunch))),
      CopyEntry("food.nudge.any", nudge(None)),
      CopyEntry("food.nudge.log", logButton(MealType.Lunch)),
      CopyEntry("food.stats.day", statsDay(Samples.day(MealPlan.Default))),
      CopyEntry("food.stats.period", statsPeriod(Samples.week).getOrElse(""))
    ) ++ MealType.values.toList.map(mealType => CopyEntry(s"food.button.${mealType.dbValue}", label(mealType)))

  /** Sample figures for the catalogue entries of the parameterised lines. */
  private object Samples:
    private val date = java.time.LocalDate.parse("2026-11-12")

    def day(plan: MealPlan): RegularMealDay =
      RegularMealDay(date, plan, Set(MealType.Breakfast, MealType.Dinner), snacks = 1)

    val week: MealPeriod =
      MealPeriod(
        DateRange.endingOn(date, 7),
        meals = 19,
        daysWithMeals = 7,
        daysPerMeal = Map(MealType.Breakfast -> 6, MealType.Lunch -> 5, MealType.Dinner -> 7)
      )
end FoodCopy
