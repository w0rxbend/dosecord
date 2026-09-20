package dosecord.core.domain.copy

/** Control labels (ROADMAP M1.4a recorded decisions). The shapes that use them — the two-row reminder layout, the
  * missed notice, the post-Taken follow-up — are pinned by `core/chat/Controls.scala` (M0.9).
  */
object Labels:

  val Taken = "Taken"
  val Skip = "Skip"
  val Undo = "Undo"
  val Correct = "Correct"
  val ITookIt = "I took it"
  val KeepMissed = "Keep missed"
  val Cancel = "Cancel"
  val Back = "Back"
  val Create = "Create"
  val Done = "Done"
  val Yes = "Yes"
  val Change = "Change"
  val Continue = "Continue"
  val NoNote = "No note"
  val Now = "Now"
  val AtScheduledTime = "At scheduled time"

  /** Snooze option labels: `Snooze 10m`, `Snooze 1h`. */
  def snooze(minutes: Int): String =
    if minutes % 60 == 0 then s"Snooze ${minutes / 60}h" else s"Snooze ${minutes}m"

  /** Missed-notice row (recorded decision): [I took it][Skip][Keep missed]. */
  val missedNotice: List[String] = List(ITookIt, Skip, KeepMissed)

  /** Post-Taken follow-up (recorded decision): [Undo][Correct] (M3.1 appends [Add note]). */
  val postTaken: List[String] = List(Undo, Correct)

  val entries: List[CopyEntry] = List(
    CopyEntry("label.taken", Taken),
    CopyEntry("label.skip", Skip),
    CopyEntry("label.undo", Undo),
    CopyEntry("label.correct", Correct),
    CopyEntry("label.i_took_it", ITookIt),
    CopyEntry("label.keep_missed", KeepMissed),
    CopyEntry("label.cancel", Cancel),
    CopyEntry("label.back", Back),
    CopyEntry("label.create", Create),
    CopyEntry("label.done", Done),
    CopyEntry("label.yes", Yes),
    CopyEntry("label.change", Change),
    CopyEntry("label.continue", Continue),
    CopyEntry("label.no_note", NoNote),
    CopyEntry("label.now", Now),
    CopyEntry("label.at_scheduled_time", AtScheduledTime),
    CopyEntry("label.snooze.10", snooze(10)),
    CopyEntry("label.snooze.30", snooze(30)),
    CopyEntry("label.snooze.60", snooze(60))
  )
end Labels
