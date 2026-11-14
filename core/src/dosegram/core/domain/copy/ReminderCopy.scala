package dosegram.core.domain.copy

import dosegram.core.domain.Refusal

/** Reminder, follow-up, missed, undo and correction copy (ROADMAP M1.4a; lifecycle semantics per ADR-012; wording per
  * docs/MEDICATION_REMINDER_UX.md). Multi-line copy is a list of lines; each line renders as its own paragraph.
  */
object ReminderCopy:

  /** Recorded decision: `Time for Vitamin D, 1000 IU.` with the instructions on a second line when present. */
  def reminderBody(name: String, dose: Option[String], instructions: Option[String]): List[String] =
    val line1 = dose match
      case Some(d) => tr(s"Time for $name, $d.", s"Нагадування: $name, $d.")
      case None    => tr(s"Time for $name.", s"Нагадування: $name.")
    instructions.toList.foldLeft(List(line1))(_ :+ _)

  /** Discreet mode (DESIGN.md section 4.3): no medication name in the body. */
  def discreetReminderBody(time: String): String = tr(s"Time for your $time dose.", s"Нагадування про дозу на $time.")

  /** Taken follow-up (finalize summary): `Recorded at 09:03.` */
  def takenConfirmation(time: String): String = tr(s"Recorded at $time.", s"Записано о $time.")

  /** A second Taken on a resolved occurrence is a semantic no-op (DESIGN.md section 7.3). */
  def alreadyRecorded(time: String): String = tr(s"Already recorded at $time.", s"Уже записано о $time.")

  /** Skip follow-up (UX safety copy: neutral). */
  def skippedConfirmation: String = tr("Marked skipped.", "Позначено як пропущену.")

  /** Snooze follow-up: `Okay, I'll remind you again at 09:10.` */
  def snoozeConfirmation(time: String): String =
    tr(s"Okay, I'll remind you again at $time.", s"Гаразд, нагадаю ще раз о $time.")

  /** Missed notice (UX spec): neutral statement of fact, controls are `Labels.missedNotice`. */
  def missedNotice(name: String, time: String): String =
    tr(
      s"I did not get a response for $name at $time, so I marked it missed.",
      s"На нагадування про $name о $time відповіді не було, тож дозу позначено як пропущену."
    )

  /** Undo within the 15-minute window returns the occurrence to due (ADR-012). */
  def undoConfirmation(nextReminderTime: String): String =
    tr(s"Undone. I'll check again at $nextReminderTime.", s"Скасовано. Нагадаю ще раз о $nextReminderTime.")

  /** Past the undo window the two-choice correction is offered instead (M1.10). */
  def undoWindowPassed: String =
    tr("The undo window has passed — correct it instead?", "Час на скасування минув — виправити запис?")

  /** Correction prompt (`dose.correct`, M1.10): [Now][At scheduled time][Cancel]. */
  def correctionPrompt: String = tr("When did you take it?", "Коли ви її прийняли?")

  /** Correction follow-up (M1.10): `Corrected — logged as taken at 09:00.` */
  def correctedConfirmation(time: String): String =
    tr(s"Corrected — logged as taken at $time.", s"Виправлено — записано як прийняту о $time.")

  /** [Keep missed] on the missed notice (M1.10): the row stays missed, the controls die. */
  def keepMissedConfirmation: String = tr("Okay — left marked missed.", "Гаразд — лишаємо позначку «пропущено».")

  /** [Cancel] on the correction prompt (M1.10). */
  def correctionCancelled: String = tr("No change.", "Без змін.")

  /** The refusal toasts of the one-tap handlers (M1.10); the two correction-prompt refusals open the flow instead. */
  def refusal(reason: Refusal): String =
    reason match
      case Refusal.NothingToUndo                => tr("There is nothing to undo.", "Немає чого скасовувати.")
      case Refusal.UndoWindowPassed             => tr("The undo window has passed.", "Час на скасування минув.")
      case Refusal.UndoWindowPassedOfferCorrect => undoWindowPassed
      case Refusal.CorrectionPrompt             => correctionPrompt
      case Refusal.AlreadyResolved              => tr("This dose is already recorded.", "Цю дозу вже записано.")
      case Refusal.SnoozeLimitReached           =>
        tr("No snoozes left for this dose.", "Для цієї дози відкладень більше не лишилося.")
      case Refusal.SnoozePastBound =>
        tr("That snooze would run past the next dose.", "Таке відкладення заходить на час наступної дози.")
      case Refusal.SnoozeNotAllowed => tr("This dose cannot be snoozed.", "Цю дозу не можна відкласти.")
      case Refusal.CorrectOnOpenRow =>
        tr(
          "This dose is still open — Taken or Skip applies.",
          "Ця доза ще відкрита — тут підходить «Прийнято» або «Пропустити»."
        )
      case Refusal.InvalidEffectiveAt => tr("That time is in the future.", "Цей час ще не настав.")
      case Refusal.RowCancelled       => tr("This dose is cancelled.", "Цю дозу скасовано.")

  /** The universal commands' resolution failures (M1.10). */
  def nothingDue: String = tr("Nothing is due right now.", "Зараз нічого не заплановано.")
  def noOpenDose(name: String): String = tr(s"No open dose for $name.", s"Відкритої дози для $name немає.")
  def unknownMedication(name: String): String = tr(s"I could not find $name.", s"Не вдалося знайти $name.")

  /** Medications -> Log dose / `/log` without a medication (M1.10). */
  def logPickerPrompt: String = tr("Log a dose of which medication?", "Дозу яких ліків записати?")
  def logEmpty: String = tr("No medications to log yet.", "Поки немає ліків, які можна записати.")

  /** The picker after its one tap (BUX-TAKE-01, BUX-TAKE-02): what was logged and when, with no button left. */
  def logged(name: String, time: String): String = tr(s"Logged $name at $time.", s"Записано: $name о $time.")

  /** `toast.take.already`: a later tap on a picker that has logged its dose. */
  def alreadyLogged(time: String): String = tr(s"Already logged at $time.", s"Уже записано о $time.")

  /** The confirmation after Undo of a manual log (BUX-UNDO-03), and the answer to a later tap on its picker. */
  def logUndone: String =
    tr("Undone. Nothing is recorded for this dose.", "Скасовано. Для цієї дози нічого не записано.")

  /** The button that opens a fresh picker (BUX-TAKE-01). */
  def logAnother: String = tr("Log another", "Записати ще")

  /** The one safety sentence (UX spec): the bot never advises, it defers to the clinician/pharmacist. */
  def unsureAdvice: String =
    tr(
      "If you are unsure whether to take a late or missed dose, follow your clinician/pharmacist instructions.",
      "Якщо ви не певні, чи приймати запізнілу або пропущену дозу, дотримуйтеся вказівок свого лікаря чи фармацевта."
    )

  /** The catalogue failure toast when the database is unavailable (R68: visibly fails). */
  def failureToast: String = tr(
    "Something went wrong on my side — please try again in a moment.",
    "Щось пішло не так з мого боку — спробуйте, будь ласка, ще раз за хвилину."
  )

  /** A lost commit acknowledgement cannot establish noncommit. Reconcile before any replay. */
  def checkingOutcome: String = tr("Checking whether this was recorded…", "Перевіряю, чи це записано…")

  /** Toast for a bad MAC or an expired slot (DESIGN.md section 4.4). */
  def staleControlToast: String = tr("This button is no longer valid.", "Ця кнопка вже не діє.")

  def entries: List[CopyEntry] = List(
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
    CopyEntry("reminder.logged", logged("Vitamin D", "09:03")),
    CopyEntry("reminder.already_logged", alreadyLogged("09:03")),
    CopyEntry("reminder.log_undone", logUndone),
    CopyEntry("reminder.log_another", logAnother),
    CopyEntry("reminder.unsure_advice", unsureAdvice),
    CopyEntry("reminder.failure_toast", failureToast),
    CopyEntry("reminder.checking_outcome", checkingOutcome),
    CopyEntry("reminder.stale_control_toast", staleControlToast)
  )
end ReminderCopy
