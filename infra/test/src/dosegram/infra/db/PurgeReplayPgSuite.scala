package dosegram.infra.db

import dosegram.core.application.privacy.Deletions
import dosegram.core.application.privacy.PurgeReplay
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.ports.IdSource
import dosegram.infra.FilePurgeList
import dosegram.infra.crypto.DataKeys

import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** Finding 3 of the verification of 2026-11-14 and ARCHITECTURE SEC-06, SEC-16 on Testcontainers Postgres 18: a backup
  * taken before an account deletion still holds the account. Restored with `pg_restore` as the restore drill does, the
  * replay of the purge list deletes it again before anything serves, and a second replay finds nothing to do.
  */
class PurgeReplayPgSuite extends PgSuite:

  private def run(command: String*): String =
    val result = pg.container.execInContainer(command*)
    assertEquals(result.getExitCode, 0, result.getStderr)
    result.getStdout

  private def count(ds: javax.sql.DataSource, query: String): Int =
    val conn = ds.getConnection
    try
      val rs = conn.createStatement().executeQuery(query)
      rs.next()
      rs.getInt(1)
    finally conn.close()

  /** The tables of `ds` in which some row, read as text, holds `needle`. */
  private def tablesHolding(ds: javax.sql.DataSource, needle: String): List[String] =
    val conn = ds.getConnection
    try
      given Connection = conn
      sql"""SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name"""
        .query[String]()
        .filter(table => count(ds, s"SELECT count(*) FROM $table AS r WHERE r::text LIKE '%$needle%'") > 0)
    finally conn.close()

  test("a backup restored after an account deletion holds the account until the replay of the purge list"):
    val purgeFile = Files.createTempFile("purge-list", ".txt")
    val (account, kept) = ox.supervised:
      val rig = ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), FilePurgeList(purgeFile), "replay-1")
      val account = rig.onboard()
      rig.line("250 ml")
      val other = ProductChatRig(dataSource, rig.clock, FilePurgeList(purgeFile), "replay-2")
      val kept = other.onboard()
      // The nightly backup, before the deletion.
      run("pg_dump", "-U", pg.username, "-d", pg.databaseName, "-Fc", "-f", "/tmp/before.dump")
      rig.line("/privacy")
      rig.tap("Your consents", PrivacyCopy.DeleteData)
      rig.tap("What do you want to delete?", PrivacyCopy.DeleteMyAccount)
      rig.tap("Delete your account?", PrivacyCopy.Continue)
      rig.tap("Last step", PrivacyCopy.DeleteMyAccount)
      assertEquals(count(dataSource, s"SELECT count(*) FROM users WHERE id = '$account'"), 0)
      (account, kept)

    run("createdb", "-U", pg.username, "restored")
    run("pg_restore", "-U", pg.username, "-d", "restored", "--no-owner", "--exit-on-error", "/tmp/before.dump")
    val restored = Database.pooled(pg.jdbcUrl.replace(s"/${pg.databaseName}", "/restored"), pg.username, pg.password)
    try
      assertEquals(count(restored, s"SELECT count(*) FROM users WHERE id = '$account'"), 1, "the backup holds it")
      val clock = MutableClock(ProductChatRig.Start)
      val uow = PgUnitOfWork(restored, clock, DataKeys.development)
      val replay =
        PurgeReplay(uow, Deletions(clock, IdSource.uuidV7(SecureRandom()), FilePurgeList(purgeFile)))
      assertEquals(replay.run(FilePurgeList.read(purgeFile)), 1)
      assertEquals(tablesHolding(restored, account.toString), Nil, "no row of the restored database names it")
      assertEquals(count(restored, s"SELECT count(*) FROM users WHERE id = '$kept'"), 1, "another account stays")
      assertEquals(count(restored, "SELECT count(*) FROM deletion_receipts WHERE scope = 'account'"), 1)
      assertEquals(replay.run(FilePurgeList.read(purgeFile)), 0, "a second replay has nothing to do")
      assertEquals(FilePurgeList.read(purgeFile), List(account), "the replay does not list the account again")
    finally restored.close()

  test("an account on the purge list that the database does not hold is skipped"):
    val clock = MutableClock(ProductChatRig.Start)
    val purgeFile = Files.createTempFile("purge-list", ".txt")
    val replay = PurgeReplay(
      PgUnitOfWork(dataSource, clock, DataKeys.development),
      Deletions(clock, IdSource.uuidV7(SecureRandom()), FilePurgeList(purgeFile))
    )
    assertEquals(replay.run(List(UUID.randomUUID())), 0)
