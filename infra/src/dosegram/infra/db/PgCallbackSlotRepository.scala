package dosegram.infra.db

import dosegram.core.ports.CallbackSlot
import dosegram.core.ports.CallbackSlotRepository
import dosegram.core.ports.KeyService

import java.sql.Connection
import scala.language.implicitConversions

/** `callback_slots`. The state behind a button is sealed (V21, ROADMAP G2.4b) with the slot's account key, or the
  * transport service key for a button of the onboarding.
  */
final class PgCallbackSlotRepository(conn: Connection, keys: KeyService) extends CallbackSlotRepository:
  private given Connection = conn
  import SealedColumns.SlotPayload

  private given RowMapper[CallbackSlot] = rs =>
    val stepSeqRaw = rs.getInt("step_seq")
    val stepSeqNull = rs.wasNull
    val id = rs.uuid("id")
    val accountId = Option(rs.getObject("account_id", classOf[java.util.UUID]))
    CallbackSlot(
      id = id,
      accountId = accountId,
      chat = rs.getString("chat"),
      payload = Option(rs.getBytes("payload_enc"))
        .map(keys.openText(SlotPayload.forRow(accountId, id), _))
        .getOrElse(rs.getString("payload")),
      sessionId = Option(rs.getObject("session_id", classOf[java.util.UUID])),
      stepSeq = if stepSeqNull then None else Some(stepSeqRaw),
      expiresAt = rs.optInstant("expires_at")
    )

  override def insert(slot: CallbackSlot): Unit =
    val sealedPayload = keys.sealText(SlotPayload.forRow(slot.accountId, slot.id), slot.payload)
    sql"""INSERT INTO callback_slots (id, account_id, chat, payload_enc, session_id, step_seq, expires_at)
          VALUES (${slot.id}, ${slot.accountId}, ${Jsonb(slot.chat)}, $sealedPayload,
                  ${slot.sessionId}, ${slot.stepSeq}, ${slot.expiresAt})""".execute()

  override def loadForUpdate(id: java.util.UUID): Option[CallbackSlot] =
    sql"""SELECT id, account_id, chat, payload, payload_enc, session_id, step_seq, expires_at
          FROM callback_slots WHERE id = $id FOR UPDATE""".queryOne[CallbackSlot]()

  override def deleteForSession(sessionId: java.util.UUID): Int =
    sql"DELETE FROM callback_slots WHERE session_id = $sessionId".execute()
