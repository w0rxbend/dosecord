package dosegram.core.domain.reminders

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The due slots of one plan that have no record yet, split as catch-up needs them (ARCHITECTURE REM-16, section 8.5):
  * only the newest can still be sent, and only while it is fresh; every older one is recorded as passed over, so a
  * restart after downtime sends at most one prompt per plan and no slot goes unrecorded.
  */
final case class DueSlots(passed: List[NudgeSlot], current: Option[NudgeSlot], stale: Option[NudgeSlot])

object PromptPlanner:

  /** `activeFrom` is when the plan took its current form: slots before it belong to no plan the user chose. */
  def split(
      due: List[NudgeSlot],
      recorded: Set[(LocalDate, String)],
      activeFrom: Instant,
      now: Instant,
      expiry: Duration
  ): DueSlots =
    val open = due.filter(slot => slot.at.isAfter(activeFrom) && !recorded((slot.planDate, slot.slotKey)))
    open.lastOption match
      case None         => DueSlots(Nil, None, None)
      case Some(newest) =>
        val fresh = Duration.between(newest.at, now).compareTo(expiry) <= 0
        DueSlots(open.init, Option.when(fresh)(newest), Option.unless(fresh)(newest))
