package dosegram.core.domain.copy

import dosegram.core.domain.reminders.PromptKind

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

  /** The text a stopped report's message is edited to. */
  def stopped(kind: PromptKind): String =
    val name = if kind == PromptKind.MonthlyReport then tr("Monthly summaries", "Місячні підсумки")
    else tr("Weekly summaries", "Тижневі підсумки")
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
    val name = if kind == PromptKind.MonthlyReport then tr("Monthly summaries", "Місячні підсумки")
    else tr("Weekly summaries", "Тижневі підсумки")
    tr(
      s"$name are paused for now. Tap Resume to get them again.",
      s"$name поки призупинено. Натисніть «Відновити», щоб отримувати їх знову."
    )
  def Resume: String = tr("Resume", "Відновити")

  def resumed(kind: PromptKind): String =
    val name = if kind == PromptKind.MonthlyReport then tr("Monthly summaries", "Місячні підсумки")
    else tr("Weekly summaries", "Тижневі підсумки")
    tr(s"$name are on again.", s"$name знову увімкнено.")

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
    CopyEntry("report.month.image_unavailable", imageUnavailable)
  )
end ReportCopy
