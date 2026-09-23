package dosecord.core.scheduling

import dosecord.contracts.LoopDispatch
import dosecord.core.domain.FinalizeReason
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.Tx

import java.time.Instant
import java.util.UUID

/** The finalize half of the outbox enqueue (DESIGN.md section 7.6: "the post-resolution finalize op is enqueued for
  * every recorded handle of the occurrence"), shared by the reminder loop (a repeat supersedes the previous reminder)
  * and the M1.10 one-tap intake handlers (a resolution drops the controls of every recorded handle). With no recorded
  * handle there is nothing to finalize — a target-less finalize row would be undispatchable.
  */
object FinalizeDispatch:

  def reasonTag(reason: FinalizeReason): String = reason match
    case FinalizeReason.Superseded => "superseded"
    case FinalizeReason.Resolved   => "resolved"

  def enqueue(tx: Tx, occurrenceId: UUID, accountId: UUID, epoch: Int, reason: FinalizeReason, now: Instant): Unit =
    tx.renderedMessages.handlesForSubject("occurrence", occurrenceId).foreach { handle =>
      val target = OutboxDispatcher.encodeHandle(handle)
      tx.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = s"occ:$occurrenceId:e$epoch:s0:kfinalize_${reasonTag(reason)}:t$target",
          op = OutboxOp.Finalize,
          kind = "reminder_finalize",
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
    }
end FinalizeDispatch
