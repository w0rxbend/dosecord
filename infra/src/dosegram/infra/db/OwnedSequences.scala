package dosegram.infra.db

import java.sql.Connection
import scala.util.Using

/** The sequences that number the rows of a column (`bigserial`, identity). A copy of rows with their numbers, without
  * `setval`, leaves a sequence behind its column: every later insert fails on the primary key until the sequence has
  * passed the copied rows. The live database had this on 2026-11-14 (`domain_events.seq`): the reminder of the day and
  * two button taps failed with SQL state 23505. Start-up moves each such sequence to its column's largest value.
  */
object OwnedSequences:

  /** One sequence behind its column: the sequence, the column it numbers, and the value it is moved to. */
  final case class Advanced(sequence: String, column: String, to: Long)

  /** Moves every sequence of the `public` schema that is behind its column; returns what it moved. Runs as the schema
    * owner, which may set every sequence.
    */
  def align(conn: Connection): List[Advanced] =
    owned(conn).flatMap: (sequence, table, column) =>
      val largest = single(conn, s"SELECT max($column) FROM $table")
      val next = single(conn, s"SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM $sequence")
      Option.when(largest > next):
        Using.resource(conn.prepareStatement("SELECT setval(?::regclass, ?)")): statement =>
          statement.setString(1, sequence)
          statement.setLong(2, largest)
          statement.executeQuery().close()
        Advanced(sequence, s"$table.$column", largest)

  /** Each owned sequence with its table and column, quoted as identifiers by the catalog. */
  private def owned(conn: Connection): List[(String, String, String)] =
    Using.resource(conn.createStatement()): statement =>
      val rs = statement.executeQuery(
        """SELECT s.oid::regclass::text, t.oid::regclass::text, quote_ident(a.attname)
           FROM pg_class s
           JOIN pg_namespace n ON n.oid = s.relnamespace
           JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
           JOIN pg_class t ON t.oid = d.refobjid
           JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
           WHERE s.relkind = 'S' AND n.nspname = 'public'
           ORDER BY 1"""
      )
      Iterator.continually(rs).takeWhile(_.next()).map(r => (r.getString(1), r.getString(2), r.getString(3))).toList

  /** A query of this object over catalog names, never input: one number, 0 for an empty table. */
  private def single(conn: Connection, query: String): Long =
    Using.resource(conn.createStatement()): statement =>
      val rs = statement.executeQuery(query)
      rs.next()
      rs.getLong(1)
end OwnedSequences
