package dosegram.render.text

import java.nio.file.Files
import java.nio.file.Path

/** Where the pinned font files are on this machine. The build passes the directory to every test fork. */
object PinnedFonts:
  def dir: Path =
    sys.env
      .get("DOSEGRAM_FONTS_DIR")
      .map(Path.of(_))
      .getOrElse(throw IllegalStateException("DOSEGRAM_FONTS_DIR is not set; run the tests through Mill"))

  def read(face: FontFace): TtfFont = TtfFont.read(dir.resolve(face.file))

/** Derives `FontMetricsTable.scala` from the pinned font files. The committed table must equal this output
  * (`FontPackSuite`); regenerate with `./mill report-renderer.test.runMain dosegram.render.text.fontMetricsGenerate`.
  */
object FontMetricsSource:
  val Target = "report-renderer/src/dosegram/render/text/FontMetricsTable.scala"

  /** Latin with its first extension block, Greek, Cyrillic, general punctuation, the minus sign and the replacement
    * character. A character outside this set is drawn as U+FFFD (research brief 04 section 7.4).
    */
  val CodePoints: Vector[Int] =
    ((0x20 to 0x7e) ++ (0xa0 to 0x17f) ++ (0x384 to 0x3ce) ++ (0x400 to 0x45f) ++ Seq(0x490, 0x491) ++ (0x2010 to 0x2026)
      ++ Seq(0x2212, 0xfffd)).toVector

  def packedAdvances(font: TtfFont): String =
    CodePoints.flatMap(codePoint => font.advance(codePoint).map(advance => s"$codePoint:$advance")).mkString(" ")

  def render(fonts: Vector[(FontFace, TtfFont)]): String =
    val units = fonts.map((face, font) => s"""    "${face.file}" -> ${font.unitsPerEm}""").mkString(",\n")
    val advances = fonts.map((face, font) => s"""    "${face.file}" -> "${packedAdvances(font)}"""").mkString(",\n")
    s"""package dosegram.render.text
       |
       |/** Advance widths of the pinned font pack, in font units, as `codePoint:advance` pairs. Generated from the files
       |  * in `report-renderer/fonts` by `fontMetricsGenerate`; `FontPackSuite` fails when it drifts. Do not edit.
       |  */
       |private[text] object FontMetricsTable:
       |  val UnitsPerEm: Map[String, Int] = Map(
       |$units
       |  )
       |
       |  val Advances: Map[String, String] = Map(
       |$advances
       |  )
       |""".stripMargin

@main def fontMetricsGenerate(): Unit =
  val fonts = FontPack.Faces.map(face => face -> PinnedFonts.read(face))
  Files.writeString(Path.of(FontMetricsSource.Target), FontMetricsSource.render(fonts))
  println(s"wrote ${FontMetricsSource.Target}")
