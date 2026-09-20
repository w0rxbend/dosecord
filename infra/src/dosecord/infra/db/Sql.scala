package dosecord.infra.db

import org.postgresql.util.PGobject

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

/** A raw JSON document bound to or read from a `jsonb` column. */
final case class Jsonb(value: String)

/** One bind parameter of a [[SqlQuery]]: writes a value into a prepared statement, allocating server-side arrays on
  * `conn` when needed and registering them in `cleanup` so they are freed after execution.
  */
trait SqlBind:
  def bind(ps: PreparedStatement, index: Int, conn: Connection, cleanup: ArrayBuffer[java.sql.Array]): Unit

object SqlBind:
  given Conversion[String, SqlBind] = v => (ps, i, _, _) => ps.setString(i, v)
  given Conversion[Int, SqlBind] = v => (ps, i, _, _) => ps.setInt(i, v)
  given Conversion[Long, SqlBind] = v => (ps, i, _, _) => ps.setLong(i, v)
  given Conversion[Boolean, SqlBind] = v => (ps, i, _, _) => ps.setBoolean(i, v)
  given Conversion[UUID, SqlBind] = v => (ps, i, _, _) => ps.setObject(i, v)
  given Conversion[Instant, SqlBind] = v => (ps, i, _, _) => ps.setObject(i, v.atOffset(ZoneOffset.UTC))
  given Conversion[Jsonb, SqlBind] = v =>
    (ps, i, _, _) =>
      val pg = PGobject()
      pg.setType("jsonb")
      pg.setValue(v.value)
      ps.setObject(i, pg)
  given Conversion[Array[String], SqlBind] = v =>
    (ps, i, conn, cleanup) =>
      val arr = conn.createArrayOf("text", v.map(s => s: AnyRef))
      cleanup += arr
      ps.setArray(i, arr)
  given [A](using toBind: Conversion[A, SqlBind]): Conversion[Option[A], SqlBind] =
    case Some(v) => toBind(v)
    case None    => (ps, i, _, _) => ps.setNull(i, Types.OTHER)

/** Reads one result row into `A`. Hand-written per repository; the givens cover single-column reads.
  */
trait RowMapper[A]:
  def read(rs: ResultSet): A

object RowMapper:
  given RowMapper[String] = _.getString(1)
  given RowMapper[Int] = _.getInt(1)
  given RowMapper[Long] = _.getLong(1)
  given RowMapper[Boolean] = _.getBoolean(1)
  given RowMapper[UUID] = _.getObject(1, classOf[UUID])
  given RowMapper[Instant] = _.getObject(1, classOf[OffsetDateTime]).toInstant
  given RowMapper[Jsonb] = rs => Jsonb(rs.getString(1))
  given RowMapper[Array[String]] = rs => rs.getArray(1).getArray.asInstanceOf[Array[AnyRef]].map(_.toString)

/** Column getters for hand-written [[RowMapper]]s. */
extension (rs: ResultSet)
  def uuid(col: String): UUID = rs.getObject(col, classOf[UUID])
  def instant(col: String): Instant = rs.getObject(col, classOf[OffsetDateTime]).toInstant
  def optInstant(col: String): Option[Instant] = Option(rs.getObject(col, classOf[OffsetDateTime])).map(_.toInstant)
  def optString(col: String): Option[String] = Option(rs.getString(col))
  def jsonb(col: String): Jsonb = Jsonb(rs.getString(col))
  def optJsonb(col: String): Option[Jsonb] = Option(rs.getString(col)).map(Jsonb(_))
  def stringArray(col: String): Array[String] = rs.getArray(col).getArray.asInstanceOf[Array[AnyRef]].map(_.toString)

/** A parameterized statement produced by the [[sql]] interpolator. */
final class SqlQuery(statement: String, binds: Vector[SqlBind]):

  /** INSERT/UPDATE/DELETE; returns the affected row count. */
  def execute()(using conn: Connection): Int = withStatement(_.executeUpdate())

  def query[A]()(using conn: Connection, mapper: RowMapper[A]): List[A] =
    withStatement: ps =>
      val rs = ps.executeQuery()
      val out = List.newBuilder[A]
      while rs.next() do out += mapper.read(rs)
      out.result()

  def queryOne[A]()(using conn: Connection, mapper: RowMapper[A]): Option[A] =
    query[A]().headOption

  private def withStatement[A](f: PreparedStatement => A)(using conn: Connection): A =
    val ps = conn.prepareStatement(statement)
    val arrays = ArrayBuffer.empty[java.sql.Array]
    try
      binds.zipWithIndex.foreach((bind, i) => bind.bind(ps, i + 1, conn, arrays))
      f(ps)
    finally
      arrays.foreach(_.free())
      ps.close()

/** Hand-written SQL with typed bind parameters (ADR-002): `sql"... $x ..."`. Every interpolated value becomes a `?`
  * parameter; SQL text is never built from values.
  */
extension (sc: StringContext) def sql(args: SqlBind*): SqlQuery = SqlQuery(sc.parts.mkString("?"), args.toVector)
