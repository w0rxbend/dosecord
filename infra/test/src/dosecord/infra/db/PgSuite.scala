package dosecord.infra.db

import java.sql.Connection
import java.sql.DriverManager

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.utility.DockerImageName

/** Testcontainers Postgres 18 harness: one container per suite, migrated to head in `beforeAll`.
  */
trait PgSuite extends munit.FunSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:18-alpine")
  )

  protected def pg: PostgreSQLContainer = withContainers { case c: PostgreSQLContainer => c }

  protected lazy val dataSource: HikariDataSource =
    Database.pooled(pg.jdbcUrl, pg.username, pg.password)

  override def beforeAll(): Unit =
    super.beforeAll()
    Migrator(dataSource).migrate()

  override def afterAll(): Unit =
    if dataSource != null then dataSource.close()
    super.afterAll()

  protected def withConnection[A](f: Connection => A): A =
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()

  /** A session-state-free connection for role/GUC tests. */
  protected def withFreshConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
    try f(conn)
    finally conn.close()
