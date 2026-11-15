package dosegram.infra.db

import dosegram.core.ports.ConsentPurpose
import dosegram.infra.crypto.DataKeys
import dosegram.infra.perf.StatementLog

import java.time.Instant
import java.util.UUID

/** The account reads of one transaction (docs/PERFORMANCE.md, hot spot 2) on Testcontainers Postgres 18: a time zone,
  * the settings and a consent are read from the database once per transaction, and the transaction still sees every
  * change it makes, an erasure and a rolled-back savepoint included.
  */
class TransactionReadsPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-15T09:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)
  private def uow = PgUnitOfWork(dataSource, MutableClock(t0), DataKeys.development)

  test("a time zone, the settings and a consent read three times in one transaction are each one query"):
    val account = fixtures.medicationAccount("Europe/Kyiv")
    val counted = StatementLog.during(pg, dataSource):
      uow.transaction: tx =>
        (1 to 3).foreach: _ =>
          assertEquals(tx.accounts.timezoneOf(account), Some("Europe/Kyiv"))
          assert(tx.accounts.settingsOf(account).isDefined)
          assert(tx.consents.isGranted(account, ConsentPurpose.Medication))
    def runs(prefix: String): Int = counted.statements.filter(_.text.startsWith(prefix)).map(_.count).sum
    assertEquals(runs("SELECT timezone FROM users"), 1)
    assertEquals(runs("SELECT locale, quiet_hours"), 1)
    assertEquals(runs("SELECT granted FROM consent_state"), 1)

  test("a transaction reads its own change of the time zone, the settings and a consent"):
    val account = fixtures.medicationAccount("UTC")
    uow.transaction: tx =>
      assertEquals(tx.accounts.timezoneOf(account), Some("UTC"))
      tx.accounts.setTimezone(account, "Europe/Kyiv", t0)
      assertEquals(tx.accounts.timezoneOf(account), Some("Europe/Kyiv"))
      val settings = tx.accounts.settingsOf(account).getOrElse(fail("no settings"))
      tx.accounts.saveSettings(account, settings.copy(locale = Some("uk")), t0)
      assertEquals(tx.accounts.settingsOf(account).flatMap(_.locale), Some("uk"))
      assert(tx.consents.isGranted(account, ConsentPurpose.Medication))
      assert(tx.consents.withdraw(account, ConsentPurpose.Medication, t0))
      assert(!tx.consents.isGranted(account, ConsentPurpose.Medication))

  test("after a rolled-back savepoint the transaction reads the value from before it"):
    val account = fixtures.account("UTC")
    uow.transaction: tx =>
      intercept[IllegalStateException]:
        tx.savepoint:
          tx.accounts.setTimezone(account, "Europe/Kyiv", t0)
          assertEquals(tx.accounts.timezoneOf(account), Some("Europe/Kyiv"))
          throw IllegalStateException("the step fails")
      assertEquals(tx.accounts.timezoneOf(account), Some("UTC"))

  test("after an account erasure the transaction no longer reads the account or its consent"):
    val account = fixtures.medicationAccount("UTC")
    uow.transaction: tx =>
      assertEquals(tx.accounts.timezoneOf(account), Some("UTC"))
      assert(tx.consents.isGranted(account, ConsentPurpose.Medication))
      tx.erasure.eraseAccount(account, UUID.randomUUID(), t0)
      assertEquals(tx.accounts.timezoneOf(account), None)
      assert(!tx.consents.isGranted(account, ConsentPurpose.Medication))
