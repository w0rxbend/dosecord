package dosecord.core.domain.copy

/** Menu copy (ROADMAP M1.4a recorded decision; tree per docs/MEDICATION_REMINDER_UX.md and ROADMAP M1.9). */
object MenuCopy:

  val mainPrompt = "What would you like to do?"

  /** Recorded decision: the top menu is exactly these six entries, in this order. */
  val topLevel: List[String] = List("Today", "Medications", "Habits", "Reminders", "Stats", "Account")

  /** Medications submenu (M1.9). Unshipped entries are hidden by flag, never shown as "under development". */
  val medicationsTodaysDoses = "Today's doses"
  val medicationsAdd = "Add medication"
  val medicationsLogDose = "Log dose"
  val medicationsPause = "Pause"
  val medicationsResume = "Resume"
  val medicationsSettings = "Settings"
  val medicationsHistory = "History"

  /** Submenu prompts and empty states. */
  val medicationsPrompt = "Medications"
  val accountPrompt = "Account"
  val menuLabel = "Menu"
  val medicationsEmpty = "No medications yet."
  val todayEmpty = "Nothing scheduled for today."
  def todayMore(count: Int): String = s"+$count more — see /history."
  val historyTitle = "Last 7 days"
  val historyEmpty = "No doses in the last 7 days."
  val settingsTitle = "Settings"
  val settingsIntro = "Reminder options for each medication:"

  /** Pause/Resume toggle line (M1.9): `Paused since Tue [Resume]`. */
  def pausedSince(day: String): String = s"Paused since $day"

  /** Per-medication lifecycle outcomes (the toggle re-renders the submenu). */
  def pausedConfirm(name: String): String = s"Paused $name — no reminders until you resume."
  def resumedConfirm(name: String, next: String): String = s"Resumed $name — next dose $next."
  def resumedConfirmNoNext(name: String): String = s"Resumed $name."
  def archiveConfirmPrompt(name: String): String = s"Archive $name? Its schedule stops; history is kept."
  def archivedConfirm(name: String): String = s"Archived $name."

  /** The reminder-policy line of Medications -> Settings (ADR-012 defaults, read-mostly). */
  def settingsPolicyLine(name: String, offset: String, snoozes: String, missAfterMinutes: Int): String =
    s"$name — reminders $offset; snooze $snoozes; marked missed after $missAfterMinutes min without a response."
  val policyOffsetAtDoseTime = "at dose time"
  def policyOffsetBefore(minutes: Int): String = s"$minutes min before"
  def policyOffsetAfter(minutes: Int): String = s"$minutes min after if unanswered"
  def snoozeList(minutes: List[Int]): String = minutes.map(m => s"${m}m").mkString(", ")

  /** `/today` row status phrases. */
  val statusScheduled = "scheduled"
  val statusDue = "due now"
  def statusSnoozedUntil(time: String): String = s"snoozed until $time"
  def statusTakenAt(time: String): String = s"taken at $time"
  def statusSkippedAt(time: String): String = s"skipped at $time"
  val statusMissed = "missed"
  val statusUnknown = "unknown"
  val takenLateSuffix = "late"
  def asNeededRow(name: String): String = s"$name — as needed"
  def logDoseLabel(name: String): String = s"Log $name"

  val entries: List[CopyEntry] =
    (CopyEntry("menu.main.prompt", mainPrompt) +: topLevel.map(l => CopyEntry(s"menu.main.${l.toLowerCase}", l))) ++
      List(
        CopyEntry("menu.medications.todays_doses", medicationsTodaysDoses),
        CopyEntry("menu.medications.add", medicationsAdd),
        CopyEntry("menu.medications.log_dose", medicationsLogDose),
        CopyEntry("menu.medications.pause", medicationsPause),
        CopyEntry("menu.medications.resume", medicationsResume),
        CopyEntry("menu.medications.settings", medicationsSettings),
        CopyEntry("menu.medications.history", medicationsHistory),
        CopyEntry("menu.medications.paused_since", pausedSince("Tue")),
        CopyEntry("menu.medications.prompt", medicationsPrompt),
        CopyEntry("menu.account.prompt", accountPrompt),
        CopyEntry("menu.nav.label", menuLabel),
        CopyEntry("menu.medications.empty", medicationsEmpty),
        CopyEntry("menu.today.empty", todayEmpty),
        CopyEntry("menu.today.more", todayMore(3)),
        CopyEntry("menu.history.title", historyTitle),
        CopyEntry("menu.history.empty", historyEmpty),
        CopyEntry("menu.settings.title", settingsTitle),
        CopyEntry("menu.settings.intro", settingsIntro),
        CopyEntry(
          "menu.settings.policy_line",
          settingsPolicyLine("Vitamin D", policyOffsetAtDoseTime, snoozeList(List(10, 30, 60)), 120)
        ),
        CopyEntry("menu.settings.offset_at_dose_time", policyOffsetAtDoseTime),
        CopyEntry("menu.settings.offset_before", policyOffsetBefore(5)),
        CopyEntry("menu.settings.offset_after", policyOffsetAfter(10)),
        CopyEntry("menu.settings.snooze_list", snoozeList(List(10, 30, 60))),
        CopyEntry("menu.status.scheduled", statusScheduled),
        CopyEntry("menu.status.due", statusDue),
        CopyEntry("menu.status.snoozed", statusSnoozedUntil("09:10")),
        CopyEntry("menu.status.taken", statusTakenAt("09:03")),
        CopyEntry("menu.status.skipped", statusSkippedAt("12:00")),
        CopyEntry("menu.status.missed", statusMissed),
        CopyEntry("menu.status.unknown", statusUnknown),
        CopyEntry("menu.status.taken_late", takenLateSuffix),
        CopyEntry("menu.today.as_needed_row", asNeededRow("Vitamin C")),
        CopyEntry("menu.today.log_dose", logDoseLabel("Vitamin C")),
        CopyEntry("menu.medications.paused_confirm", pausedConfirm("Vitamin D")),
        CopyEntry("menu.medications.resumed_confirm", resumedConfirm("Vitamin D", "Tue 09:00")),
        CopyEntry("menu.medications.resumed_no_next", resumedConfirmNoNext("Vitamin D")),
        CopyEntry("menu.medications.archive_prompt", archiveConfirmPrompt("Vitamin D")),
        CopyEntry("menu.medications.archived_confirm", archivedConfirm("Vitamin D"))
      )
end MenuCopy
