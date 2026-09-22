package dosecord.core.chat

import dosecord.contracts.Block
import dosecord.contracts.Choice
import dosecord.contracts.ChoiceSet
import dosecord.contracts.ChoiceStyle
import dosecord.core.domain.copy.Labels

/** The control layouts fixed by ROADMAP M0.9; the labels come from the M1.4a copy catalogue (`core/domain/copy`). The
  * shapes — two reminder rows, styles, and the post-Taken follow-up — are pinned by the suite B1 goldens.
  *
  * Each ChoiceSet renders as one packed row group on the native-buttons rung, so the two-row reminder layout is two
  * ChoiceSets; on the numbered tiers the renderer numbers choices continuously across sets.
  */
object Controls:

  /** Reminder rows: row 1 [Taken][Snooze 10m][Skip], row 2 the remaining snooze options ([Snooze 30m][Snooze 1h] for
    * the defaults). `snooze` is pre-filtered by the caller against the snooze bounds (that filtering is decide()'s job,
    * M1.3); the first option goes on row 1. Styles: Taken = success, everything else secondary.
    */
  def reminder(
      taken: CallbackToken,
      skip: CallbackToken,
      snooze: List[(Int, CallbackToken)]
  ): List[Block] =
    require(snooze.nonEmpty, "at least the default snooze option")
    val row1 = Block.Choices(
      ChoiceSet(
        id = "reminder.main",
        choices = List(
          Choice(Labels.Taken, taken.wire, ChoiceStyle.Success),
          Choice(Labels.snooze(snooze.head._1), snooze.head._2.wire),
          Choice(Labels.Skip, skip.wire)
        )
      )
    )
    snooze.tail match
      case Nil  => List(row1)
      case rest =>
        List(
          row1,
          Block.Choices(ChoiceSet(id = "reminder.more", choices = rest.map((m, t) => Choice(Labels.snooze(m), t.wire))))
        )

  /** Wire overload for callers outside `core.chat` (the outbox dispatcher's catalogue renderer mints `dc:` strings
    * through its CallbackCodec): the same fixed layout.
    */
  def reminder(taken: String, skip: String, snooze: List[(Int, String)]): List[Block] =
    reminder(CallbackToken(taken), CallbackToken(skip), snooze.map((m, t) => (m, CallbackToken(t))))

  /** Post-Taken follow-up: [Undo][Correct] (M3.1 appends [Add note]). */
  def postTaken(undo: CallbackToken, correct: CallbackToken): ChoiceSet =
    ChoiceSet(
      id = "post_taken.followup",
      choices = List(Choice(Labels.Undo, undo.wire), Choice(Labels.Correct, correct.wire))
    )
end Controls
