package dosegram.infra.db

import ox.discard

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** The roles and the claim of row-level security (V14; ARCHITECTURE section 15.3, ROADMAP G2.3). Every transaction of
  * the unit of work and every pooled statement takes one of the two serving roles; serving code never runs as the login
  * role, which owns the schema.
  */
object RowSecurity:

  /** One account: the policies admit the rows whose account is the claim, and no row without a claim. */
  val AppRole = "dosegram_app"

  /** The documented cross-account work: the grants of [[AppRole]] and a policy that admits every account. */
  val WorkerRole = "dosegram_worker"

  /** The transaction-local setting the policies compare with each row's account. */
  val ClaimSetting = "app.account_id"

  /** One transaction on a pooled connection under the role of `account`: the application role with that account as the
    * claim, or the worker role for none. Commits when `body` returns and rolls back when it throws.
    */
  def transaction[A](dataSource: DataSource, account: Option[UUID])(body: Connection => A): A =
    val conn = dataSource.getConnection
    try
      conn.setAutoCommit(false)
      account.fold(crossAccount(conn))(asAccount(conn, _))
      val result = body(conn)
      conn.commit()
      result
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.close()

  /** Takes the worker role until the end of the current transaction. */
  def crossAccount(conn: Connection): Unit =
    given Connection = conn
    sql"SELECT set_config('role', $WorkerRole, true)".queryOne[String]().discard

  /** Takes the application role with `account` as the claim until the end of the current transaction. */
  def asAccount(conn: Connection, account: UUID): Unit =
    given Connection = conn
    sql"SELECT set_config('role', $AppRole, true), set_config($ClaimSetting, ${account.toString}, true)"
      .queryOne[String]()
      .discard

  /** The start-up check: the login role must be able to take both serving roles (a superuser always can). Returns the
    * GRANT the owner runs when it cannot, so start-up refuses to serve instead of failing on the first transaction.
    */
  def missingGrant(conn: Connection): Option[String] =
    given Connection = conn
    sql"""SELECT session_user
          WHERE NOT (pg_has_role(session_user, $AppRole, 'SET') AND pg_has_role(session_user, $WorkerRole, 'SET'))"""
      .queryOne[String]()
      .map(login => s"GRANT $AppRole, $WorkerRole TO \"$login\";")
