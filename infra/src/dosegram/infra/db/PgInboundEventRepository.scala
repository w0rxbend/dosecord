package dosegram.infra.db

import dosegram.core.ports.InboundEventRepository
import dosegram.core.ports.KeyService
import dosegram.core.ports.NewInboundEvent
import dosegram.core.ports.StoredInboundEvent

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** `inbound_events` over Postgres. The stored reply is sealed with `keys` (V13): the row's account key, or the
  * transport service key while the chat has no account; a plaintext reply of the previous release is read as it is
  * until the backfill of the next start seals it.
  */
final class PgInboundEventRepository(conn: Connection, keys: KeyService) extends InboundEventRepository:
  private given Connection = conn
  import SealedColumns.InboundReply

  private given RowMapper[StoredInboundEvent] = rs =>
    val eventId = rs.uuid("event_id")
    val accountId = Option(rs.getObject("account_id", classOf[UUID]))
    StoredInboundEvent(
      vendor = rs.getString("vendor"),
      vendorEventId = rs.getString("vendor_event_id"),
      eventId = eventId,
      accountId = accountId,
      receivedAt = rs.instant("received_at"),
      reply = Option(rs.getBytes("reply_enc"))
        .map(keys.openText(InboundReply.forRow(accountId, eventId), _))
        .orElse(rs.optString("reply")),
      processedAt = rs.optInstant("processed_at")
    )

  override def insert(event: NewInboundEvent): Boolean =
    sql"""INSERT INTO inbound_events (vendor, vendor_event_id, event_id, received_at, account_id)
          VALUES (${event.vendor}, ${event.vendorEventId}, ${event.eventId}, ${event.receivedAt}, ${event.accountId})
          ON CONFLICT DO NOTHING""".execute() == 1

  override def find(vendor: String, vendorEventId: String): Option[StoredInboundEvent] =
    sql"""SELECT vendor, vendor_event_id, event_id, account_id, received_at, reply, reply_enc, processed_at
          FROM inbound_events
          WHERE vendor = $vendor AND vendor_event_id = $vendorEventId""".queryOne[StoredInboundEvent]()

  override def complete(
      vendor: String,
      vendorEventId: String,
      accountId: Option[UUID],
      chatId: Option[String],
      reply: String,
      processedAt: java.time.Instant
  ): Unit =
    val eventId = sql"""SELECT event_id FROM inbound_events
                        WHERE vendor = $vendor AND vendor_event_id = $vendorEventId FOR UPDATE""".queryOne[UUID]()
    eventId.foreach: id =>
      sql"""UPDATE inbound_events
            SET reply = NULL, reply_enc = ${keys.sealText(InboundReply.forRow(accountId, id), reply)},
                processed_at = $processedAt, account_id = $accountId, chat_key = $chatId
            WHERE vendor = $vendor AND vendor_event_id = $vendorEventId""".execute()
