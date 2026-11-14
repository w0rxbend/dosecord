package dosegram.infra.db

import dosegram.core.ports.ExportFile
import dosegram.core.ports.KeyService

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.language.implicitConversions

/** One table of the archive: its columns with their database types, and its rows as JSON values in column order. */
final case class ArchivedTable(name: String, columns: List[(String, String)], rows: List[List[ujson.Value]])

/** The full export of one account (PRODUCT_SPEC R-IMX-1, ROADMAP G3.10): every table that [[ExportCoverage]] covers,
  * read on the caller's connection, written as one ZIP with `export.json`, its JSON Schema `schema.json`, one CSV file
  * per table and a short readable `README.txt`. Instants carry the offset of the account's time zone, whose id is in
  * the file. Names and notes are included: the export is for the account's owner, so every sealed column is opened and
  * written under its plaintext name, and the sealed and lookup columns are left out (V10, V11).
  */
object AccountArchive:

  val FormatVersion = 1
  val Mime = "application/zip"

  private val FileDate = DateTimeFormatter.ofPattern("yyyyMMdd")

  def build(conn: Connection, keys: KeyService, accountId: UUID, at: Instant): ExportFile =
    given Connection = conn
    val zone = ZoneId.of(
      sql"SELECT timezone FROM users WHERE id = $accountId".queryOne[String]().getOrElse("UTC")
    )
    val tables = read(conn, keys, accountId, zone)
    val name = s"dosegram-export-${FileDate.format(at.atZone(zone))}.zip"
    ExportFile(name, Mime, zip(tables, zone, at))

  /** The covered tables in name order, empty ones included, so the archive always lists every domain. */
  def read(conn: Connection, keys: KeyService, accountId: UUID, zone: ZoneId): List[ArchivedTable] =
    baseTables(conn).flatMap: (table, hasOwner) =>
      selectOf(table, hasOwner).map(select => opened(query(conn, table, select, accountId, zone), keys, accountId))

  /** `table` with each sealed value opened into its plaintext column, and without the sealed and lookup columns. */
  private def opened(table: ArchivedTable, keys: KeyService, accountId: UUID): ArchivedTable =
    val sealedHere = SealedColumns.all.filter(_.table == table.name)
    val hidden = sealedHere.flatMap(column => column.encrypted :: column.lookup.map(_.column).toList).toSet
    val names = table.columns.map(_._1)
    val types = table.columns.toMap
    def open(row: Map[String, ujson.Value], column: SealedColumn): Map[String, ujson.Value] =
      (row.get(column.encrypted), row.get(column.rowKey)) match
        case (Some(ujson.Str(encoded)), Some(ujson.Str(rowKey))) =>
          val bytes = keys.open(column.forAccount(accountId, rowKey), Base64.getDecoder.decode(encoded))
          row.updated(column.plain, plainValue(types.getOrElse(column.plain, "text"), bytes))
        case _ => row
    val rows = table.rows.map: row =>
      val byName = sealedHere.foldLeft(names.zip(row).toMap)(open)
      names.filterNot(hidden).map(byName)
    ArchivedTable(table.name, table.columns.filterNot((name, _) => hidden(name)), rows)

  private def plainValue(typeName: String, bytes: Array[Byte]): ujson.Value =
    typeName match
      case "jsonb" | "json" => ujson.read(String(bytes, UTF_8))
      case "bytea"          => ujson.Str(Base64.getEncoder.encodeToString(bytes))
      case _                => ujson.Str(String(bytes, UTF_8))

  /** Every base table of the public schema, and whether it has an `account_id` column. */
  def baseTables(conn: Connection): List[(String, Boolean)] =
    given Connection = conn
    given RowMapper[(String, Boolean)] = rs => (rs.getString(1), rs.getBoolean(2))
    sql"""SELECT t.table_name,
                 EXISTS (SELECT 1 FROM information_schema.columns c
                         WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name
                           AND c.column_name = ${ExportCoverage.OwnerColumn})
          FROM information_schema.tables t
          WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
          ORDER BY t.table_name""".query[(String, Boolean)]()

  // The table names come from the schema and the registry, never from a request; each one is quoted.
  private def selectOf(table: String, hasOwner: Boolean): Option[String] =
    ExportCoverage.tables.get(table) match
      case Some(Coverage.Owned(column)) =>
        Some(s"SELECT * FROM ${quoted(table)} WHERE ${quoted(column)} = ? ORDER BY 1")
      case Some(Coverage.Selected(query)) => Some(query)
      case Some(Coverage.Excluded(_))     => None
      case None                           =>
        Option.when(hasOwner)(s"SELECT * FROM ${quoted(table)} WHERE ${ExportCoverage.OwnerColumn} = ? ORDER BY 1")

  private def quoted(identifier: String): String =
    require(identifier.matches("[a-z_][a-z0-9_]*"), "a table or column name is a plain identifier")
    s"\"$identifier\""

  private def query(conn: Connection, table: String, select: String, accountId: UUID, zone: ZoneId): ArchivedTable =
    val statement = conn.prepareStatement(select)
    try
      statement.setObject(1, accountId)
      val rs = statement.executeQuery()
      val meta = rs.getMetaData
      val columns = (1 to meta.getColumnCount).toList.map(i => meta.getColumnName(i) -> meta.getColumnTypeName(i))
      val rows = List.newBuilder[List[ujson.Value]]
      while rs.next() do rows += columns.zipWithIndex.map((column, i) => valueOf(rs, i + 1, column._2, zone))
      ArchivedTable(table, columns, rows.result())
    finally statement.close()

  /** One value as JSON: instants in the account zone with their offset, `jsonb` as JSON, bytes as base64. */
  private def valueOf(rs: ResultSet, index: Int, typeName: String, zone: ZoneId): ujson.Value =
    if rs.getObject(index) == null then ujson.Null
    else
      typeName match
        case "timestamptz" =>
          ujson.Str(rs.getObject(index, classOf[OffsetDateTime]).atZoneSameInstant(zone).toOffsetDateTime.toString)
        case "bool"                                            => ujson.Bool(rs.getBoolean(index))
        case "int2" | "int4" | "int8" | "serial" | "bigserial" => ujson.Num(rs.getLong(index).toDouble)
        case "numeric" | "float4" | "float8"                   => ujson.Num(rs.getBigDecimal(index).doubleValue)
        case "jsonb" | "json"                                  => ujson.read(rs.getString(index))
        case "bytea"                        => ujson.Str(Base64.getEncoder.encodeToString(rs.getBytes(index)))
        case array if array.startsWith("_") =>
          ujson.Arr.from(rs.getArray(index).getArray.asInstanceOf[Array[AnyRef]].map(v => ujson.Str(String.valueOf(v))))
        case _ => ujson.Str(rs.getString(index))

  private def zip(tables: List[ArchivedTable], zone: ZoneId, at: Instant): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    val out = ZipOutputStream(bytes)
    def entry(name: String, content: String): Unit =
      val zipEntry = ZipEntry(name)
      zipEntry.setTime(at.toEpochMilli)
      out.putNextEntry(zipEntry)
      out.write(content.getBytes(UTF_8))
      out.closeEntry()
    try
      entry("README.txt", readme(tables, zone, at))
      entry("export.json", ujson.write(document(tables, zone, at), indent = 2))
      entry("schema.json", ujson.write(schema(tables), indent = 2))
      tables.foreach(table => entry(s"csv/${table.name}.csv", ArchiveCsv.of(table)))
    finally out.close()
    bytes.toByteArray

  def document(tables: List[ArchivedTable], zone: ZoneId, at: Instant): ujson.Value =
    ujson.Obj(
      "format" -> "dosegram-export",
      "version" -> FormatVersion,
      "exportedAt" -> at.atZone(zone).toOffsetDateTime.toString,
      "timeZone" -> zone.getId,
      "tables" -> ujson.Obj.from(tables.map: table =>
        table.name -> ujson.Arr.from(table.rows.map(row => ujson.Obj.from(table.columns.map(_._1).zip(row)))))
    )

  /** The JSON Schema (draft 2020-12) of `export.json`, generated from the same column list as the rows. */
  def schema(tables: List[ArchivedTable]): ujson.Value =
    def row(table: ArchivedTable): ujson.Value =
      ujson.Obj(
        "type" -> "object",
        "additionalProperties" -> false,
        "required" -> ujson.Arr.from(table.columns.map(column => ujson.Str(column._1))),
        "properties" -> ujson.Obj.from(table.columns.map((name, typeName) => name -> typeOf(typeName)))
      )
    ujson.Obj(
      "$schema" -> "https://json-schema.org/draft/2020-12/schema",
      "title" -> s"Dosegram export, version $FormatVersion",
      "type" -> "object",
      "required" -> ujson.Arr("format", "version", "exportedAt", "timeZone", "tables"),
      "properties" -> ujson.Obj(
        "format" -> ujson.Obj("const" -> "dosegram-export"),
        "version" -> ujson.Obj("const" -> FormatVersion),
        "exportedAt" -> ujson.Obj("type" -> "string", "format" -> "date-time"),
        "timeZone" -> ujson.Obj("type" -> "string"),
        "tables" -> ujson.Obj(
          "type" -> "object",
          "additionalProperties" -> false,
          "properties" -> ujson.Obj.from(
            tables.map(table => table.name -> ujson.Obj("type" -> "array", "items" -> row(table)))
          )
        )
      )
    )

  /** The JSON type of a column; every column may be null. */
  def typeOf(typeName: String): ujson.Value =
    val json = typeName match
      case "bool"                                            => ujson.Obj("type" -> ujson.Arr("boolean", "null"))
      case "int2" | "int4" | "int8" | "serial" | "bigserial" => ujson.Obj("type" -> ujson.Arr("integer", "null"))
      case "numeric" | "float4" | "float8"                   => ujson.Obj("type" -> ujson.Arr("number", "null"))
      case "jsonb" | "json"                                  => ujson.Obj()
      case array if array.startsWith("_")                    =>
        ujson.Obj("type" -> ujson.Arr("array", "null"), "items" -> ujson.Obj("type" -> "string"))
      case "timestamptz" => ujson.Obj("type" -> ujson.Arr("string", "null"), "format" -> "date-time")
      case _             => ujson.Obj("type" -> ujson.Arr("string", "null"))
    json.obj("x-database-type") = typeName
    json

  private def readme(tables: List[ArchivedTable], zone: ZoneId, at: Instant): String =
    val counts = tables.map(table => f"  ${table.name}%-28s ${table.rows.size}%6d").mkString("\n")
    s"""Dosegram export
       |
       |Everything Dosegram holds about your account on ${at.atZone(zone).toOffsetDateTime}.
       |Times carry the offset of your time zone, $zone.
       |
       |export.json    every record, one list per table (format dosegram-export, version $FormatVersion)
       |schema.json    the JSON Schema of export.json
       |csv/           the same records, one CSV file per table
       |
       |Records per table:
       |$counts
       |
       |Recorded entries only. Not medical advice.
       |""".stripMargin
end AccountArchive

/** RFC 4180 CSV of one table: a header row of column names, then one line per row. JSON and array values are written as
  * JSON text.
  */
object ArchiveCsv:

  def of(table: ArchivedTable): String =
    val header = table.columns.map(column => field(column._1))
    (header :: table.rows.map(_.map(cell))).map(_.mkString(",")).mkString("", "\r\n", "\r\n")

  private def cell(value: ujson.Value): String = value match
    case ujson.Null                                 => ""
    case ujson.Str(text)                            => field(text)
    case ujson.Bool(flag)                           => flag.toString
    case ujson.Num(number)                          => field(numberText(number))
    case structured @ (_: ujson.Obj | _: ujson.Arr) => field(ujson.write(structured))

  private def numberText(number: Double): String =
    if number.isWhole && math.abs(number) < 1e15 then number.toLong.toString else BigDecimal(number).toString

  private def field(text: String): String =
    if text.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then "\"" + text.replace("\"", "\"\"") + "\""
    else text
