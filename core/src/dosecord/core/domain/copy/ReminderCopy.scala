package dosecord.core.domain.copy

import dosecord.core.domain.Refusal

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

  /** Correction follow-up (M1.10): `Corrected — logged as taken at 09:00.` */
  def correctedConfirmation(time: String): String = s"Corrected — logged as taken at $time."

  /** [Keep missed] on the missed notice (M1.10): the row stays missed, the controls die. */
  val keepMissedConfirmation: String = "Okay — left marked missed."

  /** [Cancel] on the correction prompt (M1.10). */
  val correctionCancelled: String = "No change."

  /** The refusal toasts of the one-tap handlers (M1.10); the two correction-prompt refusals open the flow instead. */
  def refusal(reason: Refusal): String =
    reason match
      case Refusal.NothingToUndo                => "There is nothing to undo."
      case Refusal.UndoWindowPassed             => "The undo window has passed."
      case Refusal.UndoWindowPassedOfferCorrect => undoWindowPassed
      case Refusal.CorrectionPrompt             => correctionPrompt
      case Refusal.AlreadyResolved              => "This dose is already recorded."
      case Refusal.SnoozeLimitReached           => "No snoozes left for this dose."
      case Refusal.SnoozePastBound              => "That snooze would run past the next dose."
      case Refusal.SnoozeNotAllowed             => "This dose cannot be snoozed."
      case Refusal.CorrectOnOpenRow             => "This dose is still open — Taken or Skip applies."
      case Refusal.InvalidEffectiveAt           => "That time is in the future."
      case Refusal.RowCancelled                 => "This dose is cancelled."

  /** The universal commands' resolution failures (M1.10). */
  val nothingDue: String = "Nothing is due right now."
  def noOpenDose(name: String): String = s"No open dose for $name."
  def unknownMedication(name: String): String = s"I could not find $name."

  /** Medications -> Log dose / `/log` without a medication (M1.10). */
  val logPickerPrompt: String = "Log a dose of which medication?"
  val logEmpty: String = "No medications to log yet."

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
    CopyEntry("reminder.corrected", correctedConfirmation("09:00")),
    CopyEntry("reminder.keep_missed", keepMissedConfirmation),
    CopyEntry("reminder.correction_cancelled", correctionCancelled),
    CopyEntry("reminder.refusal.nothing_to_undo", refusal(Refusal.NothingToUndo)),
    CopyEntry("reminder.refusal.undo_window_passed", refusal(Refusal.UndoWindowPassed)),
    CopyEntry("reminder.refusal.already_resolved", refusal(Refusal.AlreadyResolved)),
    CopyEntry("reminder.refusal.snooze_limit", refusal(Refusal.SnoozeLimitReached)),
    CopyEntry("reminder.refusal.snooze_past_bound", refusal(Refusal.SnoozePastBound)),
    CopyEntry("reminder.refusal.snooze_not_allowed", refusal(Refusal.SnoozeNotAllowed)),
    CopyEntry("reminder.refusal.correct_on_open_row", refusal(Refusal.CorrectOnOpenRow)),
    CopyEntry("reminder.refusal.invalid_effective_at", refusal(Refusal.InvalidEffectiveAt)),
    CopyEntry("reminder.refusal.row_cancelled", refusal(Refusal.RowCancelled)),
    CopyEntry("reminder.nothing_due", nothingDue),
    CopyEntry("reminder.no_open_dose", noOpenDose("Vitamin D")),
    CopyEntry("reminder.unknown_medication", unknownMedication("Vitamin D")),
    CopyEntry("reminder.log_picker_prompt", logPickerPrompt),
    CopyEntry("reminder.log_empty", logEmpty),
    CopyEntry("reminder.unsure_advice", unsureAdvice),
    CopyEntry("reminder.failure_toast", failureToast),
    CopyEntry("reminder.stale_control_toast", staleControlToast)
  )
end ReminderCopy
