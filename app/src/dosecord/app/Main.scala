package dosecord.app

import dosecord.infra.Metrics
import dosecord.infra.Settings
import dosecord.infra.Telemetry
import dosecord.infra.TzdbGuard
import dosecord.infra.db.Database
import dosecord.infra.db.Migrator

/** Composition root (DESIGN.md section 3): settings, scribe telemetry (M0.11), tzdb startup assertion (M0.7 remedy),
  * Flyway under `pg_advisory_lock`, then one Ox `supervised` root scope serving `/healthz` until SIGTERM.
  */
object Main:
  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Left(error) =>
        Console.err.println(s"dosecord: $error")
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
      val result = Migrator(dataSource).migrateLocked()
      val target = Option(result.targetSchemaVersion).getOrElse("unchanged")
      info(s"migrate: ${result.migrationsExecuted} migration(s) applied, target $target")
      command match
        case Cli.Command.Migrate => 0
        case Cli.Command.Run(_)  =>
          Shutdown.serve(
            body = {
              // HEALTH_PORT is a local-override read here (not in Settings, which is infra-owned): a dev
              // machine may already have 8080 bound. Settings should adopt it properly at the M2.4 cut.
              val port = sys.env.get("HEALTH_PORT").flatMap(_.toIntOption).getOrElse(Health.DefaultPort)
              val binding = Health.start(port)
              info(s"/healthz listening on :$port")
              val adapters = Adapters.start(settings, dataSource, info)
              Drain(
                inbound = ("health" -> (() => binding.stop())) :: adapters,
                claiming = Nil,
                inFlight = InFlightRegistry(),
                budget = Shutdown.DrainBudget,
                log = info
              )
            },
            log = info
          )
    finally dataSource.close()
