package dosecord.core.domain.copy

/** One entry of the copy catalogue (ROADMAP M1.4a): a stable id plus the rendered text. Parameterized copy is entered
  * with its sample arguments so the forbidden-phrase test sees every template at least once.
  */
final case class CopyEntry(id: String, text: String)

/** The user-facing copy catalogue (ROADMAP M1.4a, product spec docs/MEDICATION_REMINDER_UX.md). All copy is neutral and
  * non-clinical: the bot records user actions, it never prescribes, advises doses, or shames. Tone and the safety
  * sentence are salvaged from the retired prototype's specification.
  */
object Copy:

  /** Every catalogue entry: all static strings plus one sample rendering of every parameterized string. */
  def all: List[CopyEntry] =
    Labels.entries ++
      ReminderCopy.entries ++
      DoseActionCopy.entries ++
      DigestCopy.entries ++
      WizardCopy.entries ++
      MenuCopy.entries ++
      OpsCopy.entries ++
      HelpCopy.entries ++
      HabitCopy.entries ++
      IdentityCopy.entries ++
      MoodCopy.entries
end Copy
