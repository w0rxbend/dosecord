package dosecord.infra.db

import dosecord.core.ports.Wake
import org.postgresql.PGConnection

import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration

/** The LISTEN half of ADR-003's wake-up (DESIGN.md section 7.4): a dedicated raw connection — LISTEN is per-session, so
  * a pooled connection would lose the subscription when returned to the pool — LISTENing on `dosecord_wake`.
  * `awaitOrTimeout` blocks until a NOTIFY arrives or the timeout (the loop's fallback poll) elapses. There is no
  * reconnect logic yet: a broken connection fails the loop's fork and supervision restarts it.
  */
final class PgNotifyWake(jdbcUrl: String, user: String, password: String, channel: String = "dosecord_wake")
    extends Wake,
      AutoCloseable:

  private val conn = DriverManager.getConnection(jdbcUrl, user, password)
  conn.setAutoCommit(true)
  private val pg = conn.unwrap(classOf[PGConnection])
  private val listen = conn.createStatement()
  try listen.execute(s"LISTEN $channel") // a fixed internal identifier, never user input
  finally listen.close()

  override def awaitOrTimeout(timeout: Duration): Unit =
    if timeout.isZero || timeout.isNegative then { pg.getNotifications(0); () }
    else
      try { pg.getNotifications(math.min(timeout.toMillis, Int.MaxValue.toLong).toInt); () }
      catch
        case e: SQLException =>
          // A cancelled loop fork interrupts the blocking read; end the fork quietly instead of crashing the scope.
          if Thread.currentThread().isInterrupted || conn.isClosed then
            throw new InterruptedException(s"wake listener stopped: ${e.getMessage}")
          else throw e

  override def close(): Unit = conn.close()
