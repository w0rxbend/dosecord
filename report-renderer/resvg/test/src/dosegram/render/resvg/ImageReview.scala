package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.GreyscaleContrast
import dosegram.render.Samples
import dosegram.render.Vision
import dosegram.render.card.Privacy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.Theme

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** The image review of ROADMAP G4.7 (images track, 2026-11-28), written for a reviewer and for the CI artefact:
  *
  *   - `contact-<variant>.png`: every card of the review set in the light, dark and discreet variant at 360 px display
  *     width, with the 36 blob states, and the same sheet as a reader sees it in greyscale and with deuteranopia and
  *     protanopia (`contact-<variant>-<vision>.png`);
  *   - `vision/<card>.png`: each card's light and dark page side by side in the four ways of seeing (VA-ID-05);
  *   - `greyscale-contrast.txt`: the result of the 3:1 check of [[GreyscaleContrast]] over every card in English and
  *     Ukrainian, light, dark and discreet.
  *
  * It fails, after writing everything, when a card has no page or a mark keeps under 3:1 in greyscale.
  */
object ImageReview:
  private val Seen: Vector[Vision] = Vector(Vision.Greyscale, Vision.Deuteranopia, Vision.Protanopia)
  private val MaximumSheetBytes = 64 * 1024 * 1024

  def write(directory: Path, binary: Path): Int =
    val renderer = CardRenderer(ResvgRasteriser(ResvgBinary.config(binary)))
    val sheets = ResvgRasteriser(ResvgBinary.config(binary).copy(timeoutMillis = 300000, maximumPngBytes = MaximumSheetBytes))
    val sheetCount = ContactSheet.Variants.map { variant =>
      val entries = ContactSheet.entries(Samples.review, variants = Vector(variant))
      require(entries.nonEmpty, s"the ${variant._1} sheet has no card")
      val png = sheets.rasterise(SvgEmitter.emit(ContactSheet.sheet(entries)), 1).fold(f => throw IllegalStateException(s"the ${variant._1} sheet: $f"), identity)
      Files.write(directory.resolve(s"contact-${variant._1}.png"), png.toArray)
      val drawn = ImageIO.read(ByteArrayInputStream(png.toArray))
      Seen.foreach(vision => ImageIO.write(VisionSheet.seen(vision, drawn), "png", directory.resolve(s"contact-${variant._1}-${name(vision)}.png").toFile))
      1 + Seen.size
    }.sum
    val visionDirectory = Files.createDirectories(directory.resolve("vision"))
    val visionCount = Samples.review.map { fixture =>
      def draw(theme: Theme) =
        fixture.render(Samples.context(theme, Privacy.Named)).map(card => renderer.render(card).fold(f => throw IllegalStateException(s"${fixture.name}: $f"), _.png))
      val pages = draw(Theme.Light).zip(draw(Theme.Dark))
      require(pages.nonEmpty, s"${fixture.name} has no page")
      pages.zipWithIndex.foreach { case ((light, dark), page) =>
        val file = s"${fixture.name.replace('/', '-')}${if page == 0 then "" else s"-p${page + 1}"}.png"
        ImageIO.write(VisionSheet.compose(light, dark), "png", visionDirectory.resolve(file).toFile)
      }
      pages.size
    }.sum
    val findings = greyscaleFindings
    Files.writeString(directory.resolve("greyscale-contrast.txt"), findings.report)
    if findings.failures.nonEmpty then throw IllegalStateException(s"${findings.failures.size} marks keep under 3:1 in greyscale; see greyscale-contrast.txt")
    sheetCount + visionCount

  final case class Findings(cards: Int, failures: Vector[String]):
    def report: String =
      if failures.isEmpty then s"Every mark of $cards cards keeps ${GreyscaleContrast.Minimum}:1 against its backdrop in greyscale.\n"
      else failures.mkString(s"${failures.size} marks of $cards cards keep under ${GreyscaleContrast.Minimum}:1 in greyscale:\n", "\n", "\n")

  /** The check of `GreyscaleContrastSuite` over the same cards: English and Ukrainian, light, dark and discreet. */
  def greyscaleFindings: Findings =
    val variants = ContactSheet.Variants :+ ("light-discreet", Theme.Light, Privacy.Discreet)
    val entries = ContactSheet.entries(Samples.review, variants = variants) ++
      ContactSheet.entries(Samples.review, UkrainianImageCopy, variants)
    Findings(entries.size, entries.flatMap(entry => GreyscaleContrast.findings(entry.card.scene).map(f => s"${entry.name}: $f")))

  private def name(vision: Vision): String = vision.toString.toLowerCase

/** Writes the image review into the directory given as the first argument:
  * `./mill report-renderer.resvg.test.imageReview` or `scripts/image-review.sh`. Needs the pinned resvg.
  */
@main def renderImageReview(outputDir: String): Unit =
  val binary = ResvgBinary.locate.getOrElse(throw IllegalStateException("resvg is not on the PATH and DOSEGRAM_RESVG is not set"))
  val directory = Files.createDirectories(Path.of(outputDir))
  val written = ImageReview.write(directory, binary)
  println(s"image review: wrote $written images and greyscale-contrast.txt to $directory")
