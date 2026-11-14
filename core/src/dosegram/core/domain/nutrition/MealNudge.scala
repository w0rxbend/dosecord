package dosegram.core.domain.nutrition

import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.NudgeSlot

/** Meal-time nudges (PRODUCT_SPEC R-NUT-6, ROADMAP G8.4): a slot at a meal anchor of the day profile is that meal's
  * nudge, and an entry of that meal on the slot's day answers it, whenever it was made.
  */
object MealNudge:

  /** The meal a slot reminds of: the main meal whose anchor is the slot's time; `None` for a slot at another time. */
  def mealOf(profile: DayProfile, slot: NudgeSlot): Option[MealType] =
    MealTimes(profile.breakfast, profile.lunch, profile.dinner).mealAt(slot.local.toLocalTime)

  /** Whether the slot's meal is already logged on its day (live entries of that day). */
  def isLogged(meal: Option[MealType], entriesOfDay: List[MealEntry]): Boolean =
    meal.exists(mealType => entriesOfDay.exists(entry => entry.isLive && entry.mealType == mealType))
