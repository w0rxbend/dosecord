package dosegram.core.ports

import java.time.Duration
import java.time.Instant

/** One inbound update as the inbox keeps it until it is processed (ARCHITECTURE section 7.2, ROADMAP G1.7).
  * `vendorEventId` is the key the mediator's `inbound_events` uses too; `sequence` orders the updates of one `chatKey`;
  * `body` is the vendor's own encoding, opaque here.
  */
final case class InboxEntry(vendor: String, vendorEventId: String, chatKey: String, sequence: Long, body: String)

/** A pending entry with the time it was stored, which is when the update was received. */
final case class PendingInboxEntry(entry: InboxEntry, receivedAt: Instant)

/** What one run of the deleting job removed. */
final case class InboxPurge(bodies: Int, inboxRows: Int, inboundEvents: Int)

/** The durable inbox (ARCHITECTURE sections 7.2.1 to 7.2.3): an update is stored before it is acknowledged to the
  * vendor and processed after. A store of the edges: each call is its own short statement, never part of the
  * transaction of a chat event. Every method throws `java.sql.SQLException` when the database fails.
  */
trait InboundInbox:

  /** Stores the entry unless one with its key exists. When this returns, the entry is durable. */
  def store(entry: InboxEntry, receivedAt: Instant): Unit

  /** Per chat, up to `perChat` pending entries in `sequence` order, the chats in no particular order; at most `limit`
    * entries in all.
    */
  def pending(perChat: Int, limit: Int): List[PendingInboxEntry]

  /** The entry's effect is durable: its body is deleted and it is never offered again. */
  def markProcessed(entry: InboxEntry, at: Instant): Unit

  /** Counts a failed attempt; the entry is quarantined at `maxAttempts`. True when it was quarantined now. */
  def recordFailure(entry: InboxEntry, maxAttempts: Int, at: Instant): Boolean

  /** The deleting job (ARCHITECTURE section 15.6): every body older than [[InboundInbox.BodyLifetime]], a pending one
    * included, and every inbox and `inbound_events` row older than [[InboundInbox.RowLifetime]].
    */
  def purge(now: Instant): InboxPurge

object InboundInbox:
  val BodyLifetime: Duration = Duration.ofHours(24)
  val RowLifetime: Duration = Duration.ofDays(7)
