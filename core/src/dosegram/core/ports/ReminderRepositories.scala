package dosegram.core.ports

import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptNotice
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One nudge plan of an account (`prompt_schedules`): the preset it came from, the plan, whether it is on, and its
  * pause. `activeFrom` is when the plan took its current form; no slot before it is decided.
  */
final case class PromptSchedule(
    id: UUID,
    accountId: UUID,
    kind: PromptKind,
    presetId: String,
    plan: NudgePlan,
    ownCap: Boolean,
    enabled: Boolean,
    sound: Boolean,
    pausedUntil: Option[Instant],
    activeFrom: Instant
)

/** One decided slot (`prompt_occurrences`). `sendKey` names the outbox row of a prompt that was sent. */
final case class PromptOccurrence(
    id: UUID,
    accountId: UUID,
    scheduleId: UUID,
    kind: PromptKind,
    planDate: LocalDate,
    slotKey: String,
    dueAt: Instant,
    status: PromptStatus,
    reason: Option[SuppressReason],
    notice: Option[PromptNotice],
    sendKey: Option[String],
    budgeted: Boolean,
    decidedAt: Instant
)

/** `prompt_schedules` and `prompt_occurrences` (ARCHITECTURE section 8.4, ADR-023). */
trait PromptRepository:

  def schedules(accountId: UUID): List[PromptSchedule]

  def schedule(accountId: UUID, kind: PromptKind): Option[PromptSchedule]

  /** Creates or replaces the plan of `schedule.kind`, keeping the id of an existing one. Returns the stored plan. */
  def save(schedule: PromptSchedule, now: Instant): PromptSchedule

  def setEnabled(accountId: UUID, kind: PromptKind, enabled: Boolean, now: Instant): Boolean

  def pause(scheduleId: UUID, until: Option[Instant], now: Instant): Unit

  /** The ids of every plan that is on, for the prompt loop. */
  def enabledIds(): List[UUID]

  /** The plan, locked for this transaction (`FOR UPDATE SKIP LOCKED`); none when it is off or another loop holds it.
    */
  def lockEnabled(scheduleId: UUID): Option[PromptSchedule]

  /** The decided slots of a plan on the given plan dates. */
  def decided(scheduleId: UUID, dates: List[LocalDate]): List[PromptOccurrence]

  /** The decided slots of every plan of the account on one plan date. */
  def decidedOn(accountId: UUID, date: LocalDate): List[PromptOccurrence]

  /** Inserts the decision; false when the slot was already decided (the natural key). */
  def insert(occurrence: PromptOccurrence): Boolean

  def occurrence(id: UUID): Option[PromptOccurrence]

  /** Ends a sent prompt as answered or expired; false when it was no longer open. */
  def close(id: UUID, status: PromptStatus, at: Instant): Boolean

  /** The newest prompt of the plan that reached the chat, of any plan date: the message a new prompt replaces. */
  def lastSent(scheduleId: UUID): Option[PromptOccurrence]

  /** Records that the prompt's message now carries `notice` (the pause notice it was edited to). */
  def markNotice(id: UUID, notice: PromptNotice): Unit

  /** Cancels the outbox row of a sent prompt that has not left the queue yet, so a replaced prompt is never delivered
    * late. Returns whether a row was cancelled.
    */
  def cancelQueued(sendKey: String): Boolean

/** `day_profiles`: the anchors and work hours, and the pause of every prompt of the account. */
trait DayProfileRepository:
  def anchors(accountId: UUID): Option[DayAnchors]
  def save(accountId: UUID, anchors: DayAnchors, now: Instant): Unit
  def pausedUntil(accountId: UUID): Option[Instant]

  /** Pauses every prompt of the account; the anchors are stored with their defaults if the account has none yet. */
  def pauseAll(accountId: UUID, until: Option[Instant], default: DayAnchors, now: Instant): Unit

/** The repository group of the day profile and the prompts (ARCHITECTURE section 10.17 rule 5). */
trait ReminderRepositories:
  def prompts: PromptRepository
  def dayProfiles: DayProfileRepository
