package dosegram.infra.db

import dosegram.core.ports.Wake
import org.postgresql.PGConnection

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import scala.util.control.NonFatal

/** The LISTEN half of ADR-003's wake-up (DESIGN.md section 7.4): a dedicated raw connection — LISTEN is per-session, so
  * a pooled connection would lose the subscription when returned to the pool — LISTENing on `dosegram_wake`.
  * `awaitOrTimeout` blocks until a NOTIFY arrives or the timeout (the loop's fallback poll) elapses.
  *
  * The first connection is opened here, so a database that cannot be reached fails the start. A connection that breaks
  * later (a restart of the database, a terminated backend) is closed and the error is thrown to the loop, which logs it
  * and goes on; the next wait opens a new connection. A NOTIFY sent while no connection listens is lost, and the loop's
  * own tick and poll interval cover it.
  */
final class PgNotifyWake private (open: () => Connection) extends Wake, AutoCloseable:

  @volatile private var listener: Option[PgNotifyWake.Listener] = Some(PgNotifyWake.listening(open()))

  override def awaitOrTimeout(timeout: Duration): Unit =
    val current = listener.getOrElse(PgNotifyWake.listening(open()))
    listener = Some(current)
    try current.await(timeout)
    catch
      case e: SQLException =>
        listener = None
        PgNotifyWake.closeQuietly(current.conn)
        throw e

  override def close(): Unit = listener.foreach(_.close())

object PgNotifyWake:
  private val Channel = "dosegram_wake"

  def apply(jdbcUrl: String, user: String, password: String): PgNotifyWake =
    new PgNotifyWake(() => DriverManager.getConnection(jdbcUrl, user, password))

  /** Over a `DATABASE_URL` that carries its own credentials as pgjdbc query parameters (DESIGN.md section 12). */
  def apply(jdbcUrl: String): PgNotifyWake = new PgNotifyWake(() => DriverManager.getConnection(jdbcUrl))

  /** A connection that cannot LISTEN is closed, not leaked. */
  private def listening(conn: Connection): Listener =
    try Listener(conn)
    catch
      case NonFatal(e) =>
        closeQuietly(conn)
        throw e

  // The connection is already broken or half open; closing it can fail too, and there is nothing left to release.
  private def closeQuietly(conn: Connection): Unit =
    try conn.close()
    catch case NonFatal(_) => ()

  /** One LISTENing connection. */
  private final class Listener(val conn: Connection):
    conn.setAutoCommit(true)
    private val pg = conn.unwrap(classOf[PGConnection])
    private val listen = conn.createStatement()
    try listen.execute(s"LISTEN $Channel") // a fixed internal identifier, never user input
    finally listen.close()

    def await(timeout: Duration): Unit =
      // pgjdbc reads a timeout of 0 as "block until a NOTIFY"; the no-argument form only drains what is pending.
      if timeout.isZero || timeout.isNegative then { pg.getNotifications(); () }
      else
        try { pg.getNotifications(math.min(timeout.toMillis, Int.MaxValue.toLong).toInt); () }
        catch
          case e: SQLException =>
            // A cancelled loop fork interrupts the blocking read; end the fork quietly instead of crashing the scope.
            // A connection the database closed is a broken connection, not a cancellation.
            if Thread.currentThread().isInterrupted then
              throw new InterruptedException(s"wake listener stopped: ${e.getClass.getSimpleName}")
            else throw e

    def close(): Unit = conn.close()
