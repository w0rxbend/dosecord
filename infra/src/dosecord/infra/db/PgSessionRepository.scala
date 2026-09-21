package dosecord.infra.db

import dosecord.core.ports.Clock
import dosecord.core.ports.ConversationSession
import dosecord.core.ports.SessionRepository
import dosecord.core.ports.StaleSessionVersion

import java.sql.Connection
import scala.language.implicitConversions

final class PgSessionRepository(conn: Connection, clock: Clock) extends SessionRepository:
  private given Connection = conn

  private given RowMapper[ConversationSession] = rs =>
    ConversationSession(
      id = rs.uuid("id"),
      principalKey = rs.getString("principal_key"),
      vendor = rs.getString("vendor"),
      chatId = rs.getString("chat_id"),
      flow = rs.getString("flow"),
      step = rs.getString("step"),
      stepSeq = rs.getInt("step_seq"),
      data = rs.getString("data"),
      version = rs.getInt("version"),
      lastPrompt = rs.optString("last_prompt"),
      expiresAt = rs.instant("expires_at"),
      createdAt = rs.instant("created_at"),
      updatedAt = rs.instant("updated_at")
    )

  override def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, version,
                 last_prompt, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE principal_key = $principalKey AND vendor = $vendor AND chat_id = $chatId
          FOR UPDATE""".queryOne[ConversationSession]()

  override def insert(session: ConversationSession): Unit =
    val now = clock.now()
    sql"""INSERT INTO conversation_sessions
            (id, principal_key, vendor, chat_id, flow, step, step_seq, data, version, last_prompt,
             expires_at, created_at, updated_at)
          VALUES (${session.id}, ${session.principalKey}, ${session.vendor}, ${session.chatId},
                  ${session.flow}, ${session.step}, ${session.stepSeq}, ${Jsonb(session.data)},
                  ${session.version}, ${session.lastPrompt.map(Jsonb(_))}, ${session.expiresAt}, $now, $now)"""
      .execute()

  override def save(session: ConversationSession): Unit =
    val updated =
      sql"""UPDATE conversation_sessions
            SET flow = ${session.flow}, step = ${session.step}, step_seq = ${session.stepSeq},
                data = ${Jsonb(session.data)}, version = ${session.version + 1},
                last_prompt = ${session.lastPrompt.map(Jsonb(_))}, expires_at = ${session.expiresAt},
                updated_at = ${clock.now()}
            WHERE id = ${session.id} AND version = ${session.version}""".execute()
    if updated != 1 then throw StaleSessionVersion(session.id, session.version)

  override def delete(id: java.util.UUID): Unit =
    sql"DELETE FROM conversation_sessions WHERE id = $id".execute()

  override def expiring(now: java.time.Instant, limit: Int): List[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, version,
                 last_prompt, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE expires_at <= $now
          ORDER BY expires_at
          LIMIT $limit
          FOR UPDATE SKIP LOCKED""".query[ConversationSession]()

  override def resumable(now: java.time.Instant, limit: Int): List[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, version,
                 last_prompt, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE last_prompt IS NOT NULL AND expires_at > $now
          ORDER BY updated_at
          LIMIT $limit""".query[ConversationSession]()
