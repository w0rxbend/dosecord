package dosegram.core.domain.nutrition

import java.time.Duration
import java.time.LocalTime

/** The meal times of the user's day profile (ADR-023 anchors): breakfast, lunch and dinner. */
final case class MealTimes(breakfast: LocalTime, lunch: LocalTime, dinner: LocalTime):

  def of(mealType: MealType): Option[LocalTime] =
    mealType match
      case MealType.Breakfast => Some(breakfast)
      case MealType.Lunch     => Some(lunch)
      case MealType.Dinner    => Some(dinner)
      case MealType.Snack     => None

  /** The meal that fits a local time (BOT_UX section 11.3 step 2): the main meal whose time is nearest, when it is at
    * most [[MealTimes.Reach]] away; a snack otherwise. It only marks a button: the user's tap decides.
    */
  def suggest(at: LocalTime): MealType =
    MealType.main
      .flatMap(meal => of(meal).map(time => meal -> MealTimes.distance(at, time)))
      .filter((_, distance) => distance.compareTo(MealTimes.Reach) <= 0)
      .minByOption((_, distance) => distance)
      .fold(MealType.Snack)((meal, _) => meal)

  /** The main meal whose time is exactly `at`, for a prompt slot made from a meal anchor. */
  def mealAt(at: LocalTime): Option[MealType] = MealType.main.find(meal => of(meal).contains(at))

object MealTimes:
  val Reach: Duration = Duration.ofMinutes(90)

  /** The distance of two clock times on the 24-hour circle. */
  private def distance(a: LocalTime, b: LocalTime): Duration =
    val minutes = Math.abs(a.toSecondOfDay - b.toSecondOfDay) / 60
    Duration.ofMinutes(Math.min(minutes, 24 * 60 - minutes).toLong)
