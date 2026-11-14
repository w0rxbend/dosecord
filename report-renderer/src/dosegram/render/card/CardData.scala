package dosegram.render.card

import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.chart.DoseStatus

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

// The view models of the cards: plain, immutable snapshots with no behaviour beyond derived counts. Builders in the
// application layer fill them from records; this module only ever sees synthetic or already-authorised values.

/** Water logged against the user's own goal, in millilitres. */
final case class WaterProgress(volumeMl: Int, goalMl: Int):
  def share: Double = if goalMl <= 0 then 0.0 else StrictMath.min(1.0, volumeMl.toDouble / goalMl)
  def isGoalReached: Boolean = goalMl > 0 && volumeMl >= goalMl

  /** Floored, so it is 100 only when the goal is reached; above the goal it is the true number, while the vessel is
    * drawn full at `share` (PRODUCT_SPEC R-HYD-5, ROADMAP conflict X-40).
    */
  def percent: Int = if goalMl <= 0 then 0 else (volumeMl.toLong * 100 / goalMl).toInt

final case class MealProgress(logged: Int, planned: Int):
  def share: Double = if planned <= 0 then 0.0 else StrictMath.min(1.0, logged.toDouble / planned)

/** What is known about one night's sleep. A passive quiet window is a bound, not a duration, so it has no minutes. */
enum SleepEvidence:
  case Recorded(minutes: Int, goalMinutes: Int)
  case QuietWindowOnly
  case NoRecord

/** IMG-03. A domain that is `None` is not tracked today and has no row and no halo segment (VA-BL-05). */
final case class DayData(
    date: LocalDate,
    mood: Option[MoodReading],
    medication: Option[Vector[DoseStatus]],
    water: Option[WaterProgress],
    food: Option[MealProgress],
    sleep: Option[SleepEvidence]
)

/** IMG-08. */
final case class WaterData(
    date: LocalDate,
    progress: WaterProgress,
    entryCount: Int,
    lastEntry: Option[LocalTime],
    usesDrinkFactors: Boolean
)

/** The record of one local day in the adherence calendar (VISUALS section 6.7). */
enum DayAdherence:
  /** Resolved doses of the day; `hasMissed` separates "none taken, all skipped" from "none taken, some missed". */
  case Resolved(taken: Int, resolved: Int, hasMissed: Boolean)
  case NotKnown
  case Open
  case NothingScheduled

/** IMG-06. `medicationName` is set when the calendar is filtered to one medication. */
final case class CalendarData(
    month: YearMonth,
    weekStart: DayOfWeek,
    today: Option[LocalDate],
    medicationName: Option[String],
    days: Vector[DayAdherence],
    unknownDoseCount: Int
):
  require(days.size == month.lengthOfMonth, s"${days.size} days given for $month")

/** One medication of the strip: its name, and for each day the ordered statuses of that day's occurrences. */
final case class MedicationRow(name: String, isAsNeeded: Boolean, days: Vector[Vector[DoseStatus]])

/** IMG-07. */
final case class StripData(from: LocalDate, dayCount: Int, rows: Vector[MedicationRow]):
  require(rows.forall(_.days.size == dayCount), "every row needs one entry per day")

final case class MealDay(mainMeals: Int, snacks: Int)

/** The main meals of the plate (IMG-09), in its fixed order: breakfast top left, lunch top right, dinner bottom left.
  */
enum MainMeal:
  case Breakfast, Lunch, Dinner

/** IMG-09, `card.food.day.v1`: which meals of a day are logged, each main meal at the time of its first entry, and the
  * snacks counted. `progress` counts the planned main meals with an entry; with no plan it has `planned = 0` and the
  * day is a count, never a rate. No meal word, photo or number of energy is part of it.
  */
final case class FoodDayData(
    date: LocalDate,
    mains: Map[MainMeal, LocalTime],
    snacks: Int,
    progress: MealProgress
)

/** IMG-04. Every series has seven entries, from `from`. A domain that is `None` is not tracked and is absent; a day
  * that is `None` has no record and is drawn as a gap (VA-PR-03).
  */
final case class WeekData(
    from: LocalDate,
    medication: Option[Vector[Vector[DoseStatus]]],
    water: Option[Vector[Option[WaterProgress]]],
    food: Option[Vector[Option[MealDay]]],
    mood: Option[Vector[Option[MoodState]]],
    sleep: Option[Vector[SleepEvidence]]
)

/** The closed list of milestone kinds (VISUALS section 7.3): acts of logging in a non-medication domain, and the first
  * year of records. No kind reads medication or a measured value (VA-IMG-14a).
  */
enum MilestoneKind:
  case WaterEntries, MealsLogged, MoodCheckIns, SleepCheckIns, FirstYear

/** IMG-14. */
final case class MilestoneData(kind: MilestoneKind, count: Int)

/** IMG-12: one episode on the 18:00 to 18:00 axis of its sleep day, in minutes after 18:00 of the evening before. */
final case class SleepNightBar(date: LocalDate, fromMinute: Int, toMinute: Int, isNap: Boolean)

/** IMG-12, `card.sleep.regularity.v1`: the episodes of a window and the figures under them. A figure is absent below
  * its gate. The typical times are clock minutes after local midnight. Every episode here is the user's own entry.
  */
final case class SleepRegularityData(
    from: LocalDate,
    to: LocalDate,
    bars: Vector[SleepNightBar],
    nights: Int,
    averageMinutes: Option[Int],
    bedtimeSpreadMinutes: Option[Int],
    wakeSpreadMinutes: Option[Int],
    typicalBedtime: Option[Int],
    typicalWake: Option[Int],
    goalMinutes: Option[Int]
)
