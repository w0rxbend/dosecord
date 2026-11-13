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

  /** Floored, and 100 only when the goal is reached (VISUALS section 6.9). */
  def percent: Int = if goalMl <= 0 then 0 else StrictMath.min(100, volumeMl * 100 / goalMl)

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
