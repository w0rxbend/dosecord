package dosegram.core.domain.copy

import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.reminders.PromptKind

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** The copy of the prompts, the preset catalogue and the `/reminders` hub (ADR-023). A prompt reminds the user of what
  * the user set up; it never says how much to drink or comments on an amount.
  */
object PromptCopy:

  // ---------- the prompts ----------

  def waterNudge(day: HydrationDay): String = s"Water reminder. ${WaterCopy.card(day)}"
  val bedtimeNudge = "Bedtime reminder. Tap when you go to sleep."
  val morningNudge = "Good morning. Tap when you are up."
  val GoingToSleep = "Going to sleep"
  val ImUp = "I'm up"

  val widened = "The last few reminders went unanswered, so they come less often for the rest of today."
  val pausedToday = "Water reminders are paused for the rest of today. They start again tomorrow."
  def pausedTodayOf(kind: PromptKind): String = s"${kindName(kind)} reminders are paused for the rest of today."

  val Snooze1h = "Snooze 1 h"
  val PauseToday = "Pause for today"
  def logged(millilitres: Int, day: HydrationDay): String = s"Logged $millilitres ml. ${WaterCopy.card(day)}"
  def snoozed(until: String): String = s"Snoozed. The next reminder comes after $until."
  def pausedFor(kind: PromptKind): String =
    s"${kindName(kind)} reminders are paused for the rest of today. They start again tomorrow."
  val alreadyClosed = "This reminder is already closed."
  def sleepRecorded(label: String, at: String): String = s"Recorded: $label, $at."

  // ---------- the hub ----------

  val hubTitle = "Reminders"
  def hubLine(kind: PromptKind, preset: Option[String], next: Option[String]): String =
    val state = preset match
      case None       => "off"
      case Some(name) => next.fold(name)(at => s"$name, next $at")
    s"${kindName(kind)}: $state"
  def medicationLine(count: Int): String =
    if count == 0 then "Medication: no schedules. Add one in /meds."
    else s"Medication: ${plural(count, "schedule")}, set in /meds."
  def dayLine(work: String, quiet: String): String = s"Work hours $work. Quiet hours $quiet."
  def pausedAllUntil(until: String): String = s"All reminders except medication are paused until $until."
  val pauseAllNote = "Medication reminders are not affected."
  val AddFromPreset = "Add from preset"
  val DayProfile = "Day and hours"
  def Pause(hours: Int): String = s"Pause $hours h"
  val Resume = "Resume"
  val Back = "Back"
  val TurnOff = "Turn off"
  val TurnOn = "Turn on"
  val Off = "Off"
  def kindButton(kind: PromptKind): String = kindName(kind)

  def choosePreset(kind: PromptKind): String =
    s"${kindName(kind)} reminders. Pick one; you see the times before it starts."
  def preview(name: String, times: List[String]): String =
    if times.isEmpty then s"$name. No reminder falls in the next days with your current day and hours."
    else s"$name. Next: ${times.mkString(", ")}."
  def applied(name: String): String = s"$name is on."
  def turnedOff(kind: PromptKind): String = s"${kindName(kind)} reminders are off."
  val needsConsent = "Turn the domain on first: use its command once, for example /water or /mood."
  val notWired = "This kind of reminder is not available yet."

  def dayProfile(lines: List[String]): String = ("Your day" :: lines).mkString("\n")
  def anchorLine(name: String, at: LocalTime): String = s"$name: ${clock(at)}"
  val dayProfileHelp =
    "Change a time by sending, for example: /reminders wake 06:30, /reminders work mon-fri 09:00-18:00, " +
      "/reminders quiet 22:00-07:00. Every reminder that uses that time moves with it."
  val dayUsage =
    "Send for example /reminders wake 06:30, /reminders work mon-fri 09:00-18:00 or /reminders quiet 22:00-07:00."
  val daySaved = "Saved. Reminders that use this time have moved."
  val workOrder = "Work hours must start before they end, on at least one day."

  val offerWater = "Would you like water reminders? Pick a plan, or none. You can change it any time in /reminders."
  val NotNow = "Not now"

  // ---------- names ----------

  def kindName(kind: PromptKind): String = kind match
    case PromptKind.Water         => "Water"
    case PromptKind.Mood          => "Mood"
    case PromptKind.SleepBedtime  => "Bedtime"
    case PromptKind.SleepMorning  => "Morning"
    case PromptKind.Meal          => "Meals"
    case PromptKind.WeeklyReport  => "Weekly summary"
    case PromptKind.MonthlyReport => "Monthly summary"

  /** The name of each preset of the catalogue, by id. */
  def presetName(id: String): String = id match
    case "water.work_hours_30"  => "Work hours, every 30 min"
    case "water.hourly"         => "Every hour, 08:00 to 21:00"
    case "water.gentle"         => "Gentle: 4 a day at meal times"
    case "water.behind_goal"    => "Only when behind my goal"
    case "water.custom"         => "Custom interval"
    case "mood.evening"         => "Once in the evening"
    case "mood.midday_evening"  => "Midday and evening"
    case "mood.random3"         => "Three moments, 10:00 to 20:00"
    case "sleep.bedtime"        => "30 min before bedtime"
    case "sleep.morning"        => "At wake time"
    case "meal.anchors"         => "At meal times, if not logged"
    case "report.weekly"        => "Sunday evening"
    case "report.monthly"       => "On the 1st"
    case "med.morning"          => "Once in the morning"
    case "med.evening"          => "Once in the evening"
    case "med.twice_daily"      => "Twice a day, at wake and bedtime"
    case "med.three_with_meals" => "Three times a day, with meals"
    case "med.every_8h"         => "Every 8 hours from wake"
    case "med.every_12h"        => "Every 12 hours from wake"
    case "med.weekdays"         => "Weekdays only"
    case "med.weekly"           => "Once a week"
    case "med.every_other_day"  => "Every other day"
    case "med.cycle"            => "Days on, days off"
    case "med.as_needed"        => "As needed, no reminder"
    case other                  => other

  def clock(at: LocalTime): String = f"${at.getHour}%02d:${at.getMinute}%02d"

  def days(days: Set[DayOfWeek]): String =
    val sorted = days.toList.sortBy(_.getValue)
    val names = sorted.map(_.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
    val consecutive = sorted.size > 2 && sorted.map(_.getValue).sliding(2).forall(p => p(1) == p(0) + 1)
    if consecutive then s"${names.head} to ${names.last}" else names.mkString(", ")

  private def plural(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  private val sampleDay = HydrationDay(totalMl = 750, entries = 3, goalMl = Some(2000))

  val entries: List[CopyEntry] = List(
    CopyEntry("prompt.water", waterNudge(sampleDay)),
    CopyEntry("prompt.bedtime", bedtimeNudge),
    CopyEntry("prompt.morning", morningNudge),
    CopyEntry("prompt.going_to_sleep", GoingToSleep),
    CopyEntry("prompt.im_up", ImUp),
    CopyEntry("prompt.widened", widened),
    CopyEntry("prompt.paused_today", pausedToday),
    CopyEntry("prompt.snooze_1h", Snooze1h),
    CopyEntry("prompt.pause_today", PauseToday),
    CopyEntry("prompt.logged", logged(250, sampleDay)),
    CopyEntry("prompt.snoozed", snoozed("15:30")),
    CopyEntry("prompt.paused_for", pausedFor(PromptKind.Water)),
    CopyEntry("prompt.already_closed", alreadyClosed),
    CopyEntry("prompt.sleep_recorded", sleepRecorded(ImUp, "Mon 07:05")),
    CopyEntry("reminders.title", hubTitle),
    CopyEntry("reminders.line", hubLine(PromptKind.Water, Some(presetName("water.work_hours_30")), Some("14:30"))),
    CopyEntry("reminders.line.off", hubLine(PromptKind.Mood, None, None)),
    CopyEntry("reminders.medication", medicationLine(2)),
    CopyEntry("reminders.medication.none", medicationLine(0)),
    CopyEntry("reminders.day", dayLine("Mon to Fri 09:00-18:00", "22:00-07:00")),
    CopyEntry("reminders.paused_all", pausedAllUntil("15:00")),
    CopyEntry("reminders.pause_note", pauseAllNote),
    CopyEntry("reminders.choose", choosePreset(PromptKind.Water)),
    CopyEntry("reminders.preview", preview("Work hours, every 30 min", List("09:30", "10:00"))),
    CopyEntry("reminders.preview.empty", preview("Work hours, every 30 min", Nil)),
    CopyEntry("reminders.applied", applied("Work hours, every 30 min")),
    CopyEntry("reminders.turned_off", turnedOff(PromptKind.Water)),
    CopyEntry("reminders.needs_consent", needsConsent),
    CopyEntry("reminders.not_wired", notWired),
    CopyEntry("reminders.day_help", dayProfileHelp),
    CopyEntry("reminders.day_usage", dayUsage),
    CopyEntry("reminders.day_saved", daySaved),
    CopyEntry("reminders.work_order", workOrder),
    CopyEntry("reminders.offer_water", offerWater)
  )
