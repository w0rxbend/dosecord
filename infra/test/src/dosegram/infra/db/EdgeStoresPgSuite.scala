package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** The stores of the edges on Testcontainers Postgres 18 (ROADMAP G1.10 and the stores the API track left in memory):
  * rendered images with their one-hour life, the file id cache, Mini App sessions and idempotency keys. Each test uses
  * a second store object over the same database where a restart matters: what one process wrote, the next one reads.
  */
class EdgeStoresPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    val conn = dataSource.getConnection
    try conn.createStatement().execute("TRUNCATE render_assets, telegram_file_cache, miniapp_sessions, idempotency_keys")
    finally conn.close()

  private def count(table: String): Int =
    withConnection: conn =>
      val rows = conn.createStatement().executeQuery(s"SELECT count(*) FROM $table") // a table name of this suite
      rows.next()
      rows.getInt(1)

  // The suites connect as the owner of the schema; production connects as a member of dosegram_app.
  test("the application role may run the deleting job of each store"):
    withFreshConnection: conn =>
      val statement = conn.createStatement()
      statement.execute("SET ROLE dosegram_app")
      List("render_assets", "telegram_file_cache", "miniapp_sessions", "idempotency_keys").foreach: table =>
        statement.execute(s"DELETE FROM $table WHERE false") // a table name of this suite

  // ---------- render_assets ----------

  test("an image is read back by another store object until its hour has passed, and not after"):
    val png = Array[Byte](1, 2, 3, 4)
    PgRenderAssets(dataSource, DataKeys.development).put("card.a", png, t0)
    val afterRestart = PgRenderAssets(dataSource, DataKeys.development)
    assertEquals(afterRestart.load("card.a", t0.plusSeconds(3599)).map(_.toList), Some(png.toList))
    assert(afterRestart.holds("card.a", t0.plusSeconds(3599)))
    assertEquals(afterRestart.load("card.a", t0.plusSeconds(3600)), None, "an expired image is not there")
    assert(!afterRestart.holds("card.a", t0.plusSeconds(3600)))
    assertEquals(afterRestart.load("card.unknown", t0), None)

  test("the deleting job removes the bytes one hour after the image was drawn and not before"):
    val assets = PgRenderAssets(dataSource, DataKeys.development)
    assets.put("card.early", Array[Byte](1), t0)
    assets.put("card.late", Array[Byte](2), t0.plusSeconds(1800))
    assertEquals(assets.purge(t0.plusSeconds(3599)), 0, "nothing is deleted before the hour")
    assertEquals(assets.purge(t0.plusSeconds(3600)), 1)
    assertEquals(assets.load("card.late", t0.plusSeconds(3600)).map(_.toList), Some(List[Byte](2)))
    assertEquals(assets.purge(t0.plusSeconds(5400)), 1)

  test("storing an image again replaces it and restarts its hour"):
    val assets = PgRenderAssets(dataSource, DataKeys.development)
    assets.put("card.again", Array[Byte](1), t0)
    assets.put("card.again", Array[Byte](9, 9), t0.plusSeconds(3000))
    assertEquals(assets.load("card.again", t0.plusSeconds(3700)).map(_.toList), Some(List[Byte](9, 9)))

  test("the schema refuses an image that would live longer than one hour"):
    withConnection: conn =>
      given Connection = conn
      intercept[SQLException]:
        sql"""INSERT INTO render_assets (asset_key, mime, byte_size, bytes, created_at, purge_after)
              VALUES ('card.long', 'image/png', 1, ${Array[Byte](1)}, $t0, ${t0.plusSeconds(3601)})""".execute()

  // ---------- telegram_file_cache ----------

  test("a file id survives a restart, belongs to one bot and one media kind, and can be forgotten"):
    PgTelegramFileCache(dataSource, botId = 7000000001L).put("mood.4", "photo", "file-1", t0)
    val afterRestart = PgTelegramFileCache(dataSource, botId = 7000000001L)
    assertEquals(afterRestart.get("mood.4", "photo", t0.plusSeconds(60)), Some("file-1"))
    assertEquals(afterRestart.get("mood.4", "document", t0), None, "another media kind")
    assertEquals(PgTelegramFileCache(dataSource, botId = 7000000002L).get("mood.4", "photo", t0), None, "another bot")
    afterRestart.put("mood.4", "photo", "file-2", t0.plusSeconds(120))
    assertEquals(afterRestart.get("mood.4", "photo", t0.plusSeconds(180)), Some("file-2"), "a new upload replaces it")
    afterRestart.remove("mood.4", "photo")
    assertEquals(afterRestart.get("mood.4", "photo", t0.plusSeconds(240)), None, "TG-36: a rejected file id is deleted")

  test("the pruning job forgets the file ids that were not used since the cutoff; a hit keeps one alive"):
    val cache = PgTelegramFileCache(dataSource, botId = 7000000003L)
    cache.put("card.used", "photo", "file-used", t0)
    cache.put("card.idle", "photo", "file-idle", t0)
    val month = Duration.ofDays(30)
    assertEquals(cache.get("card.used", "photo", t0.plus(month)), Some("file-used"))
    assertEquals(cache.forgetUnusedSince(t0.plus(month)), 1)
    assertEquals(cache.get("card.idle", "photo", t0.plus(month)), None)
    assertEquals(cache.get("card.used", "photo", t0.plus(month)), Some("file-used"))

  // ---------- miniapp_sessions ----------

  private def session(account: UUID, token: String, launch: String, at: Instant): MiniAppSessionRow =
    MiniAppSessionRow(tokenHash = s"hash-of-$token", accountId = account, initDataHash = s"hash-of-$launch", at, at)

  test("a session survives a restart and is found by its token hash only"):
    val account = fixtures.account()
    val row = session(account, "token-a", "launch-a", t0)
    PgMiniAppSessions(dataSource).insert(row)
    val afterRestart = PgMiniAppSessions(dataSource)
    assertEquals(afterRestart.find("hash-of-token-a"), Some(row))
    assertEquals(afterRestart.find("token-a"), None, "the token itself is not a key")
    afterRestart.touch("hash-of-token-a", t0.plusSeconds(90))
    assertEquals(afterRestart.find("hash-of-token-a").map(_.lastUsedAt), Some(t0.plusSeconds(90)))
    afterRestart.delete("hash-of-token-a")
    assertEquals(afterRestart.find("hash-of-token-a"), None)

  test("sessions are listed per account, oldest first, and ended by launch value"):
    val (mine, theirs) = (fixtures.account(), fixtures.account())
    val sessions = PgMiniAppSessions(dataSource)
    sessions.insert(session(mine, "m2", "launch-2", t0.plusSeconds(10)))
    sessions.insert(session(mine, "m1", "launch-1", t0))
    sessions.insert(session(theirs, "t1", "launch-3", t0))
    assertEquals(sessions.listForAccount(mine).map(_.tokenHash), List("hash-of-m1", "hash-of-m2"))
    assertEquals(sessions.deleteByInitData("hash-of-launch-1"), 1)
    assertEquals(sessions.deleteByInitData("hash-of-launch-1"), 0)
    assertEquals(sessions.listForAccount(theirs).size, 1, "another account keeps its session")

  test("the deleting job ends a session that is idle or past its absolute lifetime, and no other"):
    val account = fixtures.account()
    val sessions = PgMiniAppSessions(dataSource)
    sessions.insert(session(account, "idle", "l-idle", t0))
    sessions.insert(session(account, "old", "l-old", t0.minus(Duration.ofHours(12))))
    sessions.insert(session(account, "live", "l-live", t0))
    val now = t0.plus(Duration.ofMinutes(15))
    sessions.touch("hash-of-old", now)
    sessions.touch("hash-of-live", now)
    val ended = sessions.deleteEnded(idleBefore = now.minus(Duration.ofMinutes(15)), createdBefore = now.minus(Duration.ofHours(12)))
    assertEquals(ended, 2)
    assertEquals(sessions.listForAccount(account).map(_.tokenHash), List("hash-of-live"))

  test("a session row holds hashes only: the schema has no column for a token or for launch data"):
    withConnection: conn =>
      given Connection = conn
      val columns = sql"""SELECT column_name FROM information_schema.columns
                          WHERE table_name = 'miniapp_sessions' ORDER BY ordinal_position""".query[String]()
      assertEquals(columns, List("token_hash", "account_id", "initdata_hash", "created_at", "last_used_at"))

  // ---------- idempotency_keys ----------

  test("a key is reserved once; its outcome survives a restart; a second reservation finds it"):
    val account = fixtures.account()
    val key = UUID.randomUUID()
    val keys = PgIdempotencyKeys(dataSource, DataKeys.development)
    assert(keys.reserve(account, key, t0))
    assert(!keys.reserve(account, key, t0.plusSeconds(1)), "the key is taken while its request runs")
    assertEquals(keys.find(account, key), Some(IdempotencyRow(None, t0)), "in flight: no outcome yet")
    keys.complete(account, key, IdempotencyOutcome("hash-1", """{"id":"1"}"""), t0.plusSeconds(2))
    val afterRestart = PgIdempotencyKeys(dataSource, DataKeys.development)
    assertEquals(
      afterRestart.find(account, key),
      Some(IdempotencyRow(Some(IdempotencyOutcome("hash-1", """{"id":"1"}""")), t0.plusSeconds(2)))
    )
    assert(!afterRestart.reserve(account, key, t0.plusSeconds(3)))
    assert(afterRestart.reserve(fixtures.account(), key, t0), "the same key of another account is another key")

  test("a released key is free again; an answered key is not released and not taken over"):
    val account = fixtures.account()
    val (refused, answered) = (UUID.randomUUID(), UUID.randomUUID())
    val keys = PgIdempotencyKeys(dataSource, DataKeys.development)
    keys.reserve(account, refused, t0)
    keys.release(account, refused)
    assertEquals(keys.find(account, refused), None)
    keys.reserve(account, answered, t0)
    keys.complete(account, answered, IdempotencyOutcome("hash", "{}"), t0)
    keys.release(account, answered)
    assert(keys.find(account, answered).exists(_.outcome.isDefined), "an outcome is never released")
    assert(!keys.takeOver(account, answered, staleBefore = t0.plusSeconds(600), now = t0.plusSeconds(600)))

  test("a reservation whose request never finished is taken over once it is stale, by one caller"):
    val account = fixtures.account()
    val key = UUID.randomUUID()
    val keys = PgIdempotencyKeys(dataSource, DataKeys.development)
    keys.reserve(account, key, t0)
    assert(!keys.takeOver(account, key, staleBefore = t0, now = t0.plusSeconds(30)), "not stale yet")
    assert(keys.takeOver(account, key, staleBefore = t0.plusSeconds(1), now = t0.plusSeconds(61)))
    assert(!keys.takeOver(account, key, staleBefore = t0.plusSeconds(1), now = t0.plusSeconds(62)), "only one takes it")

  test("the deleting job removes the keys created before the cutoff"):
    val account = fixtures.account()
    val keys = PgIdempotencyKeys(dataSource, DataKeys.development)
    val before = count("idempotency_keys")
    val (old, fresh) = (UUID.randomUUID(), UUID.randomUUID())
    keys.reserve(account, old, t0.minus(Duration.ofHours(25)))
    keys.complete(account, old, IdempotencyOutcome("h", "{}"), t0.minus(Duration.ofHours(25)))
    keys.reserve(account, fresh, t0)
    assertEquals(keys.deleteCreatedBefore(t0.minus(Duration.ofHours(24))), 1)
    assertEquals(count("idempotency_keys"), before + 1)
    assertEquals(keys.find(account, old), None)
