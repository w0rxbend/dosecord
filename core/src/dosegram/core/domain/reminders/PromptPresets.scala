package dosegram.core.domain.reminders

import java.time.DayOfWeek
import java.time.LocalTime

/** One choice of the preset catalogue: a plan for one prompt kind. `ownCap` marks a plan the user chose whose own slot
  * count is its daily cap: its prompts do not draw on the shared daily budget of R-SCH-22 (owner decision of
  * 2026-11-13, ADR-023). Every other plan draws on the budget.
  */
final case class PromptPreset(id: String, kind: PromptKind, plan: NudgePlan, ownCap: Boolean)

/** The preset catalogue of the non-medication prompts (ADR-023). Presets refer to the anchors of the day profile where
  * the user's day decides the time, so changing the day moves the prompts. Off is the absence of a plan.
  */
object PromptPresets:

  private def at(hour: Int, minute: Int = 0): TimeRef = TimeRef.At(LocalTime.of(hour, minute))

  /** Every 30 minutes on work days, from the start of work until half an hour before its end: with 09:00 to 18:00 the
    * slots are 09:30 to 17:00, sixteen a day. The owner's choice for himself.
    */
  val WaterWorkHours: PromptPreset = PromptPreset(
    "water.work_hours_30",
    PromptKind.Water,
    NudgePlan.Interval(
      TimeRef.From(Anchor.WorkStart, 0),
      TimeRef.From(Anchor.WorkEnd, -30),
      30,
      DaySet.WorkDays,
      onlyWhenBehind = false
    ),
    ownCap = true
  )

  /** Every hour from 08:00 to 21:00: slots 09:00 to 20:00. */
  val WaterHourly: PromptPreset = PromptPreset(
    "water.hourly",
    PromptKind.Water,
    NudgePlan.Interval(at(8), at(21), 60, DaySet.Every, onlyWhenBehind = false),
    ownCap = true
  )

  /** Four a day at the anchors: after waking, at lunch, mid-afternoon and at dinner. Inside the default budget. */
  val WaterGentle: PromptPreset = PromptPreset(
    "water.gentle",
    PromptKind.Water,
    NudgePlan.AtTimes(
      List(
        TimeRef.From(Anchor.Wake, 30),
        TimeRef.From(Anchor.Lunch, 0),
        TimeRef.From(Anchor.Lunch, 180),
        TimeRef.From(Anchor.Dinner, 0)
      ),
      DaySet.Every
    ),
    ownCap = false
  )

  /** Hourly from 08:00 to 21:00, but only while the day's total is behind the user's own goal at that point of the
    * window. Without a goal nothing is sent.
    */
  val WaterBehindGoal: PromptPreset = PromptPreset(
    "water.behind_goal",
    PromptKind.Water,
    NudgePlan.Interval(at(8), at(21), 60, DaySet.Every, onlyWhenBehind = true),
    ownCap = true
  )

  /** A custom interval and window chosen by the user. */
  def waterCustom(plan: NudgePlan.Interval): PromptPreset =
    PromptPreset(WaterCustomId, PromptKind.Water, plan.copy(onlyWhenBehind = false), ownCap = true)

  val WaterCustomId = "water.custom"

  val MoodEvening: PromptPreset = PromptPreset(
    "mood.evening",
    PromptKind.Mood,
    NudgePlan.AtTimes(List(TimeRef.From(Anchor.Dinner, 60)), DaySet.Every),
    ownCap = false
  )

  val MoodMiddayEvening: PromptPreset = PromptPreset(
    "mood.midday_evening",
    PromptKind.Mood,
    NudgePlan.AtTimes(List(TimeRef.From(Anchor.Lunch, 30), TimeRef.From(Anchor.Dinner, 60)), DaySet.Every),
    ownCap = false
  )

  val MoodRandom: PromptPreset = PromptPreset(
    "mood.random3",
    PromptKind.Mood,
    NudgePlan.RandomMoments(at(10), at(20), 3, DaySet.Every),
    ownCap = false
  )

  /** 30 minutes before bedtime. */
  val SleepBedtime: PromptPreset = PromptPreset(
    "sleep.bedtime",
    PromptKind.SleepBedtime,
    NudgePlan.AtTimes(List(TimeRef.From(Anchor.Bedtime, -30)), DaySet.Every),
    ownCap = false
  )

  /** "I'm up" at wake time. */
  val SleepMorning: PromptPreset = PromptPreset(
    "sleep.morning",
    PromptKind.SleepMorning,
    NudgePlan.AtTimes(List(TimeRef.From(Anchor.Wake, 0)), DaySet.Every),
    ownCap = false
  )

  /** A prompt at each meal anchor, sent only when that meal is not logged yet that day (ROADMAP G8.4). Inside the
    * shared budget, silent like every prompt.
    */
  val MealAnchors: PromptPreset = PromptPreset(
    "meal.anchors",
    PromptKind.Meal,
    NudgePlan.AtTimes(
      List(TimeRef.From(Anchor.Breakfast, 0), TimeRef.From(Anchor.Lunch, 0), TimeRef.From(Anchor.Dinner, 0)),
      DaySet.Every
    ),
    ownCap = false
  )

  /** The weekly summary on Sunday evening, of the seven days that end that day. */
  val WeeklyReport: PromptPreset =
    PromptPreset("report.weekly", PromptKind.WeeklyReport, NudgePlan.Weekly(DayOfWeek.SUNDAY, at(19)), ownCap = false)

  /** The monthly summary on the 1st, of the previous calendar month. */
  val MonthlyReport: PromptPreset =
    PromptPreset("report.monthly", PromptKind.MonthlyReport, NudgePlan.Monthly(1, at(10)), ownCap = false)

  val all: List[PromptPreset] = List(
    WaterWorkHours,
    WaterHourly,
    WaterGentle,
    WaterBehindGoal,
    MoodEvening,
    MoodMiddayEvening,
    MoodRandom,
    SleepBedtime,
    SleepMorning,
    MealAnchors,
    WeeklyReport,
    MonthlyReport
  )

  /** The kinds whose prompts are sent today. */
  val wired: Set[PromptKind] = Set(
    PromptKind.Water,
    PromptKind.Mood,
    PromptKind.SleepBedtime,
    PromptKind.SleepMorning,
    PromptKind.Meal,
    PromptKind.WeeklyReport,
    PromptKind.MonthlyReport
  )

  def byId(id: String): Option[PromptPreset] = all.find(_.id == id)

  def forKind(kind: PromptKind): List[PromptPreset] = all.filter(_.kind == kind)

  /** The index of a preset in [[all]]: the value of a button that applies it. */
  def indexOf(preset: PromptPreset): Int = all.indexOf(preset)
