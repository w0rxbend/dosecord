package dosecord.infra.db

import dosecord.core.ports.InboundEventRepository
import dosecord.core.ports.NewInboundEvent
import dosecord.core.ports.StoredInboundEvent

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

final class PgInboundEventRepository(conn: Connection) extends InboundEventRepository:
  private given Connection = conn

  private given RowMapper[StoredInboundEvent] = rs =>
    StoredInboundEvent(
      vendor = rs.getString("vendor"),
      vendorEventId = rs.getString("vendor_event_id"),
      eventId = rs.uuid("event_id"),
      accountId = Option(rs.getObject("account_id", classOf[UUID])),
      receivedAt = rs.instant("received_at"),
      reply = rs.optString("reply"),
      processedAt = rs.optInstant("processed_at")
    )

  override def insert(event: NewInboundEvent): Boolean =
    sql"""INSERT INTO inbound_events (vendor, vendor_event_id, event_id, received_at)
          VALUES (${event.vendor}, ${event.vendorEventId}, ${event.eventId}, ${event.receivedAt})
          ON CONFLICT DO NOTHING""".execute() == 1

  override def find(vendor: String, vendorEventId: String): Option[StoredInboundEvent] =
    sql"""SELECT vendor, vendor_event_id, event_id, account_id, received_at, reply, processed_at
          FROM inbound_events
          WHERE vendor = $vendor AND vendor_event_id = $vendorEventId""".queryOne[StoredInboundEvent]()

  override def complete(
      vendor: String,
      vendorEventId: String,
      accountId: Option[UUID],
      reply: String,
      processedAt: java.time.Instant
  ): Unit =
    sql"""UPDATE inbound_events
          SET reply = ${Jsonb(reply)}, processed_at = $processedAt, account_id = $accountId
          WHERE vendor = $vendor AND vendor_event_id = $vendorEventId""".execute()
