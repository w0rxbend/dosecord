package dosecord.core.domain.copy

/** Mood check-in copy (ROADMAP M0.12d; neutral, non-clinical tone per docs/MEDICATION_REMINDER_UX.md). */
object MoodCopy:

  /** Acknowledgement: `Mood recorded: 8/10.` */
  def recorded(level: Int): String = s"Mood recorded: $level/10."

  /** The usage error for an unparsable level or a bare `/mood` (C18: junk input is rejected, not stored). */
  val invalidMood = "I didn't get that. Try /mood <1-10> [note] [#tag ...] — e.g. /mood 8 slept well #sleep."

  val entries: List[CopyEntry] = List(
    CopyEntry("mood.recorded", recorded(8)),
    CopyEntry("mood.invalid", invalidMood)
  )
end MoodCopy
