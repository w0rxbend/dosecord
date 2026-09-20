package dosecord.app

import dosecord.infra.Settings
import dosecord.infra.TzdbGuard
import dosecord.infra.db.Database
import dosecord.infra.db.Migrator

/** Composition root (DESIGN.md section 3): settings, tzdb startup assertion (M0.7 remedy), Flyway under
  * `pg_advisory_lock`, then one Ox `supervised` root scope serving `/healthz` until SIGTERM.
  */
object Main:
  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Left(error) =>
        Console.err.println(s"dosecord: $error")
        Console.err.println(Cli.usage)
        sys.exit(2)
      case Right(command) => sys.exit(run(command))

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
    val log = Log(settings.logLevel, settings.logFormat)
    TzdbGuard.assertSupported(log.info)
    log.info(
      s"starting: role=${settings.role.envName} instance=${settings.instanceId} " +
        s"adapters=${settings.enabledAdapters.map(_.envName).mkString(",")}"
    )
    val dataSource = Database.pooled(settings.databaseUrl, 10)
    try
      val result = Migrator(dataSource).migrateLocked()
      val target = Option(result.targetSchemaVersion).getOrElse("unchanged")
      log.info(s"migrate: ${result.migrationsExecuted} migration(s) applied, target $target")
      command match
        case Cli.Command.Migrate => 0
        case Cli.Command.Run(_)  =>
          Shutdown.serve(
            body = {
              val binding = Health.start()
              log.info(s"/healthz listening on :${Health.DefaultPort}")
              Drain(
                inbound = List("health" -> (() => binding.stop())),
                claiming = Nil,
                inFlight = InFlightRegistry(),
                budget = Shutdown.DrainBudget,
                log = log.info
              )
            },
            log = log.info
          )
    finally dataSource.close()
