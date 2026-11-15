package dosegram.infra.db

import dosegram.core.ports.Clock
import dosegram.core.ports.ConversationSession
import dosegram.core.ports.KeyService
import dosegram.core.ports.SessionRepository
import dosegram.core.ports.StaleSessionVersion

import java.sql.Connection
import scala.language.implicitConversions

/** `conversation_sessions`. The draft and the last question of an open wizard are sealed with the transport service key
  * (V19, ROADMAP G2.4b): a draft holds what the user typed, a medication name among it, and a session is keyed by the
  * chat's principal, which exists before the account does.
  */
final class PgSessionRepository(conn: Connection, clock: Clock, keys: KeyService) extends SessionRepository:
  private given Connection = conn
  import SealedColumns.SessionData
  import SealedColumns.SessionPrompt

  private given RowMapper[ConversationSession] = rs =>
    val id = rs.uuid("id")
    ConversationSession(
      id = id,
      principalKey = rs.getString("principal_key"),
      vendor = rs.getString("vendor"),
      chatId = rs.getString("chat_id"),
      flow = rs.getString("flow"),
      step = rs.getString("step"),
      stepSeq = rs.getInt("step_seq"),
      data = opened(rs, SessionData, id).getOrElse(throw IllegalStateException(s"session $id has no data")),
      version = rs.getInt("version"),
      lastPrompt = opened(rs, SessionPrompt, id),
      expiresAt = rs.instant("expires_at"),
      createdAt = rs.instant("created_at"),
      updatedAt = rs.instant("updated_at")
    )

  /** The sealed value of `column`, or the plaintext of a row the start-up backfill has not reached. */
  private def opened(rs: java.sql.ResultSet, column: SealedColumn, id: java.util.UUID): Option[String] =
    Option(rs.getBytes(column.encrypted))
      .map(keys.openText(column.forService(id), _))
      .orElse(Option(rs.getString(column.plain)))

  private def sealedData(session: ConversationSession): Array[Byte] =
    keys.sealText(SessionData.forService(session.id), session.data)

  private def sealedPrompt(session: ConversationSession): Option[Array[Byte]] =
    session.lastPrompt.map(keys.sealText(SessionPrompt.forService(session.id), _))

  override def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, data_enc, version,
                 last_prompt, last_prompt_enc, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE principal_key = $principalKey AND vendor = $vendor AND chat_id = $chatId
          FOR UPDATE""".queryOne[ConversationSession]()

  override def insert(session: ConversationSession): Unit =
    val now = clock.now()
    sql"""INSERT INTO conversation_sessions
            (id, principal_key, vendor, chat_id, flow, step, step_seq, data_enc, version, last_prompt_enc,
             expires_at, created_at, updated_at)
          VALUES (${session.id}, ${session.principalKey}, ${session.vendor}, ${session.chatId},
                  ${session.flow}, ${session.step}, ${session.stepSeq}, ${sealedData(session)},
                  ${session.version}, ${sealedPrompt(session)}, ${session.expiresAt}, $now, $now)"""
      .execute()

  override def save(session: ConversationSession): Unit =
    val updated =
      sql"""UPDATE conversation_sessions
            SET flow = ${session.flow}, step = ${session.step}, step_seq = ${session.stepSeq},
                data = NULL, data_enc = ${sealedData(session)}, version = ${session.version + 1},
                last_prompt = NULL, last_prompt_enc = ${sealedPrompt(session)}, expires_at = ${session.expiresAt},
                updated_at = ${clock.now()}
            WHERE id = ${session.id} AND version = ${session.version}""".execute()
    if updated != 1 then throw StaleSessionVersion(session.id, session.version)

  override def delete(id: java.util.UUID): Unit =
    sql"DELETE FROM conversation_sessions WHERE id = $id".execute()

  override def expiring(now: java.time.Instant, limit: Int): List[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, data_enc, version,
                 last_prompt, last_prompt_enc, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE expires_at <= $now
          ORDER BY expires_at
          LIMIT $limit
          FOR UPDATE SKIP LOCKED""".query[ConversationSession]()

  override def resumable(now: java.time.Instant, limit: Int): List[ConversationSession] =
    sql"""SELECT id, principal_key, vendor, chat_id, flow, step, step_seq, data, data_enc, version,
                 last_prompt, last_prompt_enc, expires_at, created_at, updated_at
          FROM conversation_sessions
          WHERE (last_prompt_enc IS NOT NULL OR last_prompt IS NOT NULL) AND expires_at > $now
          ORDER BY updated_at
          LIMIT $limit""".query[ConversationSession]()
