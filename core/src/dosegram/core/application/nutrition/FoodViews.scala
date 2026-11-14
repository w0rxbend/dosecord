package dosegram.core.application.nutrition

import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.insights.RegularMealDay
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.FoodLineView

/** The image views of a day of meals (VISUALS sections 6.4 and 6.10), built from the same records as its text. */
object FoodViews:

  /** The regular-meal view of a day: which planned main meals have an entry (R-NUT-3). */
  def regular(day: MealDayView, plan: MealPlan): RegularMealDay =
    MealStats.regular(day.date, MealStats.byDay(day.entries).get(day.date), plan)

  /** The food line of the daily summary: against the plan, or a count when nothing is planned. */
  def line(day: MealDayView, plan: MealPlan): FoodLineView =
    val view = regular(day, plan)
    if plan.planned.isEmpty then FoodLineView(view.logged.size, 0)
    else FoodLineView(view.plannedLogged, plan.planned.size)

  /** IMG-09: each main meal at the local time of its first entry, and the snacks counted. */
  def card(day: MealDayView, plan: MealPlan): FoodCardView =
    val mains = day.entries
      .filter(_.mealType.isMain)
      .groupBy(_.mealType)
      .map((mealType, own) => mealType -> own.minBy(_.occurredAt).occurredAt.atZone(day.zone).toLocalTime)
    FoodCardView(day.date, mains, day.entries.count(_.mealType == MealType.Snack), line(day, plan))
