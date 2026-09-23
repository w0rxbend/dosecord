package dosecord.core.scheduling

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatAdapter
import dosecord.contracts.ChatError
import dosecord.contracts.ChatRef
import dosecord.contracts.EditCapability
import dosecord.contracts.MessageHandle
import dosecord.contracts.ReactPayload
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedMessage
import dosecord.core.ports.Clock
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxMetrics
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Renders one claimed outbox row into the chat it targets and the message to execute. The row's `payload` is opaque to
  * the dispatcher; the production implementation ([[CatalogueOutboxRenderer]]) resolves the delivery channel and
  * renders the reminder copy from the M1.4a catalogue at send time (R56).
  */
trait OutboxRenderer:
  def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage)

/** Thrown by the digest renderer when every item resolved or went stale before the send (DESIGN.md section 7.5: the
  * dispatcher re-reads the items and skips the send). The dispatcher catches it and cancels the row without a vendor
  * call (ROADMAP M1.8).
  */
final case class DigestEmpty(sendKey: String) extends RuntimeException(s"digest $sendKey has no live items")

object OutboxDispatcher:
  val MaxAttempts = 8
  val PossibleDuplicateAfter: Duration = Duration.ofMinutes(2)
  val MinBackoff: Duration = Duration.ofSeconds(5)

  /** `fallback_after` (DESIGN.md section 7.6): a send still unsent this long after `next_attempt_at` — above the early
    * backoff rungs, so the dispatcher itself was down — also reaches the next healthy channel.
    */
  val FallbackAfter: Duration = Duration.ofMinutes(5)

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

  /** Exponential backoff 5 s .. 30 min by attempt count (ADR-009), before jitter. */
  def backoff(attempts: Int): Duration =
    val shift = math.min(math.max(attempts, 1) - 1, 12)
    val scaled = Duration.ofSeconds(5L << shift)
    if scaled.compareTo(MaxBackoff) > 0 then MaxBackoff else scaled

  /** The backoff of ADR-009 with full jitter (M1.7): uniformly distributed in `[max(5 s, base/2), min(30 min, base)]`,
    * so every retry instant stays inside the 5 s .. 30 min envelope while concurrent rows do not retry in lockstep.
    * `roll` in `[0, 1]` is the random draw; the first rung stays exactly 5 s.
    */
  def jitteredBackoff(attempts: Int, roll: Double): Duration =
    require(roll >= 0.0 && roll <= 1.0, s"jitter roll out of range: $roll")
    val base = backoff(attempts)
    val half = base.dividedBy(2)
    val low = if half.compareTo(MinBackoff) > 0 then half else MinBackoff
    val high = if base.compareTo(MaxBackoff) > 0 then MaxBackoff else base
    Duration.ofMillis(low.toMillis + ((high.toMillis - low.toMillis) * roll).toLong)

  /** The reaction sub-ops of a send: every reaction of every numbered (reaction-tier) control, in order. */
  def reactionsOf(rendered: RenderedMessage): List[String] =
    rendered.controls.collect { case RenderedControls.Numbered(_, _, reactions) => reactions }.flatten

  /** The dispatch order of a claimed batch (DESIGN.md section 7.6): `ack > interaction_reply > reminder > info > bulk`.
    * Rate limiting is FIFO per dispatcher fork, so priority is realised by dispatching the batch in this order.
    */
  def priorityRank(importance: String): Int = importance match
    case "ack"               => 0
    case "interaction_reply" => 1
    case "reminder"          => 2
    case "info"              => 3
    case "bulk"              => 4
    case _                   => 3

/** The vendor-owned outbox dispatcher (ADR-009, DESIGN.md section 7.6), grown from its M0.10 form: ops
  * `send|edit|finalize|delete|react`, epoch fencing before any vendor call, backoff 5 s .. 30 min with jitter, 8
  * attempts then `dead` (with the `dosecord_outbox_dead_total` metric), `possible_duplicate` after 2 minutes, batch
  * dispatch in priority order, channel fallback (channelFatal or 5 min unsent) through `delivery_channels`, and
  * per-vendor/per-chat token buckets counting vendor events. One instance per owned vendor set; the claim transaction
  * is short and every vendor call happens with no lock held.
  */
final class OutboxDispatcher(
    uow: UnitOfWork,
    adapters: Map[String, ChatAdapter],
    renderer: OutboxRenderer,
    clock: Clock,
    claimLimit: Int = 20,
    lease: Duration = Duration.ofSeconds(60),
    metrics: OutboxMetrics = OutboxMetrics.noop,
    random: () => Double = () => scala.math.random(),
    buckets: Option[TokenBuckets] = None
):
  import OutboxDispatcher.*

  /** Claims one batch and dispatches it in priority order; returns the number of rows claimed. */
  def dispatchOnce(): Int =
    val now = clock.now()
    val rows = uow.transaction(_.outbox.claim(now, adapters.keys.toSeq, claimLimit, lease))
    rows.sortBy(row => priorityRank(row.importance)).foreach(dispatchRow(_, now))
    rows.size

  private def dispatchRow(row: OutboxMessage, now: Instant): Unit =
    // Epoch fencing (DESIGN.md sections 7.3/7.6): a row the occurrence has passed is cancelled without a vendor call.
    if row.occurrenceId.isDefined && row.epoch.exists(epochIsStale(row, _)) then
      uow.transaction(_.outbox.cancel(row.id))
    else
      // `fallback_after` (DESIGN.md section 7.6): a send still unsent past the early backoff rungs also reaches the
      // next healthy channel.
      if row.op == OutboxOp.Send && Duration.between(row.nextAttemptAt, now).compareTo(FallbackAfter) > 0 then
        fallback(row, now)
      dispatchOwned(row, now)

  private def epochIsStale(row: OutboxMessage, epoch: Int): Boolean =
    uow.transaction(_.occurrences.epochIsStale(row.occurrenceId.get, epoch))

  private def dispatchOwned(row: OutboxMessage, now: Instant): Unit =
    val adapter = adapters(row.vendor)
    try
      row.op match
        case OutboxOp.Send                     => dispatchSend(row, adapter, now)
        case OutboxOp.Edit | OutboxOp.Finalize =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          val (_, rendered) = renderer.render(row, adapter.capabilities)
          throttle(row, target.chatId)
          if adapter.capabilities.editOwn == EditCapability.NoEdit then
            // The finalize ladder's last rung (DESIGN.md section 4.3): on a profile that cannot edit (the console),
            // the outcome goes out as a replacement message; the target's stale choice_map keeps resolving, and the
            // FSM answers a late tap with the semantic no-op ("Already recorded").
            val handle = adapter.send(ChatRef(row.vendor, target.chatId), rendered, row.sendKey)
            uow.transaction: tx =>
              tx.outbox.markSent(row.id, encodeHandle(handle), now, possibleDuplicate = false)
              tx.renderedMessages.record(
                handle,
                row.accountId,
                row.kind,
                subjectType = row.occurrenceId.map(_ => "occurrence"),
                subjectId = row.occurrenceId,
                epoch = row.epoch,
                rendered.choiceMap,
                now
              )
          else
            val handle = adapter.edit(target, rendered) // idempotent by content
            // M1.10: an edit carrying a new choice_map (the post-Taken finalize keeps [Undo][Correct]) supersedes the
            // recorded one so the kept controls stay resolvable on the numbered tiers; an empty map leaves the record.
            uow.transaction: tx =>
              tx.outbox.markSent(row.id, encodeHandle(handle), now, possibleDuplicate = false)
              tx.renderedMessages.recordEdit(handle, rendered.choiceMap, now)
        case OutboxOp.Delete =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          throttle(row, target.chatId)
          adapter.delete(target)
          uow.transaction(_.outbox.markSent(row.id, requiredTarget(row), now, possibleDuplicate = false))
        case OutboxOp.React =>
          val target = decodeHandle(row.vendor, requiredTarget(row))
          val payload = ReactPayload.fromJson(row.payload)
          throttle(row, target.chatId)
          adapter.react(
            target,
            payload.emoji,
            payload.on,
            txnKey = row.sendKey
          ) // duplicate-annotation errors are success
          uow.transaction(_.outbox.markSent(row.id, requiredTarget(row), now, possibleDuplicate = false))
    catch
      case _: DigestEmpty =>
        // M1.8 (DESIGN.md section 7.5): every digest item resolved or went stale before the send — the digest is
        // skipped without a vendor call.
        uow.transaction(_.outbox.cancel(row.id))
      case ChatError.RateLimited(after) =>
        retryOrDead(row, now.plus(after), possibleDuplicate = false, error = "rate limited")
      case e @ (_: ChatError.Retryable | _: ChatError.TooOld) =>
        val duplicate =
          row.op == OutboxOp.Send && row.attemptedAt.exists(isPossibleDuplicateWindow(_, now))
        retryOrDead(row, now.plus(jitteredBackoff(row.attempts, random())), duplicate, error = e.getMessage)
      case e: ChatError if e.channelFatal =>
        // Channel fallback (DESIGN.md section 7.6): the row is dead, the channel is dead, and the next healthy
        // channel's dispatch is enqueued with a new send_key.
        uow.transaction: tx =>
          tx.outbox.dead(row.id, e.getMessage)
          row.channelId.foreach(tx.channels.markDead(_, e.getMessage, now))
        metrics.outboxDead()
        fallback(row, now)
      case e: ChatError =>
        // Permanent(channelFatal = false): no channel action (DESIGN.md section 7.6).
        uow.transaction(_.outbox.failPermanently(row.id, e.getMessage))

  /** Enqueues the next healthy channel's dispatch with a new `send_key` (idempotent per channel through the UNIQUE
    * constraint). Only sends fall back: an edit/delete/react target is a vendor-specific handle.
    */
  private def fallback(row: OutboxMessage, now: Instant): Unit =
    if row.op == OutboxOp.Send then
      row.accountId.foreach: accountId =>
        row.channelId.foreach: channelId =>
          uow.transaction: tx =>
            tx.channels
              .fallbackChannel(accountId, channelId)
              .foreach: channel =>
                tx.outbox.enqueue(
                  NewOutboxMessage(
                    id = UUID.randomUUID(),
                    sendKey = s"${row.sendKey}:fb:${channel.channelId}",
                    op = OutboxOp.Send,
                    kind = row.kind,
                    vendor = channel.vendor,
                    accountId = row.accountId,
                    occurrenceId = row.occurrenceId,
                    channelId = Some(channel.channelId),
                    epoch = row.epoch,
                    payload = row.payload,
                    importance = row.importance,
                    nextAttemptAt = now
                  )
                )
          ()

  /** One token per vendor event (DESIGN.md section 7.6: the buckets count events, not rows). */
  private def throttle(row: OutboxMessage, chatId: String): Unit =
    buckets.foreach(_.acquire(row.vendor, chatId, 1))

  /** One retry within the attempt budget, or the terminal `dead` write plus its metric at `MaxAttempts` (ADR-009). */
  private def retryOrDead(row: OutboxMessage, at: Instant, possibleDuplicate: Boolean, error: String): Unit =
    if row.attempts >= MaxAttempts then
      uow.transaction(_.outbox.dead(row.id, error))
      metrics.outboxDead()
    else
      uow.transaction(_.outbox.retry(row.id, at, possibleDuplicate, error))
      if possibleDuplicate && !row.possibleDuplicate then metrics.possibleDuplicate(row.vendor)

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
        throttle(row, chat.chatId)
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
        throttle(row, handle.chatId)
        adapter.react(handle, emoji, on = true, txnKey = s"${row.sendKey}:r$i")
        uow.transaction(_.outbox.markOpDone(row.id, opIndex))
    uow.transaction(_.outbox.markSent(row.id, encodeHandle(handle), now, possibleDuplicate = resumeDuplicate))
    if resumeDuplicate && !row.possibleDuplicate then metrics.possibleDuplicate(row.vendor)

  private def requiredTarget(row: OutboxMessage): String =
    row.target.getOrElse(throw new IllegalStateException(s"outbox row ${row.id}: ${row.op.db} op without a target"))

  private def isPossibleDuplicateWindow(attemptedAt: Instant, now: Instant): Boolean =
    Duration.between(attemptedAt, now).compareTo(PossibleDuplicateAfter) > 0
