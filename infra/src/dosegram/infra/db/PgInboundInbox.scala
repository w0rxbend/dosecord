package dosegram.infra.db

import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboxPurge
import dosegram.core.ports.KeyService
import dosegram.core.ports.PendingInboxEntry
import dosegram.infra.crypto.DataKeys

import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `inbound_inbox` (ROADMAP G1.7, ARCHITECTURE section 7.2): one auto-committed statement per call, so a call that
  * returns has made its change durable. The body is the message as received, so it is sealed with the transport service
  * key (V21, ROADMAP G2.4b): a row is keyed by chat before any account exists.
  */
final class PgInboundInbox(dataSource: DataSource, keys: DataKeys) extends InboundInbox:
  import SealedColumns.InboxBody

  private val pooled = Pooled(dataSource)

  private def pendingEntry(keyService: KeyService): RowMapper[PendingInboxEntry] = rs =>
    val vendorEventId = rs.getString("vendor_event_id")
    val body = Option(rs.getBytes("body_enc"))
      .map(keyService.openText(InboxBody.forService(vendorEventId), _))
      .getOrElse(rs.getString("body"))
    PendingInboxEntry(
      InboxEntry(
        vendor = rs.getString("vendor"),
        vendorEventId = vendorEventId,
        chatKey = rs.getString("chat_key"),
        sequence = rs.getLong("sequence"),
        body = body
      ),
      rs.instant("received_at")
    )

  override def store(entry: InboxEntry, receivedAt: Instant): Unit =
    pooled: conn ?=>
      val sealedBody = keys.on(conn).sealText(InboxBody.forService(entry.vendorEventId), entry.body)
      sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body_enc, received_at)
            VALUES (${entry.vendor}, ${entry.vendorEventId}, ${entry.chatKey}, ${entry.sequence}, $sealedBody,
                    $receivedAt)
            ON CONFLICT (vendor, vendor_event_id) DO NOTHING""".execute()
    ()

  override def pending(perChat: Int, limit: Int): List[PendingInboxEntry] =
    pooled: conn ?=>
      given RowMapper[PendingInboxEntry] = pendingEntry(keys.on(conn))
      sql"""SELECT vendor, vendor_event_id, chat_key, sequence, body, body_enc, received_at
            FROM (SELECT *, row_number() OVER (PARTITION BY vendor, chat_key ORDER BY sequence) AS place
                  FROM inbound_inbox WHERE status = 'pending') AS heads
            WHERE place <= $perChat
            ORDER BY place, vendor, chat_key
            LIMIT $limit""".query[PendingInboxEntry]()

  override def markProcessed(entry: InboxEntry, at: Instant): Unit =
    pooled:
      sql"""UPDATE inbound_inbox SET status = 'processed', body = NULL, body_enc = NULL, finished_at = $at
            WHERE vendor = ${entry.vendor} AND vendor_event_id = ${entry.vendorEventId} AND status = 'pending'"""
        .execute()
    ()

  override def recordFailure(entry: InboxEntry, maxAttempts: Int, at: Instant, reason: String): Boolean =
    pooled:
      given RowMapper[String] = _.getString(1)
      sql"""UPDATE inbound_inbox
            SET attempts = attempts + 1, failure_reason = $reason,
                status = CASE WHEN attempts + 1 >= $maxAttempts THEN 'quarantined' ELSE 'pending' END,
                finished_at = CASE WHEN attempts + 1 >= $maxAttempts THEN $at END
            WHERE vendor = ${entry.vendor} AND vendor_event_id = ${entry.vendorEventId} AND status = 'pending'
            RETURNING status""".queryOne[String]().contains("quarantined")

  override def forget(entry: InboxEntry): Unit =
    pooled:
      sql"""DELETE FROM inbound_inbox WHERE vendor = ${entry.vendor} AND vendor_event_id = ${entry.vendorEventId}"""
        .execute()
    ()

  override def purge(now: Instant): InboxPurge =
    val bodiesBefore = now.minus(InboundInbox.BodyLifetime)
    val rowsBefore = now.minus(InboundInbox.RowLifetime)
    pooled:
      val bodies =
        sql"""UPDATE inbound_inbox
              SET body = NULL, body_enc = NULL,
                  status = CASE WHEN status = 'pending' THEN 'expired' ELSE status END,
                  finished_at = coalesce(finished_at, $now)
              WHERE (body IS NOT NULL OR body_enc IS NOT NULL) AND received_at <= $bodiesBefore""".execute()
      val inboxRows = sql"DELETE FROM inbound_inbox WHERE received_at <= $rowsBefore".execute()
      val events = sql"DELETE FROM inbound_events WHERE received_at <= $rowsBefore".execute()
      InboxPurge(bodies, inboxRows, events)
