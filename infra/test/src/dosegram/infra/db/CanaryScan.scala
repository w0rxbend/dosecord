package dosegram.infra.db

import java.sql.Connection

/** The whole-database canary scan of ROADMAP G2.4b: every column of every table of the public schema, read as text
  * (a byte column searched for the canary's UTF-8 bytes), and returns where a canary was found as `table.column`. A
  * `pg_dump` prints a byte column in hex, so a grep of the dump alone would miss plaintext kept in bytes.
  */
object CanaryScan:

  final case class Hit(table: String, column: String, canary: String)

  def columns(conn: Connection, canaries: List[String]): List[Hit] =
    val st = conn.createStatement()
    val all =
      try
        val rs = st.executeQuery(
          """SELECT c.table_name::text, c.column_name::text, c.data_type::text
             FROM information_schema.columns c
             JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
             WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
             ORDER BY 1, 2"""
        )
        Iterator.continually(rs).takeWhile(_.next()).map(r => (r.getString(1), r.getString(2), r.getString(3))).toList
      finally st.close()
    for
      (table, column, dataType) <- all
      canary <- canaries
      if holds(conn, table, column, dataType, canary)
    yield Hit(table, column, canary)

  private def holds(conn: Connection, table: String, column: String, dataType: String, canary: String): Boolean =
    val matches =
      if dataType == "bytea" then s"""position(convert_to(?, 'UTF8') IN "$column") > 0"""
      else s""""$column"::text ILIKE '%' || ? || '%'"""
    // Table and column names come from the catalogue, never from input.
    val ps = conn.prepareStatement(s"""SELECT EXISTS (SELECT 1 FROM "$table" WHERE $matches)""")
    try
      ps.setString(1, canary)
      val rs = ps.executeQuery()
      rs.next()
      rs.getBoolean(1)
    finally ps.close()
