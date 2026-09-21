package dosecord.core.ports

import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.MessageHandle

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Delivery op of an outbox row (DESIGN.md section 7.6, ADR-009). */
enum OutboxOp(val db: String):
  case Send extends OutboxOp("send")
  case Edit extends OutboxOp("edit")
  case Finalize extends OutboxOp("finalize")
  case Delete extends OutboxOp("delete")
  case React extends OutboxOp("react")

object OutboxOp:
  def fromDb(db: String): OutboxOp =
    values.find(_.db == db).getOrElse(throw new IllegalArgumentException(s"unknown outbox op '$db'"))

enum OutboxStatus(val db: String):
  case Queued extends OutboxStatus("queued")
  case Sending extends OutboxStatus("sending")
  case Sent extends OutboxStatus("sent")
  case FailedRetry extends OutboxStatus("failed_retry")
  case FailedPermanent extends OutboxStatus("failed_permanent")
  case Cancelled extends OutboxStatus("cancelled")
  case Dead extends OutboxStatus("dead")

object OutboxStatus:
  def fromDb(db: String): OutboxStatus =
    values.find(_.db == db).getOrElse(throw new IllegalArgumentException(s"unknown outbox status '$db'"))

/** A row of `outbox_messages` as claimed by the dispatcher. `previousAttemptedAt` is the `attempted_at` value the row
  * carried *before* the claim that returned it (null for first attempts); it is what the `possible_duplicate` rule of
  * ADR-009 compares against, because the claim itself overwrites `attempted_at`.
  */
final case class OutboxMessage(
    id: UUID,
    sendKey: String,
    op: OutboxOp,
    kind: String,
    vendor: String,
    accountId: Option[UUID],
    occurrenceId: Option[UUID],
    channelId: Option[UUID],
    epoch: Option[Int],
    payload: String,
    target: Option[String],
    importance: String,
    status: OutboxStatus,
    attempts: Int,
    nextAttemptAt: Instant,
    leaseUntil: Option[Instant],
    attemptedAt: Option[Instant],
    previousAttemptedAt: Option[Instant],
    opsDone: Int,
    possibleDuplicate: Boolean,
    platformMessageId: Option[String],
    lastError: Option[String],
    createdAt: Instant,
    sentAt: Option[Instant]
)

/** What a producer enqueues. `payload` is an opaque JSON document to the core; the dispatcher's renderer interprets it.
  * `target` (non-send ops) is the encoded handle `chatId:messageId` of the message to act on.
  */
final case class NewOutboxMessage(
    id: UUID,
    sendKey: String,
    op: OutboxOp,
    kind: String,
    vendor: String,
    accountId: Option[UUID] = None,
    occurrenceId: Option[UUID] = None,
    channelId: Option[UUID] = None,
    epoch: Option[Int] = None,
    payload: String,
    target: Option[String] = None,
    importance: String = "info",
    nextAttemptAt: Instant
)

/** The outbox half of the per-event transaction (ADR-003). Claim/record are deliberately separate short transactions:
  * vendor calls happen with no lock held (ADR-009).
  */
trait OutboxRepository:
  /** Insert; false when a row with the same `send_key` already exists (ON CONFLICT DO NOTHING). */
  def enqueue(msg: NewOutboxMessage): Boolean

  /** Short claim (tx1 of DESIGN.md section 7.6): rows due for the owned vendors, `FOR UPDATE SKIP LOCKED`, flipped to
    * `sending` with `lease_until = now + lease`, `attempted_at = now` and `attempts + 1`. Rows stuck in `sending` are
    * re-claimable once their lease expired.
    */
  def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage]

  /** Records the primary handle right after the primary send, before any reaction sub-op; sets the send bit (0) of
    * `ops_done`.
    */
  def recordHandle(id: UUID, encodedHandle: String): Unit

  /** Sets bit `opIndex` of `ops_done` (bit 0 = primary send, bit i+1 = reaction i). */
  def markOpDone(id: UUID, opIndex: Int): Unit

  /** Terminal write of a successful dispatch; always the last write. */
  def markSent(id: UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit

  /** Reschedules after a retryable failure; at `MaxAttempts` the row goes `dead` instead (ADR-009). */
  def retry(id: UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit

  /** Non-retryable failure (`failed_permanent`); channel-fatal fallback is wired in M1.7. */
  def failPermanently(id: UUID, error: String): Unit

/** A `rendered_messages` row as the mediator's resolution rules need it (DESIGN.md section 4.6 step 5): the
  * `choice_map` plus whether finalize already removed the controls.
  */
final case class RenderedChoiceMap(
    handle: MessageHandle,
    revision: Int,
    choiceMap: List[ChoiceMapEntry],
    controlsRemoved: Boolean
)

/** Records rendered handles with their `choice_map` so controls stay resolvable after a crash (DESIGN.md section 9,
  * ADR-009: the post-resolution finalize op edits every recorded handle).
  */
trait RenderedMessageRepository:
  /** Insert; false when this (vendor, chat, message) is already recorded. */
  def record(
      handle: MessageHandle,
      accountId: Option[UUID],
      kind: String,
      subjectType: Option[String],
      subjectId: Option[UUID],
      epoch: Option[Int],
      choiceMap: List[ChoiceMapEntry],
      now: Instant
  ): Boolean

  /** Quoted-reply numbers and reactions resolve through the target's `choice_map` unconditionally (DESIGN.md section
    * 4.6 step 5).
    */
  def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap]

  /** Bare digits and hotkeys resolve against the latest pending prompt of the chat only: the newest message with a
    * `choice_map` whose controls were not removed.
    */
  def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap]
