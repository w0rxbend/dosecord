package dosegram.infra.db

import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource
import scala.language.implicitConversions

/** The outbox rows that are due and not yet delivered, and when the oldest of them fell due. */
final case class OutboxBacklog(pending: Long, oldestDueAt: Option[Instant])

/** Read-only operational probes for `/readyz` and the owner alerts (ARCHITECTURE section 18, OPS-A2): counts and
  * instants only, never a payload, an error text or an account id. Every query binds `now`; none calls SQL `now()`.
  */
final class PgOpsHealth(dataSource: DataSource):
  private val pooled = Pooled(dataSource)

  /** Rows waiting to be sent whose attempt is due: queued, put back for a retry, or claimed and not finished. */
  def outboxBacklog(now: Instant): OutboxBacklog =
    given RowMapper[OutboxBacklog] = rs =>
      OutboxBacklog(rs.getLong(1), Option(rs.getObject(2, classOf[OffsetDateTime])).map(_.toInstant))
    pooled:
      sql"""SELECT count(*), min(next_attempt_at) FROM outbox_messages
            WHERE status IN ('queued', 'failed_retry', 'sending') AND next_attempt_at <= $now"""
        .queryOne[OutboxBacklog]()
        .getOrElse(OutboxBacklog(0, None))

  /** Outbox rows that went `dead` after `since`; a dead row's last attempt is when it died. */
  def deadSince(since: Instant): Long = pooled:
    sql"""SELECT count(*) FROM outbox_messages
          WHERE status = 'dead' AND COALESCE(attempted_at, created_at) > $since""".queryOne[Long]().getOrElse(0L)

  /** Inbox updates quarantined after `since`. */
  def quarantinedSince(since: Instant): Long = pooled:
    sql"""SELECT count(*) FROM inbound_inbox
          WHERE status = 'quarantined' AND finished_at > $since""".queryOne[Long]().getOrElse(0L)
