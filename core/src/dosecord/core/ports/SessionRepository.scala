package dosecord.core.ports

import java.time.Instant
import java.util.UUID

/** Row of `conversation_sessions`. `data` and `lastPrompt` are raw JSON documents; core stays free of a JSON library
  * until M0.9.
  */
final case class ConversationSession(
    id: UUID,
    principalKey: String,
    vendor: String,
    chatId: String,
    flow: String,
    step: String,
    stepSeq: Int,
    data: String,
    version: Int,
    lastPrompt: Option[String],
    expiresAt: Instant,
    createdAt: Instant,
    updatedAt: Instant
)

final case class StaleSessionVersion(id: UUID, version: Int)
    extends RuntimeException(s"conversation session $id was expected at version $version")

trait SessionRepository:
  /** Locks the row until the surrounding transaction ends. */
  def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession]
  def insert(session: ConversationSession): Unit

  /** Optimistic write: updates only if the row is still at `session.version` and bumps it by one; throws
    * [[StaleSessionVersion]] otherwise.
    */
  def save(session: ConversationSession): Unit

  /** Ends the session (abort/complete, M0.12b). */
  def delete(id: UUID): Unit

  /** Sessions idle past their deadline, oldest first, locked `FOR UPDATE SKIP LOCKED` so concurrent sweepers do not
    * double-process (M0.12b).
    */
  def expiring(now: Instant, limit: Int): List[ConversationSession]

  /** Sessions with a persisted prompt that is still inside its expiry window; re-sent on restart (persist-then-send,
    * M0.12b).
    */
  def resumable(now: Instant, limit: Int): List[ConversationSession]
