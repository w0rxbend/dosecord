package dosecord.infra.db

import dosecord.core.ports.CallbackSlot
import dosecord.core.ports.CallbackSlotRepository

import java.sql.Connection
import scala.language.implicitConversions

final class PgCallbackSlotRepository(conn: Connection) extends CallbackSlotRepository:
  private given Connection = conn

  private given RowMapper[CallbackSlot] = rs =>
    val stepSeqRaw = rs.getInt("step_seq")
    val stepSeqNull = rs.wasNull
    CallbackSlot(
      id = rs.uuid("id"),
      accountId = Option(rs.getObject("account_id", classOf[java.util.UUID])),
      chat = rs.getString("chat"),
      payload = rs.getString("payload"),
      sessionId = Option(rs.getObject("session_id", classOf[java.util.UUID])),
      stepSeq = if stepSeqNull then None else Some(stepSeqRaw),
      expiresAt = rs.optInstant("expires_at")
    )

  override def insert(slot: CallbackSlot): Unit =
    sql"""INSERT INTO callback_slots (id, account_id, chat, payload, session_id, step_seq, expires_at)
          VALUES (${slot.id}, ${slot.accountId}, ${Jsonb(slot.chat)}, ${Jsonb(slot.payload)},
                  ${slot.sessionId}, ${slot.stepSeq}, ${slot.expiresAt})""".execute()

  override def loadForUpdate(id: java.util.UUID): Option[CallbackSlot] =
    sql"""SELECT id, account_id, chat, payload, session_id, step_seq, expires_at
          FROM callback_slots WHERE id = $id FOR UPDATE""".queryOne[CallbackSlot]()

  override def deleteForSession(sessionId: java.util.UUID): Int =
    sql"DELETE FROM callback_slots WHERE session_id = $sessionId".execute()
