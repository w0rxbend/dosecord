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

/** What one upgrade did: migrations applied, values sealed by the backfill, data keys rewrapped. */
final case class UpgradeResult(migrationsExecuted: Int, schemaVersion: String, valuesSealed: Int, keysRewrapped: Int)

/** `dosegram migrate`, and the same step at every start (ROADMAP G2.4, G2.4b; supervisor decision, 2026-11-14): under
  * the migration lock, Flyway up to V10 (the sealed columns), then the [[SealingBackfill]] of every value still in a
  * plaintext column, then the rest of the migrations, V11 emptying the plaintext columns among them, and last the
  * rewrap of every data key a retired key-encryption key still wraps (SEC-07). V11 refuses to run while a value is left
  * unsealed, so a backfill that stops part-way leaves the schema at V10 and the next start resumes it. A run that
  * sealed a value writes one `key.backfill.completed` audit row once the schema has `audit_log` (V12), on the
  * transaction of the rewrap.
  */
object SchemaUpgrade:

  /** The migration that adds the sealed columns; the backfill runs between it and the rest. */
  val ExpandVersion: MigrationVersion = MigrationVersion.fromVersion("10")

  def run(dataSource: DataSource, keys: DataKeys, log: String => Unit): UpgradeResult =
    Migrator(dataSource).withLock(): locked =>
      val current = Option(Flyway.configure().dataSource(locked).load().info().current()).map(_.getVersion)
      val expanded =
        if current.exists(_.isAtLeast(ExpandVersion.getVersion)) then 0
        else Flyway.configure().dataSource(locked).target(ExpandVersion).load().migrate().migrationsExecuted
      val sealedNow = SealingBackfill(dataSource, keys).run(log).map(_.sealedNow).sum
      val rest = Flyway.configure().dataSource(locked).load().migrate()
      val rewrapped = rewrap(dataSource, keys, backfilled = sealedNow > 0)
      if rewrapped > 0 then
        log(s"keys: rewrapped $rewrapped data key(s) with key-encryption key '${keys.ring.active.id}'")
      UpgradeResult(
        expanded + rest.migrationsExecuted,
        Option(rest.targetSchemaVersion).getOrElse(current.fold("empty")(_.getVersion)),
        sealedNow,
        rewrapped
      )

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
