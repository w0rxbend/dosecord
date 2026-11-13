package dosegram.core.ports

import dosegram.contracts.AccountId
import dosegram.contracts.IdentityId
import dosegram.core.domain.QuietHours

import java.time.Instant
import java.util.UUID

/** Which theme the images that the bot sends are drawn in (VISUALS_AND_ANALYTICS section 3.3.3). The `dbValue` strings
  * match the `users.image_theme` CHECK.
  */
enum ImageTheme(val dbValue: String):
  case Auto extends ImageTheme("auto")
  case Light extends ImageTheme("light")
  case Dark extends ImageTheme("dark")

object ImageTheme:
  def fromDbValue(value: String): ImageTheme =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown image theme '$value'"))

/** What an account set for itself besides its time zone: the locale (absent until the user chooses one), the quiet
  * hours (absent when off), discreet reminders and the image theme.
  */
final case class AccountSettings(
    locale: Option[String],
    quietHours: Option[QuietHours],
    isDiscreet: Boolean,
    imageTheme: ImageTheme
)

object AccountSettings:

  /** The column defaults of a new `users` row. */
  val Default: AccountSettings = AccountSettings(None, None, isDiscreet = false, ImageTheme.Auto)

/** `users` / `platform_identities` / `delivery_channels` (DESIGN.md section 6, ROADMAP M0.12d): account creation is one
  * transactional write — the `users` row (handle and display name left NULL: generated/neutral, never the vendor
  * nickname, K8), the link on the current platform identity, and the primary delivery channel.
  */
trait AccountRepository:

  /** Creates the account for `identityId` and returns its id. Caller guards on `principal.linked`; a concurrent create
    * surfaces as a constraint violation and rolls the event transaction back.
    */
  def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId

  /** The account's current IANA timezone (M1.9: the add-medication wizard's default schedule zone). */
  def timezoneOf(accountId: UUID): Option[String]

  /** Account -> Timezone (R10): moves the user's zone; `tz_follows_user` schedules are re-revisioned separately by the
    * schedule lifecycle.
    */
  def setTimezone(accountId: UUID, timezone: String, now: Instant): Unit

  /** The account's own settings; `None` for an account that does not exist. */
  def settingsOf(accountId: UUID): Option[AccountSettings]

  /** Replaces the settings. The time zone is not one of them: it moves through [[setTimezone]], with its schedules. */
  def saveSettings(accountId: UUID, settings: AccountSettings, now: Instant): Unit
