package dosegram.core.scheduling

import dosegram.contracts.LoopDispatch
import dosegram.contracts.MessageHandle
import dosegram.core.domain.FinalizeReason
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.Tx

import java.time.Instant
import java.util.UUID

/** The finalize half of the outbox enqueue (DESIGN.md section 7.6: "the post-resolution finalize op is enqueued for
  * every recorded handle of the occurrence"), shared by the reminder loop (a repeat supersedes the previous reminder)
  * and the M1.10 one-tap intake handlers (a resolution drops the controls of every recorded handle). With no recorded
  * handle there is nothing to finalize — a target-less finalize row would be undispatchable.
  */
object FinalizeDispatch:

  /** The outbox kind of a finalize or a superseding delete of a reminder card. */
  val Kind = "reminder_finalize"

  def reasonTag(reason: FinalizeReason): String = reason match
    case FinalizeReason.Superseded => "superseded"
    case FinalizeReason.Resolved   => "resolved"

  /** A superseded card is deleted and forgotten, so the chat holds one card per dose and a later finalize edits only
    * the newest (BOT_UX section 9.5 rows 2 and 4; supervisor decision, 2026-11-27). Where the vendor cannot delete it,
    * or the delete fails, the dispatcher edits it to the superseded text instead.
    */
  def enqueue(tx: Tx, occurrenceId: UUID, accountId: UUID, epoch: Int, reason: FinalizeReason, now: Instant): Unit =
    tx.renderedMessages
      .handlesForSubject("occurrence", occurrenceId)
      .foreach(enqueueFor(tx, occurrenceId, accountId, epoch, reason, _, now))

  /** A card that went out after its occurrence moved on (its send was on the wire while the loop or a tap advanced the
    * epoch, so the finalize of that step did not know it): deleted while a newer reminder is open, otherwise brought to
    * the current outcome (finding 5 of the review of 2026-11-27).
    */
  def enqueueStale(tx: Tx, occurrenceId: UUID, accountId: UUID, card: MessageHandle, now: Instant): Unit =
    tx.occurrences
      .get(occurrenceId)
      .foreach: occ =>
        val open = occ.status == OccurrenceStatus.Due || occ.status == OccurrenceStatus.Snoozed
        val reason = if open then FinalizeReason.Superseded else FinalizeReason.Resolved
        enqueueFor(tx, occurrenceId, accountId, occ.state.epoch, reason, card, now)

  private def enqueueFor(
      tx: Tx,
      occurrenceId: UUID,
      accountId: UUID,
      epoch: Int,
      reason: FinalizeReason,
      handle: MessageHandle,
      now: Instant
  ): Unit =
    val op = if reason == FinalizeReason.Superseded then OutboxOp.Delete else OutboxOp.Finalize
    val target = OutboxDispatcher.encodeHandle(handle)
    if op == OutboxOp.Delete then tx.renderedMessages.detachFromSubject(handle)
    tx.outbox.enqueue(
      NewOutboxMessage(
        id = UUID.randomUUID(),
        sendKey = s"occ:$occurrenceId:e$epoch:s0:kfinalize_${reasonTag(reason)}:t$target",
        op = op,
        kind = Kind,
        vendor = handle.vendor,
        accountId = Some(accountId),
        occurrenceId = Some(occurrenceId),
        channelId = None,
        epoch = Some(epoch),
        payload = LoopDispatch.toJson(LoopDispatch.FinalizeControls(occurrenceId, reasonTag(reason))),
        target = Some(target),
        importance = "reminder",
        nextAttemptAt = now
      )
    )
    ()
end FinalizeDispatch
