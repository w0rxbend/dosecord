package dosegram.core.domain.imports

import scala.collection.mutable.ArrayBuffer

/** One record of a CSV file: its fields, and the line of the file it starts on (1 for the header). */
final case class CsvRow(line: Int, fields: Vector[String])

/** RFC 4180 CSV as the export writes it (`ArchiveCsv`): fields separated by commas, a field in double quotes may hold
  * commas, quotes (doubled) and line breaks; lines end in CRLF or LF; a byte order mark at the start is dropped. A
  * spreadsheet set to a locale with a decimal comma saves with semicolons, so a header line with semicolons and no
  * comma selects the semicolon. A line whose fields are all empty is skipped.
  */
object CsvReader:

  private val ByteOrderMark = "\uFEFF"

  /** The rows of `text`, or `None` when a quoted field is not closed. */
  def rows(text: String): Option[List[CsvRow]] =
    val body = if text.startsWith(ByteOrderMark) then text.drop(1) else text
    val separator = separatorOf(body)
    val rows = List.newBuilder[CsvRow]
    val fields = ArrayBuffer.empty[String]
    val field = StringBuilder()
    var line = 1
    var startLine = 1
    var quoted = false
    var i = 0

    def endField(): Unit =
      fields += field.result()
      field.clear()

    def endRow(): Unit =
      endField()
      if fields.exists(_.nonEmpty) then rows += CsvRow(startLine, fields.toVector)
      fields.clear()

    while i < body.length do
      val c = body.charAt(i)
      if quoted then
        if c == '"' then
          if i + 1 < body.length && body.charAt(i + 1) == '"' then
            field += '"'
            i += 1
          else quoted = false
        else
          if c == '\n' then line += 1
          field += c
      else
        c match
          case '"'                                                       => quoted = true
          case '\r' if i + 1 < body.length && body.charAt(i + 1) == '\n' => ()
          case '\r' | '\n'                                               =>
            endRow()
            line += 1
            startLine = line
          case `separator` => endField()
          case other       => field += other
      i += 1
    if quoted then None
    else
      endRow()
      Some(rows.result())

  private def separatorOf(body: String): Char =
    val header = body.takeWhile(c => c != '\n' && c != '\r')
    if !header.contains(',') && header.contains(';') then ';' else ','
end CsvReader
