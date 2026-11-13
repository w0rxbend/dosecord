package dosegram.infra.db

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** One row of `miniapp_sessions`: the hashes of the token and of the launch data, never the values (API-05). */
final case class MiniAppSessionRow(
    tokenHash: String,
    accountId: UUID,
    initDataHash: String,
    createdAt: Instant,
    lastUsedAt: Instant
)

/** `miniapp_sessions` (ARCHITECTURE section 10.2, API-05 to API-07). */
final class PgMiniAppSessions(dataSource: DataSource):
  private val pooled = Pooled(dataSource)

  private given RowMapper[MiniAppSessionRow] = rs =>
    MiniAppSessionRow(
      rs.getString("token_hash"),
      rs.uuid("account_id"),
      rs.getString("initdata_hash"),
      rs.instant("created_at"),
      rs.instant("last_used_at")
    )

  def insert(row: MiniAppSessionRow): Unit =
    pooled:
      sql"""INSERT INTO miniapp_sessions (token_hash, account_id, initdata_hash, created_at, last_used_at)
            VALUES (${row.tokenHash}, ${row.accountId}, ${row.initDataHash}, ${row.createdAt}, ${row.lastUsedAt})"""
        .execute()
    ()

  def find(tokenHash: String): Option[MiniAppSessionRow] =
    pooled(sql"SELECT * FROM miniapp_sessions WHERE token_hash = $tokenHash".queryOne[MiniAppSessionRow]())

  def touch(tokenHash: String, at: Instant): Unit =
    pooled(sql"UPDATE miniapp_sessions SET last_used_at = $at WHERE token_hash = $tokenHash".execute())
    ()

  def delete(tokenHash: String): Unit =
    pooled(sql"DELETE FROM miniapp_sessions WHERE token_hash = $tokenHash".execute())
    ()

  def listForAccount(accountId: UUID): List[MiniAppSessionRow] =
    pooled:
      sql"SELECT * FROM miniapp_sessions WHERE account_id = $accountId ORDER BY created_at, token_hash"
        .query[MiniAppSessionRow]()

  def deleteByInitData(initDataHash: String): Int =
    pooled(sql"DELETE FROM miniapp_sessions WHERE initdata_hash = $initDataHash".execute())

  /** The deleting job: a session last used at or before `idleBefore`, or created at or before `createdBefore`, is over.
    */
  def deleteEnded(idleBefore: Instant, createdBefore: Instant): Int =
    pooled:
      sql"DELETE FROM miniapp_sessions WHERE last_used_at <= $idleBefore OR created_at <= $createdBefore".execute()
