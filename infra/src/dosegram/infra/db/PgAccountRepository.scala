package dosegram.infra.db

import dosegram.contracts.AccountId
import dosegram.contracts.IdentityId
import dosegram.core.domain.QuietHours
import dosegram.core.ports.AccountRepository
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.ImageTheme
import upickle.default.read
import upickle.default.write

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
  import PgAccountRepository.given

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

  override def timezoneOf(accountId: UUID): Option[String] =
    sql"SELECT timezone FROM users WHERE id = $accountId".queryOne[String]()

  override def setTimezone(accountId: UUID, timezone: String, now: Instant): Unit =
    val updated = sql"UPDATE users SET timezone = $timezone, updated_at = $now WHERE id = $accountId".execute()
    require(updated == 1, s"account $accountId vanished before setTimezone")

  override def settingsOf(accountId: UUID): Option[AccountSettings] =
    sql"SELECT locale, quiet_hours, discreet_default, image_theme FROM users WHERE id = $accountId"
      .queryOne[AccountSettings]()

  override def saveSettings(accountId: UUID, settings: AccountSettings, now: Instant): Unit =
    val quietHours = settings.quietHours.map(hours => Jsonb(write(hours)))
    val updated =
      sql"""UPDATE users SET locale = ${settings.locale}, quiet_hours = $quietHours,
              discreet_default = ${settings.isDiscreet}, image_theme = ${settings.imageTheme.dbValue}, updated_at = $now
            WHERE id = $accountId""".execute()
    require(updated == 1, s"account $accountId vanished before saveSettings")

object PgAccountRepository:
  private given RowMapper[AccountSettings] = rs =>
    AccountSettings(
      locale = rs.optString("locale"),
      quietHours = rs.optJsonb("quiet_hours").map(json => read[QuietHours](json.value)),
      isDiscreet = rs.getBoolean("discreet_default"),
      imageTheme = ImageTheme.fromDbValue(rs.getString("image_theme"))
    )
