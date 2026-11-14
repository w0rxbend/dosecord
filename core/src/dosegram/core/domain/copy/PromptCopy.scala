package dosegram.core.domain.copy

import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.reminders.PromptKind

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

/** The copy of the prompts, the preset catalogue and the `/reminders` hub (ADR-023). A prompt reminds the user of what
  * the user set up; it never says how much to drink or comments on an amount.
  */
object PromptCopy:

  // ---------- the prompts ----------

  def waterNudge(day: HydrationDay): String =
    tr(s"Water reminder. ${WaterCopy.card(day)}", s"Нагадування про воду. ${WaterCopy.card(day)}")
  def bedtimeNudge =
    tr("Bedtime reminder. Tap when you go to sleep.", "Нагадування про сон. Натисніть, коли лягатимете спати.")
  def morningNudge = tr("Good morning. Tap when you are up.", "Доброго ранку. Натисніть, коли встанете.")
  def GoingToSleep = tr("Going to sleep", "Лягаю спати")
  def ImUp = tr("I'm up", "Встаю")

  def widened = tr(
    "The last few reminders went unanswered, so they come less often for the rest of today.",
    "Кілька останніх нагадувань лишилися без відповіді, тому до кінця дня вони надходитимуть рідше."
  )
  def pausedToday = tr(
    "Water reminders are paused for the rest of today. They start again tomorrow.",
    "Нагадування про воду призупинено до кінця дня. Завтра вони відновляться."
  )
  def pausedTodayOf(kind: PromptKind): String = tr(
    s"${kindName(kind)} reminders are paused for the rest of today.",
    s"Нагадування «${kindName(kind)}» призупинено до кінця дня."
  )

  def Snooze1h = tr("Snooze 1 h", "Відкласти на 1 год")
  def PauseToday = tr("Pause for today", "Пауза до кінця дня")
  def logged(millilitres: Int, day: HydrationDay): String =
    tr(s"Logged $millilitres ml. ${WaterCopy.card(day)}", s"Записано $millilitres мл. ${WaterCopy.card(day)}")
  def snoozed(until: String): String =
    tr(s"Snoozed. The next reminder comes after $until.", s"Відкладено. Наступне нагадування надійде після $until.")
  def pausedFor(kind: PromptKind): String =
    tr(
      s"${kindName(kind)} reminders are paused for the rest of today. They start again tomorrow.",
      s"Нагадування «${kindName(kind)}» призупинено до кінця дня. Завтра вони відновляться."
    )
  def alreadyClosed = tr("This reminder is already closed.", "Це нагадування вже закрито.")
  def sleepRecorded(label: String, at: String): String = tr(s"Recorded: $label, $at.", s"Записано: $label, $at.")

  // ---------- the hub ----------

  def hubTitle = tr("Reminders", "Нагадування")
  def hubLine(kind: PromptKind, preset: Option[String], next: Option[String]): String =
    val state = preset match
      case None       => tr("off", "вимкнено")
      case Some(name) => next.fold(name)(at => tr(s"$name, next $at", s"$name, наступне о $at"))
    s"${kindName(kind)}: $state"
  def medicationLine(count: Int): String =
    if count == 0 then
      tr("Medication: no schedules. Add one in /meds.", "Ліки: розкладів немає. Додайте розклад у /meds.")
    else
      tr(
        s"Medication: ${plural(count, "schedule")}, set in /meds.",
        s"Ліки: ${UkNouns.Schedules.count(count)}, налаштовуються в /meds."
      )
  def dayLine(work: String, quiet: String): String =
    tr(s"Work hours $work. Quiet hours $quiet.", s"Робочі години: $work. Тихі години: $quiet.")
  def pausedAllUntil(until: String): String = tr(
    s"All reminders except medication are paused until $until.",
    s"Усі нагадування, крім ліків, призупинено до $until."
  )
  def pauseAllNote = tr("Medication reminders are not affected.", "Нагадувань про ліки це не стосується.")
  def AddFromPreset = tr("Add from preset", "Додати з готових")
  def DayProfile = tr("Day and hours", "День і години")
  def Pause(hours: Int): String = tr(s"Pause $hours h", s"Пауза на $hours год")
  def Resume = tr("Resume", "Відновити")
  def Back = tr("Back", "Назад")
  def TurnOff = tr("Turn off", "Вимкнути")
  def TurnOn = tr("Turn on", "Увімкнути")
  def Off = tr("Off", "Вимкнено")
  def kindButton(kind: PromptKind): String = kindName(kind)

  def choosePreset(kind: PromptKind): String =
    tr(
      s"${kindName(kind)} reminders. Pick one; you see the times before it starts.",
      s"Нагадування «${kindName(kind)}». Виберіть варіант — час буде видно ще до початку."
    )
  def preview(name: String, times: List[String]): String =
    if times.isEmpty then
      tr(
        s"$name. No reminder falls in the next days with your current day and hours.",
        s"$name. З вашим поточним розкладом дня найближчими днями нагадувань не буде."
      )
    else tr(s"$name. Next: ${times.mkString(", ")}.", s"$name. Наступні: ${times.mkString(", ")}.")
  def applied(name: String): String = tr(s"$name is on.", s"«$name» увімкнено.")
  def turnedOff(kind: PromptKind): String =
    tr(s"${kindName(kind)} reminders are off.", s"Нагадування «${kindName(kind)}» вимкнено.")
  def needsConsent = tr(
    "Turn the domain on first: use its command once, for example /water or /mood.",
    "Спершу ввімкніть розділ: скористайтеся його командою, наприклад /water чи /mood."
  )
  def notWired = tr("This kind of reminder is not available yet.", "Такі нагадування поки недоступні.")

  def dayProfile(lines: List[String]): String = (tr("Your day", "Ваш день") :: lines).mkString("\n")
  def anchorLine(name: String, at: LocalTime): String = s"$name: ${clock(at)}"
  def dayProfileHelp =
    tr(
      "Change a time by sending, for example: /reminders wake 06:30, /reminders work mon-fri 09:00-18:00, " +
        "/reminders quiet 22:00-07:00. Every reminder that uses that time moves with it.",
      "Щоб змінити час, надішліть, наприклад: /reminders wake 06:30, /reminders work mon-fri 09:00-18:00, " +
        "/reminders quiet 22:00-07:00. Усі нагадування, що залежать від цього часу, зсунуться разом із ним."
    )
  def dayUsage =
    tr(
      "Send for example /reminders wake 06:30, /reminders work mon-fri 09:00-18:00 or /reminders quiet 22:00-07:00.",
      "Надішліть, наприклад, /reminders wake 06:30, /reminders work mon-fri 09:00-18:00 або /reminders quiet 22:00-07:00."
    )
  def daySaved = tr(
    "Saved. Reminders that use this time have moved.",
    "Збережено. Нагадування, що залежать від цього часу, зсунулися."
  )
  def workOrder = tr(
    "Work hours must start before they end, on at least one day.",
    "Робочі години мають починатися раніше, ніж закінчуються, хоча б в один день."
  )

  def offerWater = tr(
    "Would you like water reminders? Pick a plan, or none. You can change it any time in /reminders.",
    "Бажаєте отримувати нагадування про воду? Виберіть план або відмовтеся. Змінити це можна будь-коли в /reminders."
  )
  def NotNow = tr("Not now", "Не зараз")

  // ---------- names ----------

  def kindName(kind: PromptKind): String = kind match
    case PromptKind.Water         => tr("Water", "Вода")
    case PromptKind.Mood          => tr("Mood", "Настрій")
    case PromptKind.SleepBedtime  => tr("Bedtime", "Відхід до сну")
    case PromptKind.SleepMorning  => tr("Morning", "Ранок")
    case PromptKind.Meal          => tr("Meals", "Їжа")
    case PromptKind.WeeklyReport  => tr("Weekly summary", "Тижневий підсумок")
    case PromptKind.MonthlyReport => tr("Monthly summary", "Місячний підсумок")

  /** The name of each preset of the catalogue, by id. */
  def presetName(id: String): String = id match
    case "water.work_hours_30" => tr("Work hours, every 30 min", "Робочі години, кожні 30 хв")
    case "water.hourly"        => tr("Every hour, 08:00 to 21:00", "Щогодини, з 08:00 до 21:00")
    case "water.gentle"        => tr("Gentle: 4 a day at meal times", "М’яко: 4 рази на день під час їжі")
    case "water.behind_goal"   => tr("Only when behind my goal", "Лише коли я відстаю від цілі")
    case "water.custom"        => tr("Custom interval", "Свій інтервал")
    case "mood.evening"        => tr("Once in the evening", "Раз увечері")
    case "mood.midday_evening" => tr("Midday and evening", "Удень і ввечері")
    case "mood.random3"        => tr("Three moments, 10:00 to 20:00", "Тричі, з 10:00 до 20:00")
    case "sleep.bedtime"       => tr("30 min before bedtime", "За 30 хв до сну")
    case "sleep.morning"       => tr("At wake time", "У час пробудження")
    case "meal.anchors"        => tr("At meal times, if not logged", "Під час їжі, якщо не записано")
    case "report.weekly"       => tr("Sunday evening", "Неділя ввечері")
    case "report.monthly"      => tr("On the 1st", "Першого числа")
    case "med.morning"         => tr("Once in the morning", "Раз уранці")
    case "med.evening"         => tr("Once in the evening", "Раз увечері")
    case "med.twice_daily" => tr("Twice a day, at wake and bedtime", "Двічі на день: після пробудження й перед сном")
    case "med.three_with_meals" => tr("Three times a day, with meals", "Тричі на день, під час їжі")
    case "med.every_8h"         => tr("Every 8 hours from wake", "Кожні 8 годин від пробудження")
    case "med.every_12h"        => tr("Every 12 hours from wake", "Кожні 12 годин від пробудження")
    case "med.weekdays"         => tr("Weekdays only", "Лише в будні")
    case "med.weekly"           => tr("Once a week", "Раз на тиждень")
    case "med.every_other_day"  => tr("Every other day", "Через день")
    case "med.cycle"            => tr("Days on, days off", "Дні прийому й перерви")
    case "med.as_needed"        => tr("As needed, no reminder", "За потреби, без нагадування")
    case other                  => other

  def clock(at: LocalTime): String = f"${at.getHour}%02d:${at.getMinute}%02d"

  def days(days: Set[DayOfWeek]): String =
    val sorted = days.toList.sortBy(_.getValue)
    val names = sorted.map(_.getDisplayName(TextStyle.SHORT, Formats.locale))
    val consecutive = sorted.size > 2 && sorted.map(_.getValue).sliding(2).forall(p => p(1) == p(0) + 1)
    if consecutive then tr(s"${names.head} to ${names.last}", s"${names.head}–${names.last}") else names.mkString(", ")

  private def plural(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  private def sampleDay = HydrationDay(totalMl = 750, entries = 3, goalMl = Some(2000))

  def entries: List[CopyEntry] = List(
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
    CopyEntry(
      "reminders.preview",
      preview(tr("Work hours, every 30 min", "Робочі години, кожні 30 хв"), List("09:30", "10:00"))
    ),
    CopyEntry("reminders.preview.empty", preview(tr("Work hours, every 30 min", "Робочі години, кожні 30 хв"), Nil)),
    CopyEntry("reminders.applied", applied(tr("Work hours, every 30 min", "Робочі години, кожні 30 хв"))),
    CopyEntry("reminders.turned_off", turnedOff(PromptKind.Water)),
    CopyEntry("reminders.needs_consent", needsConsent),
    CopyEntry("reminders.not_wired", notWired),
    CopyEntry("reminders.day_help", dayProfileHelp),
    CopyEntry("reminders.day_usage", dayUsage),
    CopyEntry("reminders.day_saved", daySaved),
    CopyEntry("reminders.work_order", workOrder),
    CopyEntry("reminders.offer_water", offerWater)
  )
