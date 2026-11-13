package dosegram.app

import dosegram.api.auth.Session
import dosegram.api.auth.SessionStore
import dosegram.infra.db.MiniAppSessionRow
import dosegram.infra.db.PgMiniAppSessions

import java.time.Instant
import java.util.UUID

/** The session store over `miniapp_sessions`: a restart of the process ends no session. */
final class PgSessionStore(sessions: PgMiniAppSessions) extends SessionStore:
  import PgSessionStore.*

  override def insert(session: Session): Unit = sessions.insert(rowOf(session))
  override def find(tokenHash: String): Option[Session] = sessions.find(tokenHash).map(sessionOf)
  override def touch(tokenHash: String, at: Instant): Unit = sessions.touch(tokenHash, at)
  override def delete(tokenHash: String): Unit = sessions.delete(tokenHash)
  override def listForAccount(accountId: UUID): List[Session] = sessions.listForAccount(accountId).map(sessionOf)
  override def deleteByInitData(digest: String): Int = sessions.deleteByInitData(digest)
  override def deleteEnded(idleBefore: Instant, createdBefore: Instant): Int =
    sessions.deleteEnded(idleBefore, createdBefore)

object PgSessionStore:

  private def rowOf(session: Session): MiniAppSessionRow =
    MiniAppSessionRow(
      session.tokenHash,
      session.accountId,
      session.initDataDigest,
      session.createdAt,
      session.lastUsedAt
    )

  private def sessionOf(row: MiniAppSessionRow): Session =
    Session(row.tokenHash, row.accountId, row.initDataHash, row.createdAt, row.lastUsedAt)
