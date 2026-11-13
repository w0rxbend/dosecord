package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.QuietHours
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.ImageTheme

import java.sql.SQLException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** The settings of an account on Testcontainers Postgres 18 (`users.locale`, `quiet_hours`, `discreet_default`,
  * `image_theme`): what `Tx.accounts` saves is what it reads, and the quiet hours it saves are the ones the reminder
  * loop decides with.
  */
class AccountSettingsPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)
  private lazy val uow = PgUnitOfWork(dataSource, () => t0)
  private val night = QuietHours(HhMm.unsafe("22:30"), HhMm.unsafe("07:00"))
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))

  private def settingsOf(account: UUID): Option[AccountSettings] = uow.transaction(_.accounts.settingsOf(account))

  test("a new account has no locale, no quiet hours, named reminders and the automatic image theme"):
    val account = fixtures.account("Europe/Kyiv")
    assertEquals(settingsOf(account), Some(AccountSettings(None, None, isDiscreet = false, ImageTheme.Auto)))

  test("saved settings are read back, and saving one account leaves another alone"):
    val account = fixtures.account("Europe/Kyiv")
    val other = fixtures.account("Europe/Kyiv")
    val chosen = AccountSettings(Some("en"), Some(night), isDiscreet = true, ImageTheme.Dark)
    uow.transaction(_.accounts.saveSettings(account, chosen, t0))
    assertEquals(settingsOf(account), Some(chosen))
    assertEquals(settingsOf(other).map(_.imageTheme), Some(ImageTheme.Auto))
    uow.transaction(_.accounts.saveSettings(account, chosen.copy(quietHours = None, locale = None), t0))
    assertEquals(settingsOf(account).map(saved => (saved.quietHours, saved.locale)), Some((None, None)))

  test("the quiet hours saved here are the ones the reminder decision reads"):
    val account = fixtures.account("Europe/Kyiv")
    uow.transaction(_.accounts.saveSettings(account, AccountSettings(None, Some(night), false, ImageTheme.Auto), t0))
    val created = fixtures.schedule(() => t0, account, "Vitamin D", everyDayAtNine, kyiv)
    val occurrenceId = fixtures.occurrenceAt(created.scheduleId, t0.plusSeconds(3600), "settings-suite")
    val quiet = uow.transaction(tx => tx.policies.forOccurrence(tx.occurrences.get(occurrenceId).get)._2)
    assertEquals(quiet.hours, Some(night))

  test("an unknown account has no settings, and saving for one fails"):
    val unknown = UUID.randomUUID()
    assertEquals(settingsOf(unknown), None)
    intercept[IllegalArgumentException]:
      uow.transaction(_.accounts.saveSettings(unknown, AccountSettings(None, None, false, ImageTheme.Light), t0))

  test("the schema refuses an image theme outside the list"):
    val account = fixtures.account("UTC")
    intercept[SQLException]:
      withConnection: conn =>
        given java.sql.Connection = conn
        sql"UPDATE users SET image_theme = 'sepia' WHERE id = $account".execute()
