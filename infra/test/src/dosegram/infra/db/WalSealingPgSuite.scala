package dosegram.infra.db

import com.zaxxer.hikari.HikariDataSource
import dosegram.contracts.PlatformIdentity
import dosegram.infra.crypto.DataKeys
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.language.implicitConversions

/** The WAL half of the dump test of ROADMAP G2.4b on Testcontainers Postgres 18 (supervisor decision, 2026-11-15).
  * What it shows, on a database the V19 release wrote plaintext file ids and export parameters into:
  *
  *   - the WAL the start-up sealing writes holds the plaintext: each sealing UPDATE is the first change of its page
  *     after a checkpoint, so the record carries a full-page image, which still holds the old row version;
  *   - after the sealing, its VACUUM and a checkpoint, the WAL written from then on holds no sealed value, even the
  *     full-page images of the very pages that held them, because VACUUM pruned the old row versions;
  *   - the older WAL stays in `pg_wal` until a checkpoint after a segment switch removes it, and with `wal_recycle`
  *     on (the default) a recycled segment keeps its bytes under a new name until it is written over. With
  *     `wal_recycle` off, as here, the switch and the checkpoint leave no sealed value in any file of `pg_wal`.
  *
  * Archived WAL and base backups are outside the server and keep what they hold for their retention (OPERATIONS
  * section 4, 35 days), as SEC-06 states for the data keys.
  */
class WalSealingPgSuite extends PgSuite:

  override protected def migrateOnStart: Boolean = false

  private val t0 = Instant.parse("2026-11-15T09:00:00Z")
  private val botId = 7000000005L
  private val fileId = "AgACAgIAAxkBAAIBWalCanaryFileId"
  private val (from, to) = (LocalDate.parse("1986-02-11"), LocalDate.parse("1986-03-12"))
  private val canaries = List(fileId, from.toString, to.toString)

  override def beforeAll(): Unit =
    super.beforeAll()
    withConnection: conn =>
      val st = conn.createStatement()
      try
        st.execute("ALTER SYSTEM SET wal_recycle = off")
        st.execute("SELECT pg_reload_conf()")
      finally st.close()

  private def execute(conn: Connection, statement: String): Unit =
    val st = conn.createStatement()
    try st.execute(statement)
    finally st.close()

  test("a value the V19 release kept in plaintext is in no WAL written after the start-up sealing and a checkpoint"):
    withConnection(execute(_, "CREATE DATABASE wal_upgrade"))
    val url = pg.jdbcUrl.replace(s"/${pg.databaseName}", "/wal_upgrade")
    val ds: HikariDataSource = Database.pooled(url, pg.username, pg.password)
    try
      Flyway.configure().dataSource(ds).target("19").load().migrate()
      val keys = DataKeys.development
      val uow = PgUnitOfWork(ds, MutableClock(t0), keys)
      val account = uow.transaction: tx =>
        val principal = tx.identities.resolve(PlatformIdentity("telegram", "7005", None), t0)
        tx.accounts.createAccount(principal.identityId, "Europe/Kyiv", t0).uuid
      val summary = UUID.randomUUID()
      val seeded = withConnectionOf(ds): conn =>
        given Connection = conn
        val start = WalScan.flushed(conn)
        // PgTelegramFileCache.put and PgExportRepository.create of the V19 release.
        sql"""INSERT INTO telegram_file_cache (asset_key, bot_id, media_kind, file_id, created_at, last_used_at)
              VALUES ('card.wal', $botId, 'photo', $fileId, $t0, $t0)""".execute()
        sql"""INSERT INTO export_jobs (id, account_id, kind, params, status, created_at)
              VALUES ($summary, $account, 'record_summary_pdf', ${Jsonb(s"""{"from":"$from","to":"$to"}""")},
                      'awaiting_confirmation', $t0)""".execute()
        WalScan.between(conn, start, WalScan.flushed(conn), canaries)
      assertEquals(seeded, canaries, "the scan reads the plaintext the V19 release wrote")

      // The rows are old by the time this release starts: a checkpoint has passed since they were written.
      val beforeUpgrade = withConnectionOf(ds): conn =>
        execute(conn, "CHECKPOINT")
        WalScan.flushed(conn)
      val result = SchemaUpgrade.run(ds, keys, _ => ())
      assertEquals(result.valuesSealed, 2)
      val sealing = withConnectionOf(ds)(conn => WalScan.between(conn, beforeUpgrade, WalScan.flushed(conn), canaries))
      assertEquals(sealing, canaries, "the sealing UPDATEs carry full-page images that hold the old row versions")

      val afterCheckpoint = withConnectionOf(ds): conn =>
        execute(conn, "CHECKPOINT")
        WalScan.flushed(conn)
      // The release uses both rows again: each change is the first of its page after the checkpoint.
      assertEquals(PgTelegramFileCache(ds, botId, keys).get("card.wal", "photo", t0.plusSeconds(60)), Some(fileId))
      uow.transaction: tx =>
        assertEquals(tx.exports.find(account, summary).flatMap(_.period).map(p => (p.start, p.end)), Some((from, to)))
        assert(tx.exports.confirm(account, summary, t0.plusSeconds(60)), "the export is confirmed")
      val later = withConnectionOf(ds)(conn => WalScan.between(conn, afterCheckpoint, WalScan.flushed(conn), canaries))
      assertEquals(later, Nil, "the WAL written after the sealing and a checkpoint holds a sealed value")
      assertEquals(withConnectionOf(ds)(WalScan.directory(_, canaries)), canaries,
        "until a segment switch, the current segment of pg_wal still holds the older records")

      val leftInDirectory = withConnectionOf(ds): conn =>
        execute(conn, "SELECT pg_switch_wal()")
        execute(conn, "CHECKPOINT")
        WalScan.directory(conn, canaries)
      assertEquals(leftInDirectory, Nil, "a file of pg_wal still holds a sealed value after a switch and a checkpoint")
    finally ds.close()

  test("the scan finds a value written to the WAL, so a clean scan means the value is not there"):
    withConnection: conn =>
      val start = WalScan.flushed(conn)
      execute(conn, "SELECT pg_logical_emit_message(true, 'dosegram-test', 'canary-wal-control')")
      assertEquals(WalScan.between(conn, start, WalScan.flushed(conn), List("canary-wal-control", "absent")),
        List("canary-wal-control"))

  private def withConnectionOf[A](ds: HikariDataSource)(f: Connection => A): A =
    val conn = ds.getConnection
    try f(conn)
    finally conn.close()
