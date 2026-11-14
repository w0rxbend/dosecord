package dosegram.core.domain.imports

import scala.collection.mutable.ArrayBuffer
import scala.util.boundary
import scala.util.boundary.break

/** One record of a CSV file: its fields, and the line of the file it starts on (1 for the header). */
final case class CsvRow(line: Int, fields: Vector[String])

/** The caps of one CSV text (supervisor decision, 2026-11-15): the file is input from outside, so the reader stops at
  * the first field, row or text that passes them instead of holding it. A batch body is at most 2 MB (ARCHITECTURE
  * section 9.4); the widest template has 9 columns and its longest field is a meal's words.
  */
final case class CsvLimits(totalChars: Int, rowChars: Int, rowFields: Int, fieldChars: Int)

object CsvLimits:
  val Default: CsvLimits = CsvLimits(totalChars = 2 * 1024 * 1024, rowChars = 16_384, rowFields = 64, fieldChars = 4096)

/** RFC 4180 CSV as the export writes it (`ArchiveCsv`): fields separated by commas, a field in double quotes may hold
  * commas, quotes (doubled) and line breaks; lines end in CRLF or LF; a byte order mark at the start is dropped. A
  * spreadsheet set to a locale with a decimal comma saves with semicolons, so a header line with semicolons and no
  * comma selects the semicolon. A line whose fields are all empty is skipped.
  */
object CsvReader:

  private val ByteOrderMark = "﻿"

  /** The rows of `text`, or `None` when a quoted field is not closed or the text passes `limits`. */
  def rows(text: String, limits: CsvLimits = CsvLimits.Default): Option[List[CsvRow]] =
    if text.length > limits.totalChars then None
    else
      boundary:
        val body = if text.startsWith(ByteOrderMark) then text.drop(1) else text
        val separator = separatorOf(body)
        val rows = List.newBuilder[CsvRow]
        val fields = ArrayBuffer.empty[String]
        val field = StringBuilder()
        var rowChars = 0
        var line = 1
        var startLine = 1
        var quoted = false
        var i = 0

        def append(c: Char): Unit =
          field += c
          rowChars += 1
          if field.length > limits.fieldChars || rowChars > limits.rowChars then break(None)

        def endField(): Unit =
          fields += field.result()
          field.clear()
          if fields.size > limits.rowFields then break(None)

        def endRow(): Unit =
          endField()
          if fields.exists(_.nonEmpty) then rows += CsvRow(startLine, fields.toVector)
          fields.clear()
          rowChars = 0

        while i < body.length do
          val c = body.charAt(i)
          if quoted then
            if c == '"' then
              if i + 1 < body.length && body.charAt(i + 1) == '"' then
                append('"')
                i += 1
              else quoted = false
            else
              if c == '\n' then line += 1
              append(c)
          else
            c match
              case '"'                                                       => quoted = true
              case '\r' if i + 1 < body.length && body.charAt(i + 1) == '\n' => ()
              case '\r' | '\n'                                               =>
                endRow()
                line += 1
                startLine = line
              case `separator` => endField()
              case other       => append(other)
          i += 1
        if quoted then None
        else
          endRow()
          Some(rows.result())

  private def separatorOf(body: String): Char =
    val header = body.takeWhile(c => c != '\n' && c != '\r')
    if !header.contains(',') && header.contains(';') then ';' else ','
end CsvReader
