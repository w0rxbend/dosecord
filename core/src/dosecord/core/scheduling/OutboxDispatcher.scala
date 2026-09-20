package dosecord.core.scheduling

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatAdapter
import dosecord.contracts.ChatError
import dosecord.contracts.ChatRef
import dosecord.contracts.MessageHandle
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedMessage
import dosecord.core.ports.Clock
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant

/** Renders one claimed outbox row into the chat it targets and the message to execute. The row's `payload` is opaque to
  * the dispatcher; in the grown system (M1.7) this resolves the delivery channel and renders from the copy catalogue at
  * send time.
  */
trait OutboxRenderer:
  def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage)

object OutboxDispatcher:
  val MaxAttempts = 8
  val PossibleDuplicateAfter: Duration = Duration.ofMinutes(2)
  private val MaxBackoff: Duration = Duration.ofMinutes(30)

  /** `ops_done` bitmap: bit 0 = primary send recorded, bit i+1 = reaction i recorded. */
  def opsDoneHas(opsDone: Int, opIndex: Int): Boolean = (opsDone & (1 << opIndex)) != 0

  /** `platform_message_id` / `target` encoding. chatId may itself contain ':' on some vendors, so the split is on the
    * last separator.
    */
  def encodeHandle(handle: MessageHandle): String = s"${handle.chatId}:${handle.messageId}"

  def decodeHandle(vendor: String, encoded: String): MessageHandle =
    val sep = encoded.lastIndexOf(':')
    require(sep > 0 && sep < encoded.length - 1, s"malformed encoded handle '$encoded'")
    MessageHandle(vendor, encoded.take(sep), encoded.drop(sep + 1))

  /** Exponential backoff 5 s .. 30 min by attempt count (ADR-009); jitter lands with the retry property in M1.7. */
  def backoff(attempts: Int): Duration =
    val shift = math.min(math.max(attempts, 1) - 1, 12)
    val scaled = Duration.ofSeconds(5L << shift)
    if scaled.compareTo(MaxBackoff) > 0 then MaxBackoff else scaled

  /** The reaction sub-ops of a send: every reaction of every numbered (reaction-tier) control, in order. */
  def reactionsOf(rendered: RenderedMessage): List[String] =
    rendered.controls.collect { case RenderedControls.Numbered(_, _, reactions) => reactions }.flatten

/** The vendor-owned outbox dispatcher (ADR-009, DESIGN.md section 7.6), grown here in its minimal M0.10 form and
  * extended — not rewritten — in M1.7 (epoch fencing, channel fallback, rate limiting). One instance per owned vendor
  * set; the claim transaction is short and every vendor call happens with no lock held.
  */
final class OutboxDispatcher(
    uow: UnitOfWork,
    adapters: Map[String, ChatAdapter],
    renderer: OutboxRenderer,
    clock: Clock,
    claimLimit: Int = 20,
    lease: Duration = Duration.ofSeconds(60)
):
  import OutboxDispatcher.*

  /** Claims one batch and dispatches it; returns the number of rows claimed. */
  def dispatchOnce(): Int =
    val now = clock.now()
    val rows = uow.transaction(_.outbox.claim(now, adapters.keys.toSeq, claimLimit, lease))
    rows.foreach(dispatchRow(_, now))
    rows.size

  private def dispatchRow(row: OutboxMessage, now: Instant): Unit =
    val adapter = adapters(row.vendor)
    try
      row.op match
        case OutboxOp.Send                     => dispatchSend(row, adapter, now)
        case OutboxOp.Edit | OutboxOp.Finalize =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          val (_, rendered) = renderer.render(row, adapter.capabilities)
          val handle = adapter.edit(target, rendered) // idempotent by content
          uow.transaction(_.outbox.markSent(row.id, encodeHandle(handle), now, possibleDuplicate = false))
        case OutboxOp.Delete =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          adapter.delete(target)
          uow.transaction(_.outbox.markSent(row.id, requiredTarget(row), now, possibleDuplicate = false))
        case OutboxOp.React =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          adapter.react(target, row.payload, on = true, txnKey = row.sendKey) // duplicate-annotation errors are success
          uow.transaction(_.outbox.markSent(row.id, requiredTarget(row), now, possibleDuplicate = false))
    catch
      case ChatError.RateLimited(after) =>
        uow.transaction(_.outbox.retry(row.id, now.plus(after), possibleDuplicate = false, error = "rate limited"))
      case e @ (_: ChatError.Retryable | _: ChatError.TooOld) =>
        val duplicate =
          row.op == OutboxOp.Send && row.attemptedAt.exists(isPossibleDuplicateWindow(_, now))
        uow.transaction(_.outbox.retry(row.id, now.plus(backoff(row.attempts)), duplicate, error = e.getMessage))
      case e: ChatError =>
        // channelFatal fallback (Unreachable) is wired with delivery channels in M1.7; both land here for now.
        uow.transaction(_.outbox.failPermanently(row.id, e.getMessage))

  /** Send with two-phase recording: the handle and `choice_map` are committed immediately after the primary send and
    * before the first reaction, so a crash mid-render leaves resolvable controls and `ops_done` resumes the remaining
    * sub-ops without redoing completed ones (ADR-009).
    */
  private def dispatchSend(row: OutboxMessage, adapter: ChatAdapter, now: Instant): Unit =
    val (chat, rendered) = renderer.render(row, adapter.capabilities)
    val resumeDuplicate = row.previousAttemptedAt.exists(isPossibleDuplicateWindow(_, now))
    val handle =
      if opsDoneHas(row.opsDone, 0) then
        decodeHandle(
          row.vendor,
          row.platformMessageId.getOrElse(
            throw new IllegalStateException(s"outbox row ${row.id}: send sub-op done but no handle recorded")
          )
        )
      else
        val sent = adapter.send(chat, rendered, row.sendKey)
        val encoded = encodeHandle(sent)
        uow.transaction { tx =>
          tx.outbox.recordHandle(row.id, encoded)
          tx.renderedMessages.record(
            sent,
            row.accountId,
            row.kind,
            subjectType = row.occurrenceId.map(_ => "occurrence"),
            subjectId = row.occurrenceId,
            epoch = row.epoch,
            choiceMap = rendered.choiceMap,
            now
          )
        }
        sent
    reactionsOf(rendered).zipWithIndex.foreach: (emoji, i) =>
      val opIndex = i + 1
      if !opsDoneHas(row.opsDone, opIndex) then
        adapter.react(handle, emoji, on = true, txnKey = s"${row.sendKey}:r$i")
        uow.transaction(_.outbox.markOpDone(row.id, opIndex))
    uow.transaction(_.outbox.markSent(row.id, encodeHandle(handle), now, possibleDuplicate = resumeDuplicate))

  private def requiredTarget(row: OutboxMessage): String =
    row.target.getOrElse(throw new IllegalStateException(s"outbox row ${row.id}: ${row.op.db} op without a target"))

  private def isPossibleDuplicateWindow(attemptedAt: Instant, now: Instant): Boolean =
    Duration.between(attemptedAt, now).compareTo(PossibleDuplicateAfter) > 0
