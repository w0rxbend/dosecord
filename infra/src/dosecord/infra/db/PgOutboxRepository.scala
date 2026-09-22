package dosecord.infra.db

import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.OutboxRepository
import dosecord.core.ports.OutboxStatus

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import scala.language.implicitConversions

/** Postgres outbox over `outbox_messages` (ADR-003/009): SKIP LOCKED claiming with a lease, UNIQUE `send_key`,
  * two-phase handle/ops_done recording.
  */
final class PgOutboxRepository(conn: Connection) extends OutboxRepository:
  private given Connection = conn
  import PgOutboxRepository.given

  override def enqueue(msg: NewOutboxMessage): Boolean =
    sql"""INSERT INTO outbox_messages
            (id, send_key, op, kind, vendor, account_id, occurrence_id, channel_id, epoch,
             payload, target, importance, status, next_attempt_at)
          VALUES (${msg.id}, ${msg.sendKey}, ${msg.op.db}, ${msg.kind}, ${msg.vendor}, ${msg.accountId},
                  ${msg.occurrenceId}, ${msg.channelId}, ${msg.epoch}, ${Jsonb(msg.payload)},
                  ${msg.target.map(t => Jsonb(PgOutboxRepository.wrapJsonString(t)))}, ${msg.importance},
                  'queued', ${msg.nextAttemptAt})
          ON CONFLICT (send_key) DO NOTHING""".execute() == 1

  override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] =
    val leaseUntil = now.plus(lease)
    val vendorArray = vendors.toArray
    sql"""WITH due AS (
            SELECT id, attempted_at AS prev_attempted_at
            FROM outbox_messages
            WHERE vendor = ANY($vendorArray)
              AND next_attempt_at <= $now
              AND (status IN ('queued', 'failed_retry') OR (status = 'sending' AND lease_until < $now))
            ORDER BY next_attempt_at
            LIMIT $limit
            FOR UPDATE SKIP LOCKED
          )
          UPDATE outbox_messages o
          SET status = 'sending', lease_until = $leaseUntil, attempted_at = $now, attempts = o.attempts + 1
          FROM due
          WHERE o.id = due.id
          RETURNING o.id, o.send_key, o.op, o.kind, o.vendor, o.account_id, o.occurrence_id, o.channel_id,
                    o.epoch, o.payload, o.target, o.importance, o.status, o.attempts, o.next_attempt_at,
                    o.lease_until, o.attempted_at, due.prev_attempted_at, o.ops_done, o.possible_duplicate,
                    o.platform_message_id, o.last_error, o.created_at, o.sent_at""".query[OutboxMessage]()

  override def recordHandle(id: java.util.UUID, encodedHandle: String): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET platform_message_id = $encodedHandle, ops_done = ops_done | 1
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before recordHandle")

  override def markOpDone(id: java.util.UUID, opIndex: Int): Unit =
    val bit = 1 << opIndex
    val updated =
      sql"""UPDATE outbox_messages
            SET ops_done = ops_done | $bit
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before markOpDone")

  override def markSent(id: java.util.UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = 'sent', sent_at = $now, platform_message_id = $encodedHandle, lease_until = NULL,
                possible_duplicate = possible_duplicate OR $possibleDuplicate
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before markSent")

  override def retry(id: java.util.UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = CASE WHEN attempts >= 8 THEN 'dead' ELSE 'failed_retry' END,
                next_attempt_at = $at, lease_until = NULL,
                possible_duplicate = possible_duplicate OR $possibleDuplicate,
                last_error = $error
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before retry")

  override def dead(id: java.util.UUID, error: String): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = 'dead', lease_until = NULL, last_error = $error
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before dead")

  override def failPermanently(id: java.util.UUID, error: String): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = 'failed_permanent', lease_until = NULL, last_error = $error
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before failPermanently")

  override def cancel(id: java.util.UUID): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = 'cancelled', lease_until = NULL
            WHERE id = $id AND status IN ('queued', 'failed_retry', 'sending')""".execute()
    require(updated == 1, s"outbox row $id vanished before cancel")

  override def deliveredFor(occurrenceId: java.util.UUID): Boolean =
    sql"""SELECT EXISTS(
            SELECT 1 FROM outbox_messages
            WHERE occurrence_id = $occurrenceId AND sent_at IS NOT NULL
          )""".queryOne[Boolean]().getOrElse(false)

  override def cancelOlderQueued(occurrenceId: java.util.UUID, epoch: Int): Int =
    sql"""UPDATE outbox_messages
          SET status = 'cancelled', lease_until = NULL
          WHERE occurrence_id = $occurrenceId AND status = 'queued'
            AND (epoch IS NULL OR epoch < $epoch)""".execute()

object PgOutboxRepository:
  /** Reads every column the dispatcher needs; the query must also project `prev_attempted_at` (e.g.
    * `attempted_at AS prev_attempted_at` for plain reads).
    */
  given RowMapper[OutboxMessage] = rs =>
    val epoch = rs.getInt("epoch")
    // wasNull must be read immediately: it reflects the last column read, and the named arguments below are
    // evaluated in order (a later NULL channel_id would otherwise mask the epoch).
    val epochIsNull = rs.wasNull()
    OutboxMessage(
      id = rs.uuid("id"),
      sendKey = rs.getString("send_key"),
      op = OutboxOp.fromDb(rs.getString("op")),
      kind = rs.getString("kind"),
      vendor = rs.getString("vendor"),
      accountId = Option(rs.getObject("account_id", classOf[java.util.UUID])),
      occurrenceId = Option(rs.getObject("occurrence_id", classOf[java.util.UUID])),
      channelId = Option(rs.getObject("channel_id", classOf[java.util.UUID])),
      epoch = Option.when(!epochIsNull)(epoch),
      payload = rs.getString("payload"),
      target = rs.optString("target").map(unwrapJsonString),
      importance = rs.getString("importance"),
      status = OutboxStatus.fromDb(rs.getString("status")),
      attempts = rs.getInt("attempts"),
      nextAttemptAt = rs.instant("next_attempt_at"),
      leaseUntil = rs.optInstant("lease_until"),
      attemptedAt = rs.optInstant("attempted_at"),
      previousAttemptedAt = rs.optInstant("prev_attempted_at"),
      opsDone = rs.getInt("ops_done"),
      possibleDuplicate = rs.getBoolean("possible_duplicate"),
      platformMessageId = rs.optString("platform_message_id"),
      lastError = rs.optString("last_error"),
      createdAt = rs.instant("created_at"),
      sentAt = rs.optInstant("sent_at")
    )

  /** `target` is a jsonb column; M0.10 stores the encoded handle as a plain JSON string (structured target payloads
    * land in M1.7).
    */
  private def wrapJsonString(s: String): String = "\"" + s + "\""
  private def unwrapJsonString(s: String): String =
    if s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") then s.substring(1, s.length - 1) else s
