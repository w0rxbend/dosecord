package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import java.time.Instant

/** Who recorded a dose action; matches the `dose_actions.actor_type` CHECK (`user`/`system`).
  */
enum Actor(val dbValue: String):
  case System extends Actor("system")
  case User extends Actor("user")

/** The `dose_actions` row a transition appends, as data (ROADMAP M1.3: "`Transition` carries action rows"). The
  * persistence layer (M1.5) assigns `id`, `seq`, `idempotency_key` and the correlation/vendor fields; `takenLate` is
  * carried into `metadata` (there is no column for it; delay statistics derive lateness from `effective_at`).
  * `collapsedReminders` (M1.8) is the number of cadence reminders a catch-up collapse folded into the one actually
  * sent, so the projection fold can rebuild the stored `reminder_seq` jump (DESIGN.md section 7.5).
  */
final case class ActionRowIntent(
    action: DoseActionKind,
    actor: Actor,
    occurredAt: Instant,
    priorStatus: OccurrenceStatus,
    newStatus: OccurrenceStatus,
    effectiveAt: Option[Instant] = None,
    reasonCode: Option[String] = None,
    note: Option[String] = None,
    undoesSeq: Option[Int] = None,
    takenLate: Boolean = false,
    catchUp: Boolean = false,
    collapsedReminders: Int = 0
)

/** Which reminder message a dispatch sends (DESIGN.md section 7.3): the initial reminder, a bounded repeat, or the
  * snooze-wake reminder (not counted in `reminder_seq`).
  */
enum ReminderKind:
  case Initial, Repeat, SnoozeWake

/** Why a message's controls are finalized: a repeat supersedes the previous reminder, or the occurrence resolved (taken
  * / skipped / snoozed / undone).
  */
enum FinalizeReason:
  case Superseded, Resolved

/** An outbox intent the loop enqueues and the dispatcher executes, as data (ROADMAP M1.3). Rendering, `send_key`,
  * epochs on the wire and vendor ops are the dispatcher's concern (M1.7); the FSM only describes what must happen.
  * `notBefore` on [[MissedNotice]] is the quiet-hours deferral of ADR-012 (the notice, never the status, is deferred).
  */
enum DispatchIntent:
  case Reminder(kind: ReminderKind, silent: Boolean, reminderSeq: Int)
  case FinalizeControls(reason: FinalizeReason)
  case MissedNotice(notBefore: Option[Instant], silent: Boolean)

/** Declared for ADR-012 chain semantics ("on any terminal transition of chain dose k, the same transaction creates dose
  * k + 1"). Inert until M7.2: `Decide.decide` never produces one; the field exists so the `Transition` shape is stable
  * when chains land.
  */
enum ChainChildIntent:
  case AnchoredAtEffective(effectiveAt: Instant)
  case AnchoredAtScheduled(scheduledFor: Instant)

/** A named refusal of a user event. Every `(status, event)` cell of the decide table is either a transition or one of
  * these; nothing is silently dropped (ROADMAP M1.3 exhaustivity acceptance).
  */
enum Refusal:
  /** No undoable user action on this row. */
  case NothingToUndo

  /** The undo window passed on an open row (snoozed/due); no correction applies. */
  case UndoWindowPassed

  /** The undo window passed on a resolved row: "The undo window has passed — correct it instead?" (M1.10). */
  case UndoWindowPassedOfferCorrect

  /** A direct Taken beyond the late-log window (or on a user-resolved row) becomes the correction flow: "Log as taken:
    * [Now][At scheduled time][Cancel]" (ADR-012, M1.10).
    */
  case CorrectionPrompt

  /** The row is already resolved by the user; Undo or Correct applies instead. */
  case AlreadyResolved

  /** `snooze_count >= maxSnoozes`. */
  case SnoozeLimitReached

  /** The requested snooze passes the next occurrence, `scheduled_for + maxLate`, or the chain bound. */
  case SnoozePastBound

  /** Snooze is only meaningful on open rows. */
  case SnoozeNotAllowed

  /** Correct applies to resolved rows; an open row takes Taken/Skipped/Snoozed. */
  case CorrectOnOpenRow

  /** A correction's explicit `effective_at` lies in the future. */
  case InvalidEffectiveAt

  /** Cancelled rows (superseded/paused/archived) accept no user action. */
  case RowCancelled

/** What the caller tells the user (or nothing, for system ticks). Final copy is the M1.4a catalogue's job; these are
  * the semantic outcomes the copy renders.
  */
enum Feedback:
  case None
  case Recorded(takenLate: Boolean)
  case AlreadyRecorded(recordedAt: Instant)
  case SkippedRecorded
  case SnoozedUntil(until: Instant)
  case Undone(nextReminderAt: Instant)
  case Corrected(effectiveAt: Instant)
  case NoteRecorded
  case Refused(reason: Refusal)

/** The result of `Decide.decide` (DESIGN.md section 7.3): the rewritten occurrence row, the `dose_actions` row to
  * append, any extra action rows the transition records (M1.8: `catch_up_collapsed` when a jump folds the repeat
  * cadence, DESIGN.md section 7.5), the outbox intents to enqueue, the declared-but-inert chain child, and the
  * user-facing feedback. The loop (M1.6) persists `row` (epoch fencing via `row.epoch`), appends `action` plus
  * `additionalActions`, and enqueues `dispatches` in one transaction; nothing here performs I/O.
  */
final case class Transition(
    row: Occurrence,
    action: Option[ActionRowIntent],
    dispatches: List[DispatchIntent],
    chainChild: Option[ChainChildIntent],
    feedback: Feedback,
    additionalActions: List[ActionRowIntent] = Nil
)

object Transition:

  /** A semantic no-op or refusal: the row is returned untouched (no epoch increment), no action, no dispatches.
    */
  def unchanged(occ: Occurrence, feedback: Feedback): Transition =
    Transition(occ, scala.None, Nil, scala.None, feedback)
