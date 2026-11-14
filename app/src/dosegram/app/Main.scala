package dosegram.app

import dosegram.core.application.privacy.Deletions
import dosegram.core.application.privacy.PurgeReplay
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.infra.FilePurgeList
import dosegram.infra.Metrics
import dosegram.infra.Settings
import dosegram.infra.Telemetry
import dosegram.infra.TzdbGuard
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.Database
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.SchemaUpgrade
import ox.releaseAfterScope

import java.nio.file.Path
import java.security.SecureRandom

/** Composition root (DESIGN.md section 3): settings, scribe telemetry (M0.11), tzdb startup assertion (M0.7 remedy),
  * Flyway with the sealing backfill under `pg_advisory_lock` ([[SchemaUpgrade]]), the replay of the purge list
  * ([[PurgeReplay]]), then one Ox `supervised` root scope that runs the [[Launch]] assembly until SIGTERM.
  */
object Main:
  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Left(error) =>
        Console.err.println(s"dosegram: $error")
        Console.err.println(Cli.usage)
        sys.exit(2)
      case Right(command) => sys.exit(run(command))

  private def info(message: String): Unit = scribe.info(message)

  private def run(command: Cli.Command): Int =
    val base =
      try Settings.fromEnv()
      catch
        case e: IllegalArgumentException =>
          Console.err.println(e.getMessage)
          sys.exit(2)
    val settings = command match
      case Cli.Command.Run(Some(role)) => base.copy(role = role)
      case _                           => base
    Telemetry.configure(settings.logLevel, settings.logFormat)
    val tzdbVersion = TzdbGuard.assertSupported(info)
    Metrics.registerTzdb(tzdbVersion)
    info(
      s"starting: role=${settings.role.envName} instance=${settings.instanceId} " +
        s"adapters=${settings.enabledAdapters.map(_.envName).mkString(",")}"
    )
    val dataSource = Database.pooled(settings.databaseUrl, 10)
    try
      // Migrations, the sealing backfill between V10 and V11, and the rewrap of retired keys (ROADMAP G2.4, G2.4b).
      val keys = DataKeys(settings.kek, Clock.system)
      val result = SchemaUpgrade.run(dataSource, keys, info)
      info(
        s"migrate: ${result.migrationsExecuted} migration(s) applied, schema ${result.schemaVersion}, " +
          s"${result.valuesSealed} value(s) sealed, ${result.keysRewrapped} key(s) rewrapped"
      )
      // A restored backup deletes the listed accounts again before anything serves (ARCHITECTURE SEC-06, SEC-16).
      val purgeList = Path.of(settings.purgeListPath)
      val replayed = PurgeReplay(
        PgUnitOfWork(dataSource, Clock.system, keys),
        Deletions(Clock.system, IdSource.uuidV7(SecureRandom()), FilePurgeList(purgeList))
      ).run(FilePurgeList.read(purgeList))
      info(s"purge: replayed $replayed deletion(s) of the purge list")
      command match
        case Cli.Command.Migrate => 0
        case Cli.Command.Run(_)  =>
          Shutdown.serve(
            body = {
              // The SIGTERM hook halts the JVM as soon as the scope has ended, which can outrun the `finally` below.
              // The scope therefore closes the pool itself, after every fork that uses it has finished.
              releaseAfterScope { dataSource.close(); info("shutdown: pool closed") }
              Launch.start(settings, dataSource, Launch.Parts.production(settings, dataSource), info)
            },
            log = info
          )
    finally dataSource.close()
