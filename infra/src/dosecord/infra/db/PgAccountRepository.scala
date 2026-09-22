package dosecord.infra.db

import dosecord.contracts.AccountId
import dosecord.contracts.IdentityId
import dosecord.core.ports.AccountRepository

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** Account creation (ROADMAP M0.12d, DESIGN.md section 6): the `users` row has handle and display_name NULL (generated
  * handle; neutral display name, never the vendor nickname, K8), the current platform identity is linked, and the
  * primary delivery channel is registered — all inside the mediator's per-event transaction.
  */
final class PgAccountRepository(conn: Connection) extends AccountRepository:
  private given Connection = conn

  override def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId =
    val userId = UUID.randomUUID()
    sql"INSERT INTO users (id, status, timezone) VALUES ($userId, 'active', $timezone)".execute()
    val linked =
      sql"""UPDATE platform_identities SET user_id = $userId, linked_at = $now
            WHERE id = ${identityId.uuid} AND user_id IS NULL AND unlinked_at IS NULL""".execute()
    if linked != 1 then
      throw IllegalStateException(s"identity ${identityId.uuid} was not linkable (already linked or unknown)")
    sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state)
          VALUES (${UUID.randomUUID()}, $userId, ${identityId.uuid}, 'primary', 0, 'healthy')""".execute()
    AccountId(userId)
