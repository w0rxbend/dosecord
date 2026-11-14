package dosegram.infra.db

import dosegram.core.ports.AuditActor
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.infra.crypto.DataKeys
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion

import java.security.SecureRandom
import javax.sql.DataSource

/** What one upgrade did: migrations applied, values sealed by the backfills, data keys rewrapped. */
final case class UpgradeResult(migrationsExecuted: Int, schemaVersion: String, valuesSealed: Int, keysRewrapped: Int)

/** `dosegram migrate`, and the same step at every start (ROADMAP G2.4, G2.4b; supervisor decisions, 2026-11-14): under
  * the migration lock, Flyway up to V10 (the sealed columns), then the [[SealingBackfill]] of every value still in a
  * plaintext column, then V11 (emptying the plaintext columns) and V12 (`audit_log`) in one transaction, then V13 (the
  * sealed transport columns) and its backfill, which empties each plaintext value it seals, then the rest of the
  * migrations, and last the rewrap of every data key a retired key-encryption key still wraps (SEC-07). V11 refuses to
  * run while a value is left unsealed, so a backfill that stops part-way leaves the schema at V10 and the next start
  * resumes it. The V10 backfill writes one `key.backfill.completed` audit row, on the transaction of the rewrap, once
  * the schema has `audit_log`: when it sealed a value, or found sealed values next to their plaintext, which is the
  * case after a start whose V11 or V12 failed. A run that sealed a value then vacuums the tables it sealed, so their
  * dead row versions, which hold the plaintext, are freed for reuse (OPERATIONS section 9).
  */
object SchemaUpgrade:

  /** The migration that adds the sealed columns; the backfill runs between it and the rest. */
  val ExpandVersion: MigrationVersion = MigrationVersion.fromVersion(SealingStage.Expand.expand)

  /** The migration that creates `audit_log`; V11 and V12 apply together. */
  val AuditVersion: MigrationVersion = MigrationVersion.fromVersion("12")

  def run(dataSource: DataSource, keys: DataKeys, log: String => Unit): UpgradeResult =
    Migrator(dataSource).withLock(): locked =>
      def flyway = Flyway.configure().dataSource(locked)
      val current = Option(flyway.load().info().current()).map(_.getVersion)
      val expanded =
        if current.exists(_.isAtLeast(ExpandVersion.getVersion)) then 0
        else flyway.target(ExpandVersion).load().migrate().migrationsExecuted
      val backfill = SealingBackfill(dataSource, keys)
      val expandCounts = backfill.run(SealingStage.Expand, log)
      val contracted = flyway.target(AuditVersion).group(true).load().migrate().migrationsExecuted
      val transportExpanded =
        flyway.target(MigrationVersion.fromVersion(SealingStage.Transport.expand)).load().migrate().migrationsExecuted
      val transportCounts = backfill.run(SealingStage.Transport, log)
      val rest = flyway.load().migrate().migrationsExecuted
      val backfilled = expandCounts.exists(count => count.sealedNow > 0 || count.verified > 0)
      val rewrapped = rewrap(dataSource, keys, backfilled)
      if rewrapped > 0 then
        log(s"keys: rewrapped $rewrapped data key(s) with key-encryption key '${keys.ring.active.id}'")
      val sealedCounts = (expandCounts ++ transportCounts).filter(_.sealedNow > 0)
      vacuum(dataSource, sealedCounts.map(_.column.table).distinct, log)
      UpgradeResult(
        expanded + contracted + transportExpanded + rest,
        Option(flyway.load().info().current()).fold("empty")(_.getVersion.getVersion),
        sealedCounts.map(_.sealedNow).sum,
        rewrapped
      )

  /** Plain VACUUM: no lock beyond what reads and writes take, so it is safe while the bot runs. It frees the dead row
    * versions for reuse; VACUUM FULL, which rewrites the files, is an owner step of OPERATIONS section 9. A role that
    * does not own a table gets a warning from Postgres and that table is skipped.
    */
  private def vacuum(dataSource: DataSource, tables: List[String], log: String => Unit): Unit =
    if tables.nonEmpty then
      val conn = dataSource.getConnection
      try
        conn.setAutoCommit(true)
        val statement = conn.createStatement()
        try statement.execute(s"VACUUM (ANALYZE) ${tables.mkString(", ")}")
        finally statement.close()
        log(s"sealing: vacuumed ${tables.mkString(", ")}")
      finally conn.close()

  private def rewrap(dataSource: DataSource, keys: DataKeys, backfilled: Boolean): Int =
    val conn = dataSource.getConnection
    try
      conn.setAutoCommit(false)
      if backfilled then
        PgAuditLog(conn, Clock.system, IdSource.uuidV7(SecureRandom())).record(
          AuditEvent(
            AuditEventType.BackfillCompleted,
            AuditActor.System("migrate"),
            None,
            "sealed_column",
            None,
            "seal",
            AuditChannel.Scheduler
          )
        )
      val count = keys.rewrapRetired(conn)
      conn.commit()
      count
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.close()
