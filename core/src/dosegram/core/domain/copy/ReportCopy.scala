package dosegram.core.domain.copy

import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.reminders.PromptKind

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The scheduled reports (VISUALS_AND_ANALYTICS section 10) and the month and pattern controls of the chat. A report
  * states counts and the user's own previous period; it never urges, never counts down and never names what the user
  * did not do (VA-PS-12).
  */
object ReportCopy:

  def weeklyTitle: String = tr("Your week: the last 7 days", "Ваш тиждень: останні 7 днів")
  def monthlyTitle: String = tr("Your month", "Ваш місяць")
  def StopThese: String = tr("Stop these", "Вимкнути такі")
  def ThisMonth: String = tr("Month", "Місяць")
  def PreviousMonth: String = tr("Previous month", "Попередній місяць")
  def NextMonth: String = tr("Next month", "Наступний місяць")
  def Patterns: String = tr("Patterns", "Закономірності")
  def imageUnavailable: String = tr(
    "No image can be drawn for this month right now. The figures above are complete.",
    "Зараз не вдається створити зображення за цей місяць. Цифри вище повні."
  )

  /** The name of a report type in the plural, as the lines about the type say it. */
  private def summaries(kind: PromptKind): String =
    kind match
      case PromptKind.MonthlyReport => tr("Monthly summaries", "Місячні підсумки")
      case PromptKind.DailyReport   => tr("Morning summaries", "Ранкові підсумки")
      case _                        => tr("Weekly summaries", "Тижневі підсумки")

  /** The text a stopped report's message is edited to. */
  def stopped(kind: PromptKind): String =
    val name = summaries(kind)
    tr(s"$name are off. Turn them on again in /reminders.", s"$name вимкнено. Увімкнути їх знову можна в /reminders.")

  /** The one question of ROADMAP conflict X-19, asked once, at the first eligible week or month (VISUALS 10.1). */
  def offer(kind: PromptKind, days: Int): String =
    if kind == PromptKind.MonthlyReport then
      tr(
        s"Last month has records on $days days. Would you like a monthly summary on the 1st at 10:00? You can change or stop it in /reminders.",
        s"Минулого місяця записи є за $days ${UkNouns.Days(days)}. Надсилати місячний підсумок 1-го числа о 10:00? Змінити чи вимкнути це можна в /reminders."
      )
    else
      tr(
        s"Last week has records on $days days. Would you like a weekly summary on Mondays at 09:00? You can change or stop it in /reminders.",
        s"Минулого тижня записи є за $days ${UkNouns.Days(days)}. Надсилати тижневий підсумок щопонеділка о 09:00? Змінити чи вимкнути це можна в /reminders."
      )
  def OfferYes: String = tr("Yes, send it", "Так, надсилати")
  def OfferNo: String = tr("No, thanks", "Ні, дякую")

  def offerTaken(kind: PromptKind): String =
    if kind == PromptKind.MonthlyReport then
      tr("Monthly summaries are on: the 1st at 10:00.", "Місячні підсумки увімкнено: 1-го числа о 10:00.")
    else tr("Weekly summaries are on: Mondays at 09:00.", "Тижневі підсумки увімкнено: щопонеділка о 09:00.")

  def offerDeclined(kind: PromptKind): String =
    val name = if kind == PromptKind.MonthlyReport then tr("No monthly summary", "Місячного підсумку не буде")
    else tr("No weekly summary", "Тижневого підсумку не буде")
    tr(s"$name. You can turn it on in /reminders.", s"$name. Увімкнути його можна в /reminders.")

  /** VA-PS-06: the line a paused report type leaves, with Resume. It states the pause, never what the user did not do.
    */
  def paused(kind: PromptKind): String =
    val name = summaries(kind)
    tr(
      s"$name are paused for now. Tap Resume to get them again.",
      s"$name поки призупинено. Натисніть «Відновити», щоб отримувати їх знову."
    )
  def Resume: String = tr("Resume", "Відновити")

  def resumed(kind: PromptKind): String =
    val name = summaries(kind)
    tr(s"$name are on again.", s"$name знову увімкнено.")

  // ---------- the morning summary (supervisor decision, 2026-11-27) ----------

  private def weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Formats.locale)

  /** The first line of the morning summary: the day it is about, by its weekday and date ("Mon 21 Sep", "пн 21
    * вересня").
    */
  def dailyTitle(yesterday: LocalDate): String =
    val day = s"${weekday.format(yesterday)} ${Formats.dayMonth.format(yesterday)}"
    tr(s"Yesterday, $day", s"Учора, $day")

  /** Yesterday's water: against the user's own goal when there is one, else the amount alone. */
  def dayWater(day: HydrationDay): String =
    day.progress match
      case Some(progress) =>
        tr(
          s"Water: ${StatsCopy.dayLitres(progress.totalMl)} of ${StatsCopy.dayLitres(progress.goalMl)} l (${progress.percent}%)",
          s"Вода: ${StatsCopy.dayLitres(progress.totalMl)} з ${StatsCopy.dayLitres(progress.goalMl)} л (${progress.percent}%)"
        )
      case None => tr(s"Water: ${StatsCopy.dayLitres(day.totalMl)} l", s"Вода: ${StatsCopy.dayLitres(day.totalMl)} л")

  /** Yesterday's mood: the mean of the check-ins with its level name, and how many there were. */
  def dayMood(day: MoodDay): String =
    tr(
      s"Mood: ${StatsCopy.meanWithLevel(day.mean)} · ${StatsCopy.count(day.n, "check-in")}",
      s"Настрій: ${StatsCopy.meanWithLevel(day.mean)} · ${UkNouns.CheckIns.count(day.n)}"
    )

  /** The night that ended yesterday, from what the user entered. */
  def daySleep(night: SleepNight): String =
    tr(
      s"Sleep: ${StatsCopy.duration(night.minutes)} (${StatsCopy.SleepSource})",
      s"Сон: ${StatsCopy.duration(night.minutes)} (${StatsCopy.SleepSource})"
    )

  def entries: List[CopyEntry] = List(
    CopyEntry("report.offer.weekly", offer(PromptKind.WeeklyReport, 4)),
    CopyEntry("report.offer.monthly", offer(PromptKind.MonthlyReport, 16)),
    CopyEntry("report.offer.yes", OfferYes),
    CopyEntry("report.offer.no", OfferNo),
    CopyEntry("report.offer.taken.weekly", offerTaken(PromptKind.WeeklyReport)),
    CopyEntry("report.offer.taken.monthly", offerTaken(PromptKind.MonthlyReport)),
    CopyEntry("report.offer.declined.weekly", offerDeclined(PromptKind.WeeklyReport)),
    CopyEntry("report.offer.declined.monthly", offerDeclined(PromptKind.MonthlyReport)),
    CopyEntry("report.paused.weekly", paused(PromptKind.WeeklyReport)),
    CopyEntry("report.paused.monthly", paused(PromptKind.MonthlyReport)),
    CopyEntry("report.resume", Resume),
    CopyEntry("report.resumed.weekly", resumed(PromptKind.WeeklyReport)),
    CopyEntry("report.weekly.title", weeklyTitle),
    CopyEntry("report.monthly.title", monthlyTitle),
    CopyEntry("report.stop", StopThese),
    CopyEntry("report.stopped.weekly", stopped(PromptKind.WeeklyReport)),
    CopyEntry("report.stopped.monthly", stopped(PromptKind.MonthlyReport)),
    CopyEntry("report.month", ThisMonth),
    CopyEntry("report.month.previous", PreviousMonth),
    CopyEntry("report.month.next", NextMonth),
    CopyEntry("report.patterns", Patterns),
    CopyEntry("report.month.image_unavailable", imageUnavailable),
    CopyEntry("report.daily.title", dailyTitle(LocalDate.parse("2026-11-26"))),
    CopyEntry("report.daily.water", dayWater(HydrationDay(1400, 5, Some(2000)))),
    CopyEntry("report.daily.water.no_goal", dayWater(HydrationDay(1400, 5, None))),
    CopyEntry("report.daily.mood", dayMood(MoodDay(2, 7, 3, 4, energyN = 0, energySum = 0))),
    CopyEntry("report.daily.sleep", daySleep(SleepNight(430, None))),
    CopyEntry("report.stopped.daily", stopped(PromptKind.DailyReport)),
    CopyEntry("report.paused.daily", paused(PromptKind.DailyReport)),
    CopyEntry("report.resumed.daily", resumed(PromptKind.DailyReport))
  )
end ReportCopy
