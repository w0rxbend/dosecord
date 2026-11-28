package dosegram.infra.db

import dosegram.core.ports.KeyService
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.OutboxRepository
import dosegram.core.ports.OutboxStatus
import dosegram.core.scheduling.OutboxDispatcher

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import scala.language.implicitConversions

/** Postgres outbox over `outbox_messages` (ADR-003/009): SKIP LOCKED claiming with a lease, UNIQUE `send_key`,
  * two-phase handle/ops_done recording. The payload is sealed with `keys` (V13): the row's account key, or the
  * transport service key for a row written before the account existed. The asset keys it names and the chat it goes to
  * are kept beside it, so the erasure job finds them without opening it.
  */
final class PgOutboxRepository(conn: Connection, keys: KeyService) extends OutboxRepository:
  private given Connection = conn
  // A claimed row carries the chat it was kept with, which the dispatcher reads to forget an orphaned answer.
  private given RowMapper[OutboxMessage] = rs =>
    PgOutboxRepository.rowMapper(keys).read(rs).copy(chatKey = rs.optString("chat_key"))
  import SealedColumns.OutboxPayload

  override def recordedHandle(vendor: String, sendKey: String): Option[String] =
    sql"""SELECT platform_message_id FROM outbox_messages
          WHERE vendor = $vendor AND send_key = $sendKey AND platform_message_id IS NOT NULL""".queryOne[String]()

  override def replyId(vendor: String, sendKey: String): Option[java.util.UUID] =
    sql"""SELECT id FROM outbox_messages
          WHERE vendor = $vendor AND send_key = $sendKey AND kind = 'interaction_reply'""".queryOne[java.util.UUID]()

  override def enqueue(msg: NewOutboxMessage): Boolean = insert(msg)

  /** The digest fold (DESIGN.md section 7.5, M1.8): insert the digest row, or merge the payload's `items` into the
    * still-queued row with the same `send_key` (one digest per account per 15-minute bucket), keeping the later
    * `next_attempt_at` (a quiet-deferred missed notice folds its `not_before` into the digest). A row that already left
    * the queue (`sending`, `sent`, `dead`, `cancelled`) keeps its state and the late items are dropped — the bucket's
    * digest was delivered or is being delivered. The sealed payload is opened, merged and sealed again under the row
    * lock: a concurrent insert of the same key waits on the unique index and then finds the row here.
    */
  override def enqueueDigest(msg: NewOutboxMessage): Boolean =
    insert(msg) || {
      given RowMapper[(java.util.UUID, Option[java.util.UUID], String)] = rs =>
        val id = rs.uuid("id")
        val account = Option(rs.getObject("account_id", classOf[java.util.UUID]))
        (id, account, PgOutboxRepository.payloadOf(keys, rs, id, account))
      sql"""SELECT id, account_id, payload, payload_enc FROM outbox_messages
            WHERE send_key = ${msg.sendKey} AND status IN ('queued', 'failed_retry')
            FOR UPDATE""".queryOne[(java.util.UUID, Option[java.util.UUID], String)]() match
        case None                      => false
        case Some((id, account, held)) =>
          val merged = PgOutboxRepository.mergeItems(held, msg.payload)
          sql"""UPDATE outbox_messages
                SET payload = NULL, payload_enc = ${keys.sealText(OutboxPayload.forRow(account, id), merged)},
                    asset_keys = ${PgOutboxRepository.assetKeys(merged).toArray},
                    next_attempt_at = GREATEST(next_attempt_at, ${msg.nextAttemptAt})
                WHERE id = $id""".execute() == 1
    }

  private def insert(msg: NewOutboxMessage): Boolean =
    val sealedPayload = keys.sealText(OutboxPayload.forRow(msg.accountId, msg.id), msg.payload)
    sql"""INSERT INTO outbox_messages
            (id, send_key, op, kind, vendor, account_id, occurrence_id, channel_id, epoch,
             payload_enc, asset_keys, chat_key, target, importance, status, next_attempt_at, follows)
          VALUES (${msg.id}, ${msg.sendKey}, ${msg.op.db}, ${msg.kind}, ${msg.vendor}, ${msg.accountId},
                  ${msg.occurrenceId}, ${msg.channelId}, ${msg.epoch}, $sealedPayload,
                  ${PgOutboxRepository.assetKeys(msg.payload).toArray}, ${msg.chatId},
                  ${msg.target.map(t => Jsonb(PgOutboxRepository.wrapJsonString(t)))}, ${msg.importance},
                  'queued', ${msg.nextAttemptAt}, ${msg.follows})
          ON CONFLICT (send_key) DO NOTHING""".execute() == 1

  override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] =
    val leaseUntil = now.plus(lease)
    val vendorArray = vendors.toArray
    // A row whose op a newer release wrote stays queued, unclaimed, for that release (rollback safety, supervisor
    // decision of 2026-11-28), as a row of a kind this release does not render does. A row that follows it waits with
    // it for one recheck, then goes without it.
    val knownOps = OutboxOp.values.map(_.db)
    val recheckedBy = now.minus(OutboxDispatcher.UnknownKindRecheck)
    sql"""WITH due AS (
            SELECT id, attempted_at AS prev_attempted_at
            FROM outbox_messages m
            WHERE vendor = ANY($vendorArray)
              AND op = ANY($knownOps)
              AND next_attempt_at <= $now
              AND (status IN ('queued', 'failed_retry') OR (status = 'sending' AND lease_until < $now))
              AND NOT EXISTS (SELECT 1 FROM outbox_messages earlier
                              WHERE earlier.id = m.follows AND earlier.status IN ('queued', 'failed_retry', 'sending')
                                AND NOT (earlier.op <> ALL($knownOps) AND m.next_attempt_at <= $recheckedBy))
            ORDER BY next_attempt_at
            LIMIT $limit
            FOR UPDATE SKIP LOCKED
          )
          UPDATE outbox_messages o
          SET status = 'sending', lease_until = $leaseUntil, attempted_at = $now, attempts = o.attempts + 1
          FROM due
          WHERE o.id = due.id
          RETURNING o.id, o.send_key, o.op, o.kind, o.vendor, o.account_id, o.occurrence_id, o.channel_id,
                    o.epoch, o.payload, o.payload_enc, o.target, o.importance, o.status, o.attempts, o.next_attempt_at,
                    o.lease_until, o.attempted_at, due.prev_attempted_at, o.ops_done, o.possible_duplicate,
                    o.platform_message_id, o.last_error, o.created_at, o.sent_at, o.chat_key"""
      .query[OutboxMessage]()

  override def recordHandle(id: java.util.UUID, encodedHandle: String): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET platform_message_id = $encodedHandle, ops_done = ops_done | 1
            WHERE id = $id""".execute()
    require(updated == 1, s"outbox row $id vanished before recordHandle")

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

  override def leaveQueued(id: java.util.UUID, at: Instant): Unit =
    val updated =
      sql"""UPDATE outbox_messages
            SET status = 'queued', next_attempt_at = $at, lease_until = NULL,
                attempts = GREATEST(attempts - 1, 0),
                attempted_at = CASE WHEN attempts <= 1 THEN NULL ELSE attempted_at END
            WHERE id = $id AND status = 'sending'""".execute()
    require(updated == 1, s"outbox row $id vanished before it was left queued")

  override def releaseFollowers(id: java.util.UUID, dueBy: Instant): Int =
    sql"""UPDATE outbox_messages SET follows = NULL
          WHERE follows = $id AND status IN ('queued', 'failed_retry') AND next_attempt_at <= $dueBy""".execute()

  override def forgetHandle(id: java.util.UUID): Unit =
    sql"""UPDATE outbox_messages SET platform_message_id = NULL, target = NULL
          WHERE id = $id AND account_id IS NULL AND chat_key IS NULL AND status = 'sent'""".execute()
    ()

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

  override def cancelQueued(sendKey: String): Boolean =
    sql"""UPDATE outbox_messages
          SET status = 'cancelled', lease_until = NULL
          WHERE send_key = $sendKey AND status IN ('queued', 'failed_retry')""".execute() == 1

  override def recentSendKeys(accountId: java.util.UUID, prefix: String, since: Instant): List[String] =
    sql"""SELECT send_key FROM outbox_messages
          WHERE account_id = $accountId AND starts_with(send_key, $prefix) AND next_attempt_at >= $since
          ORDER BY next_attempt_at DESC, send_key""".query[String]()

  override def sendInFlight(sendKey: String): Boolean =
    sql"""SELECT 1 FROM outbox_messages WHERE send_key = $sendKey AND status = 'sending'""".queryOne[Int]().nonEmpty

object PgOutboxRepository:
  /** Reads every column the dispatcher needs, opening the payload with `keys`; the query must also project
    * `prev_attempted_at` (e.g. `attempted_at AS prev_attempted_at` for plain reads) and both `payload` columns.
    */
  def rowMapper(keys: KeyService): RowMapper[OutboxMessage] = rs =>
    val epoch = rs.getInt("epoch")
    // wasNull must be read immediately: it reflects the last column read, and the named arguments below are
    // evaluated in order (a later NULL channel_id would otherwise mask the epoch).
    val epochIsNull = rs.wasNull()
    val id = rs.uuid("id")
    val accountId = Option(rs.getObject("account_id", classOf[java.util.UUID]))
    OutboxMessage(
      id = id,
      sendKey = rs.getString("send_key"),
      op = OutboxOp.fromDb(rs.getString("op")),
      kind = rs.getString("kind"),
      vendor = rs.getString("vendor"),
      accountId = accountId,
      occurrenceId = Option(rs.getObject("occurrence_id", classOf[java.util.UUID])),
      channelId = Option(rs.getObject("channel_id", classOf[java.util.UUID])),
      epoch = Option.when(!epochIsNull)(epoch),
      payload = payloadOf(keys, rs, id, accountId),
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

  /** The payload of the current row: the sealed column, or the plaintext of a row the previous release wrote, which the
    * backfill of the next start seals.
    */
  private[db] def payloadOf(
      keys: KeyService,
      rs: java.sql.ResultSet,
      id: java.util.UUID,
      account: Option[java.util.UUID]
  ): String =
    Option(rs.getBytes("payload_enc"))
      .map(keys.openText(SealedColumns.OutboxPayload.forRow(account, id), _))
      .getOrElse(rs.getString("payload"))

  /** Every `assetKey` the payload names, at any depth: the images the message sends. */
  private[db] def assetKeys(payload: String): List[String] =
    def walk(value: ujson.Value): List[String] = value match
      case ujson.Obj(fields) =>
        fields.toList.flatMap:
          case ("assetKey", ujson.Str(key)) => List(key)
          case (_, nested)                  => walk(nested)
      case ujson.Arr(values) => values.toList.flatMap(walk)
      case _                 => Nil
    walk(ujson.read(payload)).distinct

  /** The payload `held` with the `items` of `incoming` appended to its own. */
  private[db] def mergeItems(held: String, incoming: String): String =
    val merged = ujson.read(held)
    val items = (field: ujson.Value) => field.obj.get("items").map(_.arr.toList).getOrElse(Nil)
    merged("items") = ujson.Arr.from(items(merged) ++ items(ujson.read(incoming)))
    ujson.write(merged)

  /** `target` is a jsonb column; M0.10 stores the encoded handle as a plain JSON string (structured target payloads
    * land in M1.7).
    */
  private def wrapJsonString(s: String): String = "\"" + s + "\""
  private def unwrapJsonString(s: String): String =
    if s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") then s.substring(1, s.length - 1) else s
