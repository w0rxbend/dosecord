package dosegram.render.text

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A minimal TrueType reader for the font-pack tests and the metrics generator: units per em, the Unicode character
  * map (cmap formats 4 and 12) and horizontal advances (hmtx). It reads the pinned files only, never untrusted input.
  */
final class TtfFont private (bytes: Array[Byte]):
  private val data = ByteBuffer.wrap(bytes)
  private val tables: Map[String, Int] =
    (0 until unsigned16(4)).map { index =>
      val record = 12 + index * 16
      String(bytes, record, 4, "ISO-8859-1") -> data.getInt(record + 8)
    }.toMap

  val unitsPerEm: Int = unsigned16(tables("head") + 18)

  private val longMetricCount = unsigned16(tables("hhea") + 34)
  private val glyphIds: Map[Int, Int] = readCharacterMap()

  def sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"$byte%02x").mkString

  /** The advance width of the glyph for a code point, in font units; `None` when the font has no glyph for it. */
  def advance(codePoint: Int): Option[Int] =
    glyphIds.get(codePoint).filter(_ != 0).map { glyph =>
      unsigned16(tables("hmtx") + StrictMath.min(glyph, longMetricCount - 1) * 4)
    }

  private def unsigned16(offset: Int): Int = data.getShort(offset) & 0xffff

  private def readCharacterMap(): Map[Int, Int] =
    val cmap = tables("cmap")
    val subtables = (0 until unsigned16(cmap + 2)).map { index =>
      val record = cmap + 4 + index * 8
      (unsigned16(record), unsigned16(record + 2), cmap + data.getInt(record + 4))
    }
    val windowsFull = subtables.collectFirst { case (3, 10, offset) if unsigned16(offset) == 12 => format12(offset) }
    val windowsBasic = subtables.collectFirst { case (3, 1, offset) if unsigned16(offset) == 4 => format4(offset) }
    windowsFull.orElse(windowsBasic).getOrElse(throw IllegalStateException("no Unicode cmap subtable"))

  private def format12(offset: Int): Map[Int, Int] =
    (0 until data.getInt(offset + 12)).flatMap { index =>
      val group = offset + 16 + index * 12
      val (first, last, startGlyph) = (data.getInt(group), data.getInt(group + 4), data.getInt(group + 8))
      (first to last).map(codePoint => codePoint -> (startGlyph + codePoint - first))
    }.toMap

  private def format4(offset: Int): Map[Int, Int] =
    val segments = unsigned16(offset + 6) / 2
    val ends = offset + 14
    val starts = ends + segments * 2 + 2
    val deltas = starts + segments * 2
    val rangeOffsets = deltas + segments * 2
    (0 until segments).flatMap { segment =>
      val (first, last) = (unsigned16(starts + segment * 2), unsigned16(ends + segment * 2))
      val rangeOffset = unsigned16(rangeOffsets + segment * 2)
      (first to last).filter(_ != 0xffff).map { codePoint =>
        val glyph =
          if rangeOffset == 0 then codePoint + unsigned16(deltas + segment * 2)
          else
            val entry = unsigned16(rangeOffsets + segment * 2 + rangeOffset + (codePoint - first) * 2)
            if entry == 0 then 0 else entry + unsigned16(deltas + segment * 2)
        codePoint -> (glyph & 0xffff)
      }
    }.toMap

object TtfFont:
  def read(file: Path): TtfFont = TtfFont(Files.readAllBytes(file))
