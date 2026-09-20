package dosecord.infra.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

import javax.sql.DataSource

/** Flyway over `classpath:db/migration` (ADR-002). */
final class Migrator(dataSource: DataSource):
  private val flyway = Flyway.configure().dataSource(dataSource).load()

  def migrate(): MigrateResult = flyway.migrate()

  /** Throws `FlywayValidateException` when applied and resolved migrations disagree. */
  def validate(): Unit = flyway.validate()
