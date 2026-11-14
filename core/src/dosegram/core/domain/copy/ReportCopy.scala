package dosegram.core.domain.copy

import dosegram.core.domain.reminders.PromptKind

/** The scheduled reports (VISUALS_AND_ANALYTICS section 10) and the month and pattern controls of the chat. A report
  * states counts and the user's own previous period; it never urges, never counts down and never names what the user
  * did not do (VA-PS-12).
  */
object ReportCopy:

  val weeklyTitle: String = "Your week: the last 7 days"
  val monthlyTitle: String = "Your month"
  val StopThese: String = "Stop these"
  val ThisMonth: String = "Month"
  val PreviousMonth: String = "Previous month"
  val NextMonth: String = "Next month"
  val Patterns: String = "Patterns"
  val imageUnavailable: String = "No image can be drawn for this month right now. The figures above are complete."

  /** The text a stopped report's message is edited to. */
  def stopped(kind: PromptKind): String =
    val name = if kind == PromptKind.MonthlyReport then "Monthly summaries" else "Weekly summaries"
    s"$name are off. Turn them on again in /reminders."

  val entries: List[CopyEntry] = List(
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
