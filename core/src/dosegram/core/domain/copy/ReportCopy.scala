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

  def entries: List[CopyEntry] = List(
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
