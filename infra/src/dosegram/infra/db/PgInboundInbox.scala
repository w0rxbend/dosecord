package dosegram.infra.db

import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboxPurge
import dosegram.core.ports.PendingInboxEntry

import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `inbound_inbox` (ROADMAP G1.7, ARCHITECTURE section 7.2): one auto-committed statement per call, so a call that
  * returns has made its change durable.
  */
final class PgInboundInbox(dataSource: DataSource) extends InboundInbox:
  private val pooled = Pooled(dataSource)

  private given RowMapper[PendingInboxEntry] = rs =>
    PendingInboxEntry(
      InboxEntry(
        vendor = rs.getString("vendor"),
        vendorEventId = rs.getString("vendor_event_id"),
        chatKey = rs.getString("chat_key"),
        sequence = rs.getLong("sequence"),
        body = rs.getString("body")
      ),
      rs.instant("received_at")
    )

  override def store(entry: InboxEntry, receivedAt: Instant): Unit =
    pooled:
      sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, received_at)
            VALUES (${entry.vendor}, ${entry.vendorEventId}, ${entry.chatKey}, ${entry.sequence}, ${entry.body},
                    $receivedAt)
            ON CONFLICT (vendor, vendor_event_id) DO NOTHING""".execute()
    ()

  override def pending(perChat: Int, limit: Int): List[PendingInboxEntry] =
    pooled:
      sql"""SELECT vendor, vendor_event_id, chat_key, sequence, body, received_at
            FROM (SELECT *, row_number() OVER (PARTITION BY vendor, chat_key ORDER BY sequence) AS place
                  FROM inbound_inbox WHERE status = 'pending') AS heads
            WHERE place <= $perChat
            ORDER BY place, vendor, chat_key
            LIMIT $limit""".query[PendingInboxEntry]()

  override def markProcessed(entry: InboxEntry, at: Instant): Unit =
    pooled:
      sql"""UPDATE inbound_inbox SET status = 'processed', body = NULL, finished_at = $at
            WHERE vendor = ${entry.vendor} AND vendor_event_id = ${entry.vendorEventId} AND status = 'pending'"""
        .execute()
    ()

  override def recordFailure(entry: InboxEntry, maxAttempts: Int, at: Instant): Boolean =
    pooled:
      given RowMapper[String] = _.getString(1)
      sql"""UPDATE inbound_inbox
            SET attempts = attempts + 1,
                status = CASE WHEN attempts + 1 >= $maxAttempts THEN 'quarantined' ELSE 'pending' END,
                finished_at = CASE WHEN attempts + 1 >= $maxAttempts THEN $at END
            WHERE vendor = ${entry.vendor} AND vendor_event_id = ${entry.vendorEventId} AND status = 'pending'
            RETURNING status""".queryOne[String]().contains("quarantined")

  override def purge(now: Instant): InboxPurge =
    val bodiesBefore = now.minus(InboundInbox.BodyLifetime)
    val rowsBefore = now.minus(InboundInbox.RowLifetime)
    pooled:
      val bodies =
        sql"""UPDATE inbound_inbox
              SET body = NULL,
                  status = CASE WHEN status = 'pending' THEN 'expired' ELSE status END,
                  finished_at = coalesce(finished_at, $now)
              WHERE body IS NOT NULL AND received_at <= $bodiesBefore""".execute()
      val inboxRows = sql"DELETE FROM inbound_inbox WHERE received_at <= $rowsBefore".execute()
      val events = sql"DELETE FROM inbound_events WHERE received_at <= $rowsBefore".execute()
      InboxPurge(bodies, inboxRows, events)
