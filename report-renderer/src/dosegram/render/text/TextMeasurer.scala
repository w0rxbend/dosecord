package dosegram.render.text

import dosegram.render.tokens.TextStyle

/** Port: the advance width of one line of text in pixels. Layout is pure given this function (research brief 04 section
  * 7.1, stage 3).
  */
trait TextMeasurer:
  def width(text: String, style: TextStyle): Double

/** Measures from the advance widths of the pinned font files, committed as `FontMetricsTable`. It needs no native tool
  * and no font file at run time, so the SVG goldens are identical on every machine. Kerning is not applied; the resvg
  * cross-check in `report-renderer.resvg` bounds the difference.
  */
object TableTextMeasurer extends TextMeasurer:
  private val advances: Map[String, Map[Int, Int]] =
    FontMetricsTable.Advances.map((file, packed) => file -> unpack(packed))

  def width(text: String, style: TextStyle): Double =
    val face = FontPack.face(style)
    val table = advances(face.file)
    val units = text.codePoints.toArray.iterator.map(codePoint => table.getOrElse(codePoint, missingAdvance(table))).sum
    units * style.sizePx / FontMetricsTable.UnitsPerEm(face.file)

  /** Whether the face of `style` has a glyph for the code point. */
  def covers(codePoint: Int, style: TextStyle): Boolean = advances(FontPack.face(style).file).contains(codePoint)

  // An uncovered character is replaced before measurement; if one still arrives it is measured as the widest
  // digit, so a slot is never under-estimated.
  private def missingAdvance(table: Map[Int, Int]): Int = table('0'.toInt)

  private def unpack(packed: String): Map[Int, Int] =
    packed
      .split(' ')
      .iterator
      .map { entry =>
        val separator = entry.indexOf(':')
        entry.substring(0, separator).toInt -> entry.substring(separator + 1).toInt
      }
      .toMap
