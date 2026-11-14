package dosegram.core.domain.insights

import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealType

import java.time.LocalDate

/** The meals of one local day as counts per type: the `nutrition` rollup (ARCHITECTURE INS-05). Counts only: no word of
  * a meal and no energy value enters it.
  */
final case class MealDay(breakfast: Int, lunch: Int, dinner: Int, snacks: Int):
  def meals: Int = breakfast + lunch + dinner + snacks

  def count(mealType: MealType): Int =
    mealType match
      case MealType.Breakfast => breakfast
      case MealType.Lunch     => lunch
      case MealType.Dinner    => dinner
      case MealType.Snack     => snacks

  /** The main meals with at least one entry. */
  def logged: Set[MealType] = MealType.main.filter(count(_) > 0).toSet

/** The regular-meal view of one day (PRODUCT_SPEC R-NUT-3, VISUALS section 7.5): which of the planned main meals have
  * an entry. A planned meal without an entry is "not logged", never "skipped". With no planned meal the day is a count.
  */
final case class RegularMealDay(date: LocalDate, plan: MealPlan, logged: Set[MealType], snacks: Int):

  /** Distinct planned main meals with an entry. */
  def plannedLogged: Int = plan.planned.count(logged)

  def notLogged: List[MealType] = MealType.main.filter(meal => plan.planned(meal) && !logged(meal))

  /** The share drawn by the daily card, `None` when nothing is planned: a day without a plan has no rate. */
  def progress: Option[Double] =
    Option.when(plan.planned.nonEmpty)(plannedLogged.toDouble / plan.planned.size.toDouble)

/** The meals of a period: the meals and the days with a meal, and per main meal the number of days it was logged on
  * (MINI_APP MA-FOOD-4: counts, no percentages).
  */
final case class MealPeriod(window: DateRange, meals: Int, daysWithMeals: Int, daysPerMeal: Map[MealType, Int])

object MealStats:

  def byDay(entries: Iterable[MealEntry]): Map[LocalDate, MealDay] =
    entries
      .filter(_.isLive)
      .groupBy(_.localDate)
      .map: (date, own) =>
        def of(mealType: MealType): Int = own.count(_.mealType == mealType)
        date -> MealDay(of(MealType.Breakfast), of(MealType.Lunch), of(MealType.Dinner), of(MealType.Snack))

  def regular(date: LocalDate, day: Option[MealDay], plan: MealPlan): RegularMealDay =
    RegularMealDay(date, plan, day.fold(Set.empty[MealType])(_.logged), day.fold(0)(_.snacks))

  def period(days: Map[LocalDate, MealDay], window: DateRange): MealPeriod =
    val inside = days.view.filterKeys(window.contains).values.filter(_.meals > 0).toList
    MealPeriod(
      window = window,
      meals = inside.map(_.meals).sum,
      daysWithMeals = inside.size,
      daysPerMeal = MealType.main.map(meal => meal -> inside.count(_.count(meal) > 0)).toMap
    )
end MealStats
