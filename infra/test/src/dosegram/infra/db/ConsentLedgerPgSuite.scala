package dosegram.infra.db

import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.GrantedConsent
import dosegram.core.ports.IdSource

import java.sql.SQLException
import java.time.Instant
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** The consent ledger of ARCHITECTURE section 10.2 on Testcontainers Postgres 18: `consent_events` is append-only and
  * `consent_state` is its projection, written in the same transaction.
  */
class ConsentLedgerPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val notice = ConsentNotice(1, "I agree that Dosegram stores and processes my water intake records.")
  private lazy val fixtures = Fixtures(dataSource)
  private lazy val ledger = PgConsentLedger(dataSource, IdSource.uuidV7(RandomGenerator.getDefault))

  private def events(account: java.util.UUID): List[String] =
    withConnection: conn =>
      given java.sql.Connection = conn
      sql"SELECT purpose || ':' || action FROM consent_events WHERE account_id = $account ORDER BY occurred_at, id"
        .query[String]()

  test("nothing is granted before a grant, and a grant is per account and per purpose"):
    val account = fixtures.account("Europe/Kyiv")
    val other = fixtures.account("Europe/Kyiv")
    assert(!ledger.isGranted(account, ConsentPurpose.Hydration))
    assert(ledger.grant(account, ConsentPurpose.Hydration, notice, t0))
    assert(ledger.isGranted(account, ConsentPurpose.Hydration))
    assert(!ledger.isGranted(account, ConsentPurpose.Mood), "another purpose stays off")
    assert(!ledger.isGranted(other, ConsentPurpose.Hydration), "another account stays off")
    assertEquals(ledger.granted(account), List(GrantedConsent(ConsentPurpose.Hydration, t0, 1)))

  test("a repeated grant writes no second event"):
    val account = fixtures.account("Europe/Kyiv")
    assert(ledger.grant(account, ConsentPurpose.Mood, notice, t0))
    assert(!ledger.grant(account, ConsentPurpose.Mood, notice, t0.plusSeconds(5)))
    assertEquals(events(account), List("mood:granted"))

  test("a withdrawal turns the purpose off at once and a new grant turns it on again"):
    val account = fixtures.account("Europe/Kyiv")
    ledger.grant(account, ConsentPurpose.Hydration, notice, t0)
    assert(ledger.withdraw(account, ConsentPurpose.Hydration, t0.plusSeconds(60)))
    assert(!ledger.isGranted(account, ConsentPurpose.Hydration))
    assertEquals(ledger.granted(account), Nil)
    assert(!ledger.withdraw(account, ConsentPurpose.Hydration, t0.plusSeconds(61)), "nothing left to withdraw")
    assert(ledger.grant(account, ConsentPurpose.Hydration, notice, t0.plusSeconds(120)))
    assertEquals(events(account), List("hydration:granted", "hydration:withdrawn", "hydration:granted"))

  test("a new notice version is recorded as a new grant"):
    val account = fixtures.account("Europe/Kyiv")
    ledger.grant(account, ConsentPurpose.Hydration, notice, t0)
    assert(ledger.grant(account, ConsentPurpose.Hydration, notice.copy(policyVersion = 2), t0.plusSeconds(60)))
    assertEquals(ledger.granted(account).map(_.policyVersion), List(2))

  test("consent_events is append-only and stores the hash of the statement, not the statement"):
    val account = fixtures.account("Europe/Kyiv")
    ledger.grant(account, ConsentPurpose.Hydration, notice, t0)
    withConnection: conn =>
      given java.sql.Connection = conn
      val hashBytes = sql"SELECT octet_length(notice_hash) FROM consent_events WHERE account_id = $account".query[Int]()
      assertEquals(hashBytes, List(32))
      intercept[SQLException](sql"UPDATE consent_events SET action = 'withdrawn' WHERE account_id = $account".execute())
      intercept[SQLException](sql"DELETE FROM consent_events WHERE account_id = $account".execute())

  test("the ledger accepts exactly the purposes of PRODUCT_SPEC R-IDC-6"):
    val account = fixtures.account("Europe/Kyiv")
    ConsentPurpose.values.foreach(purpose => assert(ledger.grant(account, purpose, notice, t0), purpose.id))
    withConnection: conn =>
      given java.sql.Connection = conn
      intercept[SQLException]:
        sql"""INSERT INTO consent_state (account_id, purpose, granted, policy_version, updated_at)
              VALUES ($account, 'terms', true, 1, $t0)""".execute()
