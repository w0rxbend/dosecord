package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.PngImage
import dosegram.render.Samples
import dosegram.render.Vision
import dosegram.render.card.Privacy
import dosegram.render.tokens.Theme

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** The review sheet of one fixture: its light and its dark rendering in rows, each seen normally, in greyscale, with
  * deuteranopia and with protanopia, at the 360 px display width of the contact sheet (VA-ID-05).
  */
object VisionSheet:
  private val TileWidth = 360

  def compose(light: PngImage, dark: PngImage): BufferedImage =
    val rows = Vector(light, dark).map(png => ImageIO.read(ByteArrayInputStream(png.toArray)))
    val tileHeight = rows.map(image => image.getHeight * TileWidth / image.getWidth).max
    val sheet = BufferedImage(TileWidth * Vision.values.length, tileHeight * rows.size, BufferedImage.TYPE_INT_RGB)
    val graphics = sheet.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    for
      (image, row) <- rows.zipWithIndex
      (vision, column) <- Vision.values.zipWithIndex
    do
      val height = image.getHeight * TileWidth / image.getWidth
      graphics.drawImage(seen(vision, image), column * TileWidth, row * tileHeight, TileWidth, height, null)
    graphics.dispose()
    sheet

  private def seen(vision: Vision, image: BufferedImage): BufferedImage =
    val out = BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
    for y <- 0 until image.getHeight; x <- 0 until image.getWidth do out.setRGB(x, y, vision.see(image.getRGB(x, y) & 0xffffff))
    out

/** Writes one vision sheet per fixture page into the directory given as the first argument:
  * `./mill report-renderer.resvg.test.runMain dosegram.render.resvg.renderVisionSheets <dir> [prefix]`.
  */
@main def renderVisionSheets(outputDir: String, prefix: String): Unit =
  val binary = ResvgBinary.locate.getOrElse(throw IllegalStateException("resvg is not on the PATH and DOSEGRAM_RESVG is not set"))
  val directory = Files.createDirectories(Path.of(outputDir))
  val renderer = CardRenderer(ResvgRasteriser(ResvgBinary.config(binary)))
  def draw(fixture: dosegram.render.Fixture, theme: Theme) =
    fixture.render(Samples.context(theme, Privacy.Named)).map(card => renderer.render(card).fold(failure => throw IllegalStateException(s"${fixture.name}: $failure"), _.png))
  val fixtures = Samples.review.filter(_.name.startsWith(prefix))
  fixtures.foreach { fixture =>
    draw(fixture, Theme.Light).zip(draw(fixture, Theme.Dark)).zipWithIndex.foreach { case ((light, dark), page) =>
      val name = s"${fixture.name.replace('/', '-')}${if page == 0 then "" else s"-p${page + 1}"}.png"
      ImageIO.write(VisionSheet.compose(light, dark), "png", directory.resolve(name).toFile)
    }
  }
  println(s"wrote vision sheets of ${fixtures.size} fixtures to $directory")
