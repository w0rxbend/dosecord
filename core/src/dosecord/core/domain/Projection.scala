package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import java.time.Instant

/** The lifecycle projection of an occurrence that the `dose_actions` log determines (ROADMAP M1.4b, DESIGN.md section
  * 7.3 "projection = fold(actions)"). The action log is the source of truth for the *lifecycle* fields: the status and
  * its resolution instants (`taken_at`, `effective_at`, `skipped_at`, `missed_at`) plus the reminder bookkeeping every
  * action row pins down (`last_reminded_at`, `reminder_seq`, `snooze_count`).
  *
  * The stored row additionally caches loop bookkeeping the log does not carry: `next_action_at`, the window shifts of
  * quiet-defer / snooze / undo, `epoch`, the `unknown` reason (recomputable from heartbeat evidence) and the
  * `snoozed_until` instant. Those are scheduling state, not lifecycle state, and are deliberately outside this
  * projection; the fold never invents them.
  */
final case class Projection(
    status: OccurrenceStatus,
    takenAt: Option[Instant],
    effectiveAt: Option[Instant],
    skippedAt: Option[Instant],
    missedAt: Option[Instant],
    lastRemindedAt: Option[Instant],
    reminderSeq: Int,
    snoozeCount: Int
)

object Projection:

  /** The projection of a stored row: the fold's result must equal this for the row's own action history. */
  def of(occ: Occurrence): Projection =
    Projection(
      status = occ.status,
      takenAt = occ.takenAt,
      effectiveAt = occ.effectiveAt,
      skippedAt = occ.skippedAt,
      missedAt = occ.missedAt,
      lastRemindedAt = occ.lastRemindedAt,
      reminderSeq = occ.reminderSeq,
      snoozeCount = occ.snoozeCount
    )

  /** Rebuild an occurrence's projection purely from its `dose_actions` history (ROADMAP M1.4b: the log is the source of
    * truth, the stored row is a cache). `seed` is the freshly materialised row; `actions` are its action rows in `seq`
    * order.
    */
  def fold(actions: List[ActionRowIntent], seed: Occurrence): Projection =
    actions.foldLeft(of(seed))(apply)

  /** One action row applied to the projection. Every arm mirrors exactly the field updates `Decide.decide` performs for
    * that action kind, restricted to the lifecycle fields; `new_status` is the log's own record of the resulting
    * status, so it is authoritative for every kind (a `note_added` row carries `new_status = prior_status`).
    */
  def apply(projection: Projection, action: ActionRowIntent): Projection =
    val base = projection.copy(status = action.newStatus)
    action.action match
      case DoseActionKind.ReminderSent =>
        // The snooze-wake reminder (prior status snoozed) is not counted in reminder_seq (ADR-012).
        base.copy(
          lastRemindedAt = Some(action.occurredAt),
          reminderSeq =
            if action.priorStatus == OccurrenceStatus.Snoozed then projection.reminderSeq
            else projection.reminderSeq + 1
        )
      case DoseActionKind.ReminderDeferred =>
        // Window shift only; no lifecycle field moves.
        base
      case DoseActionKind.Taken =>
        base.copy(takenAt = Some(action.occurredAt), effectiveAt = action.effectiveAt, missedAt = None)
      case DoseActionKind.Skipped =>
        base.copy(skippedAt = Some(action.occurredAt), missedAt = None)
      case DoseActionKind.Snoozed =>
        // Undo clears the snooze instant but never the budget (ADR-012), so the count only ever grows.
        base.copy(snoozeCount = projection.snoozeCount + 1)
      case DoseActionKind.AutoMarkedMissed =>
        base.copy(missedAt = Some(action.occurredAt))
      case DoseActionKind.MarkedUnknown =>
        base
      case DoseActionKind.ManuallyCorrected =>
        base.copy(
          takenAt = Some(action.occurredAt),
          effectiveAt = action.effectiveAt,
          skippedAt = None,
          missedAt = None
        )
      case DoseActionKind.Undone =>
        base.copy(takenAt = None, effectiveAt = None, skippedAt = None)
      case DoseActionKind.NoteAdded =>
        base
      case DoseActionKind.Cancelled =>
        // No producer before M1.5 (revision reconciliation); the status record alone is the lifecycle change.
        base
      case DoseActionKind.CatchUpCollapsed =>
        // Declared for M1.8, inert until then; collapse re-points scheduling state, never lifecycle fields.
        base
      case DoseActionKind.ChainChildCreated =>
        // Declared for M7.2, inert until then; the child is a separate occurrence with its own log.
        base
end Projection
