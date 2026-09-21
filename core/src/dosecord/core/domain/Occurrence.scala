package dosecord.core.domain

import java.time.Instant

/** The `occ_status` enum of the V1 schema (`infra/.../V1__baseline.sql`), mirrored exactly. `unknown` means the window
  * elapsed without evidence that a reminder was delivered; it is excluded from the adherence denominator (DESIGN.md
  * section 7.3, ADR-012).
  */
enum OccurrenceStatus(val dbValue: String):
  case Pending extends OccurrenceStatus("pending")
  case Due extends OccurrenceStatus("due")
  case Snoozed extends OccurrenceStatus("snoozed")
  case Taken extends OccurrenceStatus("taken")
  case Skipped extends OccurrenceStatus("skipped")
  case Missed extends OccurrenceStatus("missed")
  case Unknown extends OccurrenceStatus("unknown")
  case Cancelled extends OccurrenceStatus("cancelled")

  /** Open statuses are the ones the loop claims (`next_action_at IS NOT NULL`, schema CHECK `occ_open_has_action`).
    */
  def isOpen: Boolean =
    this == OccurrenceStatus.Pending || this == OccurrenceStatus.Due || this == OccurrenceStatus.Snoozed

object OccurrenceStatus:
  def fromDbValue(value: String): OccurrenceStatus =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown occ_status '$value'"))

/** Why an occurrence became `unknown` (ADR-012): `outage` when no worker was healthy across the due window,
  * `undelivered` when workers were healthy but no outbox row for the occurrence was ever sent. The `dbValue` strings
  * match what `dose_occurrences.unknown_reason` stores.
  */
enum UnknownReason(val dbValue: String):
  case Outage extends UnknownReason("outage")
  case Undelivered extends UnknownReason("undelivered")

object UnknownReason:
  def fromDbValue(value: String): UnknownReason =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown unknown_reason '$value'"))

/** Why an occurrence was cancelled (the `dose_occurrences.cancel_reason` CHECK values, ADR-004): `superseded` by a
  * newer revision, `paused`, `archived`, or `anchor_undone` (chain child of an undone anchor, M7.2).
  */
enum CancelReason(val dbValue: String):
  case Superseded extends CancelReason("superseded")
  case Paused extends CancelReason("paused")
  case Archived extends CancelReason("archived")
  case AnchorUndone extends CancelReason("anchor_undone")

object CancelReason:
  def fromDbValue(value: String): CancelReason =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown cancel_reason '$value'"))

/** Skip reason codes recorded on `dose_actions.reason_code` (ROADMAP M1.3 "skipped with reason codes"; the chips land
  * in M3.1).
  */
enum SkipReason(val code: String):
  case Forgot extends SkipReason("forgot")
  case RanOut extends SkipReason("ran_out")
  case SideEffects extends SkipReason("side_effects")
  case ClinicianAdvised extends SkipReason("clinician_advised")
  case Other extends SkipReason("other")

/** The pure projection of a `dose_occurrences` row that `Decide.decide` reads and rewrites (DESIGN.md section 7.3).
  * Identity fields (`id`, `account_id`, `medication_id`, ...), the optimistic-lock `version` and the dose snapshot
  * arrive with persistence in M1.5; the pure FSM never consults them. Field invariants mirror the schema CHECKs: open
  * status <=> `nextActionAt` set, `taken` <=> `takenAt` set, `skipped` <=> `skippedAt` set, `snoozed` => `snoozedUntil`
  * set, `unknown` => `unknownReason` set, `dueWindowStart <= missDeadline`.
  */
final case class Occurrence(
    status: OccurrenceStatus,
    scheduledFor: Instant,
    dueWindowStart: Instant,
    dueWindowEnd: Instant,
    missDeadline: Instant,
    nextActionAt: Option[Instant],
    reminderSeq: Int = 0,
    snoozeCount: Int = 0,
    snoozedUntil: Option[Instant] = None,
    lastRemindedAt: Option[Instant] = None,
    takenAt: Option[Instant] = None,
    effectiveAt: Option[Instant] = None,
    skippedAt: Option[Instant] = None,
    missedAt: Option[Instant] = None,
    unknownReason: Option[UnknownReason] = None,
    epoch: Int = 0
)

object Occurrence:

  /** A freshly materialised scheduled occurrence: `pending`, with the DESIGN.md section 7.1 derived instants
    * (`due_window_start = scheduled_for + initialOffset`, `due_window_end = scheduled_for + onTimeGrace`,
    * `miss_deadline = scheduled_for + missAfter`).
    */
  def scheduled(scheduledFor: Instant, policy: ReminderPolicy): Occurrence =
    val dueWindowStart = scheduledFor.plusSeconds(policy.initialOffsetMinutes.toLong * 60L)
    Occurrence(
      status = OccurrenceStatus.Pending,
      scheduledFor = scheduledFor,
      dueWindowStart = dueWindowStart,
      dueWindowEnd = scheduledFor.plusSeconds(policy.onTimeGraceMinutes.toLong * 60L),
      missDeadline = scheduledFor.plusSeconds(policy.missAfterMinutes.toLong * 60L),
      nextActionAt = Some(dueWindowStart)
    )
