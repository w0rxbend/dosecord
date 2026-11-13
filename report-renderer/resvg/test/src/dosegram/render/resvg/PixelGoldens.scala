package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.PngImage
import dosegram.render.Samples

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

import scala.io.Source
import scala.util.Using

/** Pixel goldens (research brief 04 section 12, layer 4): the SHA-256 of the decoded pixels of every fixture, so a
  * change of PNG compression alone does not fail the test. They are valid for the pinned resvg version and font pack
  * only; a change of either regenerates them in the same change with
  * `./mill report-renderer.resvg.test.runMain dosegram.render.resvg.pixelGoldenGenerate`.
  */
object PixelGoldens:
  val ResourceFile = "report-renderer/resvg/test/resources/goldens/pixel-hashes.txt"

  def hash(png: PngImage): String =
    val image = ImageIO.read(ByteArrayInputStream(png.toArray))
    val pixels = image.getRGB(0, 0, image.getWidth, image.getHeight, null, 0, image.getWidth)
    val digest = MessageDigest.getInstance("SHA-256")
    pixels.foreach(pixel => digest.update(Array((pixel >> 24).toByte, (pixel >> 16).toByte, (pixel >> 8).toByte, pixel.toByte)))
    digest.digest().map(byte => f"$byte%02x").mkString

  def format(hashes: Vector[(String, String)]): String = hashes.map((name, hash) => s"$name $hash\n").mkString

  def load: String =
    val stream = Option(getClass.getResourceAsStream("/goldens/pixel-hashes.txt"))
      .getOrElse(throw IllegalStateException("/goldens/pixel-hashes.txt is not on the test classpath"))
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)

@main def pixelGoldenGenerate(): Unit =
  val binary = ResvgBinary.locate.getOrElse(throw IllegalStateException("resvg is not on the PATH and DOSEGRAM_RESVG is not set"))
  val renderer = CardRenderer(ResvgRasteriser(ResvgBinary.config(binary)))
  val hashes = ContactSheet.entries(Samples.all).map { entry =>
    val image = renderer.render(entry.card).fold(failure => throw IllegalStateException(s"${entry.name}: $failure"), identity)
    entry.name -> PixelGoldens.hash(image.png)
  }
  val target = Path.of(PixelGoldens.ResourceFile)
  Files.createDirectories(target.getParent)
  Files.writeString(target, PixelGoldens.format(hashes))
  println(s"wrote ${hashes.size} pixel hashes to ${PixelGoldens.ResourceFile}")
