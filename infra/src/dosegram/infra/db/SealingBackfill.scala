package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.infra.crypto.DataKeys

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions
import scala.util.Try

/** What one column of the backfill did: values sealed in this run, and values whose sealed copy was checked against the
  * plaintext that V11 will empty.
  */
final case class SealingCount(column: SealedColumn, sealedNow: Int, verified: Int)

/** The online backfill between V10 and V11 (ROADMAP G2.4, G2.4b; supervisor decision, 2026-11-14). For every
  * [[SealedColumns]] entry it seals the values that are still only in the plaintext column, [[BatchRows]] rows per
  * transaction under `dosegram.sealing`, and checks each sealed value by opening it before it writes it. A run that
  * stops part-way leaves whole batches behind; the next run takes up the rows still without a sealed value, so it is
  * idempotent and resumable. Then it opens every sealed copy that still has a plaintext next to it and compares the
  * two, sealing again a copy that differs, so V11 empties only values whose sealed copy is proven. Logs carry table,
  * column and counts, never a value.
  */
final class SealingBackfill(dataSource: DataSource, keys: DataKeys):
  import SealingBackfill.*

  def run(log: String => Unit): List[SealingCount] =
    SealedColumns.all.map: column =>
      val sealedNow = Iterator.continually(sealBatch(column)).takeWhile(_ > 0).sum
      val (verified, resealed) = verify(column)
      log(
        s"sealing: ${column.table}.${column.plain} sealed ${sealedNow + resealed} value(s), verified $verified"
      )
      SealingCount(column, sealedNow + resealed, verified)

  private def sealBatch(column: SealedColumn): Int =
    transaction: conn =>
      val keyService = keys.beforeAuditLog(conn)
      val rows = select(
        conn,
        s"SELECT t.ctid::text, ${selection(column)} FROM ${column.table} t " +
          s"WHERE t.${column.plain} IS NOT NULL AND t.${column.encrypted} IS NULL " +
          s"ORDER BY t.${column.rowKey} LIMIT $BatchRows FOR UPDATE OF t"
      )(rs => (rs.getString(1), plainOf(column, rs, 2)))
      rows.foreach((ctid, plain) => write(conn, keyService, column, ctid, plain))
      rows.size

  private def write(conn: Connection, keyService: KeyService, column: SealedColumn, ctid: String, plain: Plain): Unit =
    val field = column.field(plain.owner, plain.row)
    val sealedValue = keyService.seal(field, plain.bytes)
    if !keyService.open(field, sealedValue).sameElements(plain.bytes) then
      throw IllegalStateException(s"sealing: a value of ${column.table}.${column.plain} did not open as written")
    val lookup = column.lookup.map(_ => keyService.lookup(column, accountOf(plain.owner), String(plain.bytes, UTF_8)))
    val setLookup = column.lookup.fold("")(spec => s", ${spec.column} = ?")
    val binds = Vector[SqlBind](sealedValue) ++ lookup.map(hash => hash: SqlBind) :+ (ctid: SqlBind)
    given Connection = conn
    val updated = SqlQuery(s"UPDATE ${column.table} SET ${column.encrypted} = ?$setLookup WHERE ctid = ?::tid", binds)
      .execute()
    if updated != 1 then throw IllegalStateException(s"sealing: a row of ${column.table} moved while it was sealed")

  /** Opens every sealed copy that still has its plaintext and returns how many it checked and how many it sealed again.
    * A copy that differs from its plaintext is sealed again from the plaintext, which is the value until V11: that
    * happens when the previous release, started again after a stopped run, changed a value the run had sealed.
    */
  private def verify(column: SealedColumn): (Int, Int) =
    transaction: conn =>
      val keyService = keys.beforeAuditLog(conn)
      val rows = select(
        conn,
        s"SELECT t.ctid::text, t.${column.encrypted}, ${selection(column)} FROM ${column.table} t " +
          s"WHERE t.${column.plain} IS NOT NULL AND t.${column.encrypted} IS NOT NULL FOR UPDATE OF t"
      )(rs => (rs.getString(1), rs.getBytes(2), plainOf(column, rs, 3)))
      val stale = rows.filterNot: (_, sealedValue, plain) =>
        val field = column.field(plain.owner, plain.row)
        Try(keyService.open(field, sealedValue)).toOption.exists(_.sameElements(plain.bytes))
      stale.foreach((ctid, _, plain) => write(conn, keyService, column, ctid, plain))
      (rows.size, stale.size)

  private def selection(column: SealedColumn): String =
    val owner = column.owner match
      case SealOwner.AccountOf(expression) => s"($expression)::text"
      case SealOwner.Service(_)            => "NULL::text"
    s"t.${column.rowKey}::text, $owner, t.${column.plain}"

  private def select[A](conn: Connection, statement: String)(row: ResultSet => A): List[A] =
    val ps = conn.prepareStatement(statement)
    try
      val rs = ps.executeQuery()
      val out = List.newBuilder[A]
      while rs.next() do out += row(rs)
      out.result()
    finally ps.close()

  /** The row key, the owner and the plaintext of [[selection]], starting at column `from`. */
  private def plainOf(column: SealedColumn, rs: ResultSet, from: Int): Plain =
    val owner = column.owner match
      case SealOwner.AccountOf(_) =>
        val account = Option(rs.getString(from + 1))
          .getOrElse(throw IllegalStateException(s"sealing: a row of ${column.table} has no account"))
        KeyOwner.Account(UUID.fromString(account))
      case SealOwner.Service(purpose) => KeyOwner.Service(purpose)
    val valueAt = from + 2
    val bytes =
      if rs.getMetaData.getColumnTypeName(valueAt) == "bytea" then rs.getBytes(valueAt)
      else rs.getString(valueAt).getBytes(UTF_8)
    Plain(rs.getString(from), owner, bytes)

  private def accountOf(owner: KeyOwner): UUID = owner match
    case KeyOwner.Account(id) => id
    case KeyOwner.Service(_)  => throw IllegalStateException("a lookup column needs an account owner")

  private def transaction[A](f: Connection => A): A =
    val conn = dataSource.getConnection
    try
      conn.setAutoCommit(false)
      val statement = conn.createStatement()
      try statement.execute("SET LOCAL dosegram.sealing = 'on'")
      finally statement.close()
      val result = f(conn)
      conn.commit()
      result
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.close()

object SealingBackfill:

  /** One row still to seal or to verify: its key as text, its owner and the plaintext bytes. */
  private final case class Plain(row: String, owner: KeyOwner, bytes: Array[Byte])

  /** Rows per transaction: small enough that a batch holds its row locks for a moment only. */
  val BatchRows: Int = 200
