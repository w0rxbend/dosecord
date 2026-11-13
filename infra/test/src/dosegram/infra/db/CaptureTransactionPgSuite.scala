package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.Application
import dosegram.core.application.CommandRegistry
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.chat.*
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewInboundEvent
import dosegram.core.ports.StatusCards

import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G2.1 on Testcontainers Postgres 18: the tracking repositories and the consent ledger are groups of the one
  * transaction handle. An entry commits or rolls back with the inbound event that recorded it, and a capture event
  * holds one pooled connection, so more simultaneous events than the pool has connections queue instead of starving.
  */
class CaptureTransactionPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val clock: Clock = () => t0
  // The capture events of one test run in parallel on a fixed clock: the generator must be thread-safe, as in production,
  // or two of them draw the same bits and one entry fails on a duplicate key.
  private val ids = IdSource.uuidV7(SecureRandom())
  private val hydration = HydrationService(clock, ids)
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val notice = ConsentNotice(1, "test statement", "en")

  /** An account linked to the vendor user `user`, with the `hydration` consent. */
  private def seedConsentedAccount(user: String): UUID =
    val accountId = UUID.randomUUID()
    val identityId = UUID.randomUUID()
    withConnection: conn =>
      given Connection = conn
      sql"INSERT INTO users (id, timezone, status) VALUES ($accountId, 'Europe/Kyiv', 'active')".execute()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, dm_channel_id, linked_at)
            VALUES ($identityId, $accountId, 'fake', $user, ${s"chat-$user"}, $t0)""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            VALUES (${UUID.randomUUID()}, $accountId, $identityId, 'primary', 0, 'healthy', $t0)""".execute()
    PgUnitOfWork(dataSource, clock).transaction(_.consents.grant(accountId, ConsentPurpose.Hydration, notice, ConsentTaps(t0, t0)))
    accountId

  private def count(table: String, account: UUID): Int =
    withConnection: conn =>
      val statement = conn.prepareStatement(s"SELECT count(*) FROM $table WHERE account_id = ?")
      try
        statement.setObject(1, account)
        val rows = statement.executeQuery()
        rows.next()
        rows.getInt(1)
      finally statement.close()

  private def glass(account: UUID, key: String): LogWater =
    LogWater(account, WaterAmount.ofMillilitres(250).toOption.get, EntryDay.Today, EntrySource.Bot, key)

  test("an entry rolls back with the transaction of its inbound event, and commits with it"):
    val account = seedConsentedAccount("atomic-1")
    val uow = PgUnitOfWork(dataSource, clock)
    def capture(vendorEventId: String, failAfterEntry: Boolean): Unit =
      uow.transaction: tx =>
        assert(tx.inboundEvents.insert(NewInboundEvent("fake", vendorEventId, UUID.randomUUID(), t0)))
        assert(hydration.log(tx, glass(account, s"fake:$vendorEventId")).isRight)
        if failAfterEntry then throw IllegalStateException("the event fails after its entry")

    intercept[IllegalStateException](capture("e-1", failAfterEntry = true))
    assertEquals(count("hydration_entries", account), 0, "the entry went with the failed event")
    assertEquals(uow.transaction(_.inboundEvents.find("fake", "e-1")), None)

    capture("e-1", failAfterEntry = false)
    assertEquals(count("hydration_entries", account), 1)
    assert(uow.transaction(_.inboundEvents.find("fake", "e-1")).isDefined)

  test("a consent and a withdrawal roll back with their transaction"):
    val account = seedConsentedAccount("atomic-2")
    val uow = PgUnitOfWork(dataSource, clock)
    intercept[IllegalStateException]:
      uow.transaction: tx =>
        assert(tx.consents.withdraw(account, ConsentPurpose.Hydration, t0))
        throw IllegalStateException("the event fails after the withdrawal")
    assert(uow.transaction(_.consents.isGranted(account, ConsentPurpose.Hydration)), "the withdrawal did not commit")

  test("capture events beyond the pool size all record: one event holds one connection"):
    val poolSize = 2
    val events = poolSize * 6
    val pool = Database.pooled(pg.jdbcUrl, pg.username, pg.password, maxPoolSize = poolSize)
    // A starved event fails here instead of waiting out Hikari's 30-second default.
    pool.setConnectionTimeout(5000)
    try
      val accounts = (1 to events).map(i => s"user-$i" -> seedConsentedAccount(s"user-$i")).toList
      ox.supervised:
        val uow = PgUnitOfWork(pool, clock)
        val adapters: Map[String, ChatAdapter] = Map("fake" -> FakeAdapter(CapabilityProfiles.Console))
        val parts = CaptureParts.over(uow, clock, ids, StatusCards.textOnly)
        val handler = Application.handler(uow, adapters, codec, clock)(parts)
        val mediator = ChatMediator(uow, adapters, codec, handler, clock, CommandRegistry.withCaptureByName)
        accounts.foreach: (user, _) =>
          val event = InboundEvent(
            EventId(UUID.randomUUID()),
            "fake",
            s"capture-$user",
            t0,
            actor = PlatformIdentity("fake", user, None),
            chat = ChatRef("fake", s"chat-$user"),
            principal = None,
            body = Inbound.MessageReceived("250 ml", None, truncated = false)
          )
          assert(mediator.push(event), s"the event of $user was accepted")
        val deadline = System.nanoTime() + 30_000_000_000L
        def recorded: Int = accounts.count((_, account) => count("hydration_entries", account) == 1)
        while recorded < events && System.nanoTime() < deadline do Thread.sleep(50)
        assertEquals(recorded, events, "every simultaneous capture event recorded its entry")
    finally pool.close()
