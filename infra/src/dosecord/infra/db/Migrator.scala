package dosecord.infra.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/** Flyway over `classpath:db/migration` (ADR-002). */
final class Migrator(dataSource: DataSource):
  private val flyway = Flyway.configure().dataSource(dataSource).load()

  def migrate(): MigrateResult = flyway.migrate()

  /** Throws `FlywayValidateException` when applied and resolved migrations disagree. */
  def validate(): Unit = flyway.validate()

  /** `dosecord migrate`: Flyway under a session-level `pg_advisory_lock` so concurrent invocations serialise (ADR-002).
    * The lock is taken on a dedicated connection and Flyway is pinned to that same connection; a second process blocks
    * in `pg_advisory_lock` until the first one commits and releases.
    */
  def migrateLocked(lockKey: Long = Migrator.AdvisoryLockKey): MigrateResult =
    val conn = dataSource.getConnection
    try
      val stmt = conn.createStatement()
      try stmt.execute(s"SELECT pg_advisory_lock($lockKey)")
      finally stmt.close()
      try Flyway.configure().dataSource(Migrator.SingleConnection(conn)).load().migrate()
      finally
        val unlock = conn.createStatement()
        try unlock.execute(s"SELECT pg_advisory_unlock($lockKey)")
        finally unlock.close()
    finally conn.close()

object Migrator:
  /** Session-level advisory lock key serialising `dosecord migrate` across processes. */
  val AdvisoryLockKey: Long = 0x7a65d05ec0dL

  /** A `DataSource` that always hands out the same (lock-holding) connection behind a proxy whose `close` is a no-op —
    * Flyway closes every connection it borrows, but the caller owns the connection's lifecycle.
    */
  private final class SingleConnection(conn: Connection) extends DataSource:
    private val nonClosing = java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[Connection].getClassLoader,
        Array(classOf[Connection]),
        (_, method, args) => if method.getName == "close" then () else method.invoke(conn, args*)
      )
      .asInstanceOf[Connection]
    override def getConnection(): Connection = nonClosing
    override def getConnection(username: String, password: String): Connection = nonClosing
    override def getLogWriter: PrintWriter = throw UnsupportedOperationException()
    override def setLogWriter(out: PrintWriter): Unit = throw UnsupportedOperationException()
    override def setLoginTimeout(seconds: Int): Unit = throw UnsupportedOperationException()
    override def getLoginTimeout: Int = throw UnsupportedOperationException()
    override def getParentLogger: Logger = throw UnsupportedOperationException()
    override def unwrap[T](iface: Class[T]): T = throw UnsupportedOperationException()
    override def isWrapperFor(iface: Class[?]): Boolean = false
