package dosegram.app

import dosegram.api.IdempotencyRecord
import dosegram.api.auth.Session
import dosegram.core.ports.Clock
import dosegram.infra.db.Fixtures
import dosegram.infra.db.PgIdempotencyKeys
import dosegram.infra.db.PgMiniAppSessions
import dosegram.infra.db.PgSuite

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** The ports of the API over their tables, as the composition root wires them (ARCHITECTURE API-05, API-16): a session
  * and the outcome of a creating request survive the process, and a creating request runs once per key however many
  * callers send it at once.
  */
class DurableApiStoresSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val clock: Clock = () => t0
  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    val conn = dataSource.getConnection
    try conn.createStatement().execute("TRUNCATE render_assets, telegram_file_cache, miniapp_sessions, idempotency_keys")
    finally conn.close()

  private def idempotency(): PgIdempotencyStore = PgIdempotencyStore(PgIdempotencyKeys(dataSource), clock)

  private def record(hash: String): IdempotencyRecord = IdempotencyRecord(hash, s"""{"made":"$hash"}""", t0)

  test("a session written by one process is read by the next, and the deleting job takes the ended ones"):
    val account = fixtures.account()
    val session = Session("token-hash", account, "launch-hash", createdAt = t0, lastUsedAt = t0)
    PgSessionStore(PgMiniAppSessions(dataSource)).insert(session)
    val afterRestart = PgSessionStore(PgMiniAppSessions(dataSource))
    assertEquals(afterRestart.find("token-hash"), Some(session))
    assertEquals(afterRestart.listForAccount(account), List(session))
    val later = t0.plus(Duration.ofMinutes(15))
    assertEquals(afterRestart.deleteEnded(idleBefore = later.minus(Duration.ofMinutes(15)), createdBefore = t0.minusSeconds(1)), 1)
    assertEquals(afterRestart.find("token-hash"), None)

  test("a creating request runs once per key; a repeat, also after a restart, gets the stored outcome"):
    val (account, key) = (fixtures.account(), UUID.randomUUID())
    val runs = AtomicInteger(0)
    def create(): Option[IdempotencyRecord] = { runs.incrementAndGet(); Some(record("first")) }
    assertEquals(idempotency().getOrCreate(account, key, () => create()), Some(record("first")))
    assertEquals(idempotency().getOrCreate(account, key, () => create()), Some(record("first")))
    assertEquals(runs.get(), 1, "the repeat did not run the request again")

  test("a refused request stores nothing and frees its key; so does one that fails"):
    val (account, key) = (fixtures.account(), UUID.randomUUID())
    val store = idempotency()
    assertEquals(store.getOrCreate(account, key, () => None), None)
    intercept[IllegalArgumentException](store.getOrCreate(account, key, () => throw IllegalArgumentException("broken")))
    assertEquals(store.getOrCreate(account, key, () => Some(record("corrected"))), Some(record("corrected")))

  test("of simultaneous requests with one key exactly one runs, and every caller gets its outcome"):
    val (account, key) = (fixtures.account(), UUID.randomUUID())
    val store = idempotency()
    val runs = AtomicInteger(0)
    val inside = CountDownLatch(1)
    def slow(): Option[IdempotencyRecord] =
      runs.incrementAndGet()
      inside.countDown()
      Thread.sleep(300)
      Some(record("slow"))
    val outcomes = ox.supervised:
      val first = ox.fork(store.getOrCreate(account, key, () => slow()))
      inside.await()
      val others = List.fill(4)(ox.fork(store.getOrCreate(account, key, () => slow())))
      (first :: others).map(_.join())
    assertEquals(outcomes.distinct, List(Some(record("slow"))))
    assertEquals(runs.get(), 1)

  test("a reservation left by a process that died is taken over after a minute, not before"):
    val (account, key) = (fixtures.account(), UUID.randomUUID())
    val keys = PgIdempotencyKeys(dataSource)
    assert(keys.reserve(account, key, t0.minus(PgIdempotencyStore.TakeOverAfter).minusSeconds(1)))
    assertEquals(idempotency().getOrCreate(account, key, () => Some(record("retry"))), Some(record("retry")))

  test("the deleting job removes an outcome 24 hours after it was stored"):
    val (account, key) = (fixtures.account(), UUID.randomUUID())
    val store = idempotency()
    store.getOrCreate(account, key, () => Some(record("old")))
    assertEquals(store.deleteStoredBefore(t0), 0)
    assertEquals(store.deleteStoredBefore(t0.plusSeconds(1)), 1)
