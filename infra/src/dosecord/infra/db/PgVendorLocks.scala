package dosecord.infra.db

import java.sql.Connection
import javax.sql.DataSource
import scala.language.implicitConversions

/** Per-vendor dispatcher ownership (DESIGN.md section 3, ADR-009): each process takes
  * `pg_advisory_lock(hashtext('vendor:' || name))` on a dedicated connection for every vendor it hosts, so two
  * processes cannot own the same vendor and a vendor's dispatch never leaves the process holding its client. The locks
  * are held for the process lifetime; `close` releases them (shutdown, or a failed startup).
  */
final class PgVendorLocks(dataSource: DataSource, vendors: Seq[String]) extends AutoCloseable:
  private val conn: Connection = dataSource.getConnection
  private given Connection = conn

  // pg_advisory_lock/unlock return void; there is no column to map.
  private given RowMapper[Unit] = _ => ()

  /** Blocks until every vendor lock is held. */
  def lockAll(): Unit =
    vendors.foreach: vendor =>
      val key = PgVendorLocks.key(vendor)
      sql"SELECT pg_advisory_lock(hashtext($key))".query[Unit]()

  override def close(): Unit =
    try
      vendors.foreach: vendor =>
        val key = PgVendorLocks.key(vendor)
        sql"SELECT pg_advisory_unlock(hashtext($key))".query[Unit]()
    finally conn.close()

object PgVendorLocks:
  def key(vendor: String): String = s"vendor:$vendor"
