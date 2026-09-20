package dosecord.core.domain.copy

/** Reminder, follow-up, missed, undo and correction copy (ROADMAP M1.4a; lifecycle semantics per ADR-012; wording per
  * docs/MEDICATION_REMINDER_UX.md). Multi-line copy is a list of lines; each line renders as its own paragraph.
  */
object ReminderCopy:

  /** Recorded decision: `Time for Vitamin D, 1000 IU.` with the instructions on a second line when present. */
  def reminderBody(name: String, dose: Option[String], instructions: Option[String]): List[String] =
    val line1 = dose match
      case Some(d) => s"Time for $name, $d."
      case None    => s"Time for $name."
    instructions.toList.foldLeft(List(line1))(_ :+ _)

  /** Discreet mode (DESIGN.md section 4.3): no medication name in the body. */
  def discreetReminderBody(time: String): String = s"Time for your $time dose."

  /** Taken follow-up (finalize summary): `Recorded at 09:03.` */
  def takenConfirmation(time: String): String = s"Recorded at $time."

  /** A second Taken on a resolved occurrence is a semantic no-op (DESIGN.md section 7.3). */
  def alreadyRecorded(time: String): String = s"Already recorded at $time."

  /** Skip follow-up (UX safety copy: neutral). */
  def skippedConfirmation: String = "Marked skipped."

  /** Snooze follow-up: `Okay, I'll remind you again at 09:10.` */
  def snoozeConfirmation(time: String): String = s"Okay, I'll remind you again at $time."

  /** Missed notice (UX spec): neutral statement of fact, controls are `Labels.missedNotice`. */
  def missedNotice(name: String, time: String): String =
    s"I did not get a response for $name at $time, so I marked it missed."

  /** Undo within the 15-minute window returns the occurrence to due (ADR-012). */
  def undoConfirmation(nextReminderTime: String): String = s"Undone. I'll check again at $nextReminderTime."

  /** Past the undo window the two-choice correction is offered instead (M1.10). */
  def undoWindowPassed: String = "The undo window has passed — correct it instead?"

  /** Correction prompt (`dose.correct`, M1.10): [Now][At scheduled time][Cancel]. */
  def correctionPrompt: String = "When did you take it?"

  /** The one safety sentence (UX spec): the bot never advises, it defers to the clinician/pharmacist. */
  def unsureAdvice: String =
    "If you are unsure whether to take a late or missed dose, follow your clinician/pharmacist instructions."

  /** The catalogue failure toast when the database is unavailable (R68: visibly fails). */
  def failureToast: String = "Something went wrong on my side — please try again in a moment."

  /** Toast for a bad MAC or an expired slot (DESIGN.md section 4.4). */
  def staleControlToast: String = "This button is no longer valid."

  val entries: List[CopyEntry] = List(
    CopyEntry("reminder.body", reminderBody("Vitamin D", Some("1000 IU"), None).mkString("\n")),
    CopyEntry(
      "reminder.body.instructions",
      reminderBody("Vitamin D", Some("1000 IU"), Some("with breakfast")).mkString("\n")
    ),
    CopyEntry("reminder.body.no_dose", reminderBody("Vitamin D", None, None).mkString("\n")),
    CopyEntry("reminder.body.discreet", discreetReminderBody("09:00")),
    CopyEntry("reminder.taken", takenConfirmation("09:03")),
    CopyEntry("reminder.already_recorded", alreadyRecorded("09:03")),
    CopyEntry("reminder.skipped", skippedConfirmation),
    CopyEntry("reminder.snoozed", snoozeConfirmation("09:10")),
    CopyEntry("reminder.missed", missedNotice("Vitamin D", "09:00")),
    CopyEntry("reminder.undone", undoConfirmation("11:15")),
    CopyEntry("reminder.undo_window_passed", undoWindowPassed),
    CopyEntry("reminder.correction_prompt", correctionPrompt),
    CopyEntry("reminder.unsure_advice", unsureAdvice),
    CopyEntry("reminder.failure_toast", failureToast),
    CopyEntry("reminder.stale_control_toast", staleControlToast)
  )
end ReminderCopy
