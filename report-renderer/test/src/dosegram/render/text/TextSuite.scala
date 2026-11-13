package dosegram.render.text

import dosegram.render.Samples
import dosegram.render.tokens.TextStyle
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

class TextSuite extends munit.ScalaCheckSuite:
  private val fitter = Samples.Fitter
  private val Ukrainian = "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯабвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
  private val English = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private val DigitsAndPunctuation = "0123456789 .,:;!?%()-+/'\"–—…·"

  test("control characters and bidirectional controls are removed, white space is collapsed (VA-L10N-06)"):
    assertEquals(TextFitter.sanitised("  Aspirin‮⁦⁩ \t\n 100\u0007 mg‏ "), "Aspirin 100 mg")

  test("a run of characters without a glyph becomes one replacement character"):
    assertEquals(TextFitter.drawable("Tea 🚀🚀 שלום ok", TextStyle.Body), "Tea � � ok")

  test("text that fits is left as written"):
    assertEquals(fitter.line("Water", TextStyle.Body, 400), FittedText(Vector("Water"), TextStyle.Body))

  test("text too wide for its slot is condensed before anything is cut (VA-TY-04)"):
    val name = "Metformin hydrochloride"
    val regular = fitter.width(name, TextStyle.Body)
    val fitted = fitter.line(name, TextStyle.Body, regular - 10)
    assertEquals(fitted, FittedText(Vector(name), TextStyle.Body.condensed))

  test("a name too wide when condensed takes a second line, then an ellipsis"):
    val twoLines = fitter.twoLines("Metformin hydrochloride extended release", TextStyle.BodyMedium, 520)
    assertEquals(twoLines.lines, Vector("Metformin hydrochloride", "extended release"))
    val cut = fitter.twoLines("Metformin hydrochloride extended release tablets for the evening", TextStyle.BodyMedium, 520)
    assertEquals(cut.lines.size, 2)
    assert(cut.lines(1).endsWith("…"), cut.lines(1))

  test("the font size is never reduced to make text fit"):
    val fitted = fitter.twoLines("A very long medication name that cannot fit anywhere at all", TextStyle.BodyMedium, 200)
    assertEquals(fitted.style.sizePx, TextStyle.BodyMedium.sizePx)

  private val hostileText: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumStr, Gen.asciiPrintableStr, Gen.oneOf(" ", "🚀", "שלום", "‮", "WWWWWWWWWWWWWWWWWWWW", "Їжак"))).map(_.mkString)

  property("fitted text never overflows its slot, whatever the input (VA-LA-01)"):
    forAll(hostileText, Gen.choose(60.0, 900.0)) { (text, slot) =>
      val one = fitter.line(text, TextStyle.Body, slot)
      val two = fitter.twoLines(text, TextStyle.BodyMedium, slot)
      (one.lines ++ two.lines).zip(Vector.fill(one.lines.size)(one.style) ++ Vector.fill(two.lines.size)(two.style)).forall {
        (line, style) => fitter.width(line, style) <= slot
      }
    }

  test("the committed metrics table equals the one derived from the pinned font files"):
    FontPack.Faces.foreach { face =>
      val font = PinnedFonts.read(face)
      assertEquals(FontMetricsTable.Advances(face.file), FontMetricsSource.packedAdvances(font), face.file)
      assertEquals(FontMetricsTable.UnitsPerEm(face.file), font.unitsPerEm, face.file)
    }

  test("every pinned font file has the recorded checksum"):
    FontPack.Faces.foreach(face => assertEquals(PinnedFonts.read(face).sha256, face.sha256, face.file))

  test("every face covers the English and Ukrainian alphabets, the digits and the catalogue punctuation (VA-TY-05)"):
    for
      face <- FontPack.Faces
      character <- (English + Ukrainian + DigitsAndPunctuation).toSeq
    do assert(PinnedFonts.read(face).advance(character.toInt).isDefined, s"${face.file} lacks '$character'")

  test("the text faces have tabular digits, so numerals align in a column (VA-TY-02)"):
    FontPack.Faces.filter(_.family.startsWith("IBM Plex Sans")).foreach { face =>
      val widths = ('0' to '9').map(digit => PinnedFonts.read(face).advance(digit.toInt)).distinct
      assertEquals(widths.size, 1, face.file)
    }
    val style = TextStyle.Value
    assertEquals(fitter.width("111", style), fitter.width("888", style))

  test("every file in the fonts directory is a pinned face or a licence, and the notices name each of them"):
    val notices = Files.readString(PinnedFonts.dir.getParent.getParent.resolve("THIRD_PARTY_NOTICES.md"))
    val files = Files.list(PinnedFonts.dir).iterator.asScala.map(_.getFileName.toString).toVector.sorted
    val fonts = FontPack.Faces.map(_.file)
    assertEquals(files.filter(_.endsWith(".ttf")), fonts.sorted)
    assertEquals(files.filterNot(_.endsWith(".ttf")), Vector("OFL-Geologica.txt", "OFL-IBM-Plex.txt"))
    FontPack.Faces.foreach { face =>
      assert(notices.contains(face.file), s"the notices do not list ${face.file}")
      assert(notices.contains(face.sha256), s"the notices do not carry the checksum of ${face.file}")
    }
    files.filter(_.startsWith("OFL-")).foreach { licence =>
      assert(Files.readString(PinnedFonts.dir.resolve(licence)).contains("SIL Open Font License, Version 1.1"), licence)
    }
