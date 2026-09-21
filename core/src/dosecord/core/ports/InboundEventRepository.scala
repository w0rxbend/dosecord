package dosecord.core.ports

import java.time.Instant
import java.util.UUID

/** What the mediator inserts at step 3 of DESIGN.md section 4.6, before identity resolution. */
final case class NewInboundEvent(vendor: String, vendorEventId: String, eventId: UUID, receivedAt: Instant)

/** A stored `inbound_events` row; `reply` is the raw JSON document replayed on a duplicate delivery (C3). */
final case class StoredInboundEvent(
    vendor: String,
    vendorEventId: String,
    eventId: UUID,
    accountId: Option[UUID],
    receivedAt: Instant,
    reply: Option[String],
    processedAt: Option[Instant]
)

/** `inbound_events` (ADR-003): idempotency with the replayed reply. `(vendor, vendor_event_id)` is the primary key
  * (R60, R64).
  */
trait InboundEventRepository:
  /** Insert; false when this `(vendor, vendor_event_id)` already exists — the stored reply is replayed instead. */
  def insert(event: NewInboundEvent): Boolean

  def find(vendor: String, vendorEventId: String): Option[StoredInboundEvent]

  /** The processed half of the per-event transaction: stores the reply and the resolved account id (C2, C3). */
  def complete(
      vendor: String,
      vendorEventId: String,
      accountId: Option[UUID],
      reply: String,
      processedAt: Instant
  ): Unit
