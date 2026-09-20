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

  /** Pause/Resume toggle line (M1.9): `Paused since Tue [Resume]`. */
  def pausedSince(day: String): String = s"Paused since $day"

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
        CopyEntry("menu.medications.paused_since", pausedSince("Tue"))
      )
end MenuCopy
