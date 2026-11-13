package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Samples
import dosegram.render.card.Privacy
import dosegram.render.color.Rgb
import dosegram.render.svg.SvgEmitter
import dosegram.render.text.FontPack
import dosegram.render.text.PinnedFonts
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.Using

/** The child-process adapter against the real resvg binary and against fake binaries that hang, crash or flood. The
  * tests that need resvg are skipped on a machine without it, and fail instead where `ResvgBinary.isRequired`; the
  * failure-path tests need only a shell.
  */
class ResvgRasteriserSuite extends munit.FunSuite:
  private val PinnedVersion = ResvgBinary.PinnedVersion

  // The fixture run starts 96 child processes; on a loaded host that exceeds the default 30 seconds.
  override val munitTimeout: Duration = 5.minutes
  private val binary = ResvgBinary.locate

  private def needed(condition: Boolean, reason: String): Unit =
    if ResvgBinary.isRequired then assert(condition, s"$reason, and DOSEGRAM_REQUIRE_RESVG is set")
    else assume(condition, reason)

  private def rasteriser: ResvgRasteriser =
    needed(binary.isDefined, "resvg is not installed")
    ResvgRasteriser(ResvgBinary.config(binary.get))

  private def fakeBinary(script: String): Path =
    val file = Files.createTempFile("fake-resvg", ".sh")
    Files.writeString(file, s"#!/bin/sh\n$script\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    file

  private def withFake(script: String, timeoutMillis: Long, maximumPngBytes: Int): ResvgRasteriser =
    ResvgRasteriser(ResvgConfig(fakeBinary(script), PinnedFonts.dir, PinnedFonts.dir, timeoutMillis, maximumPngBytes, 2))

  private def waterSvg(theme: Theme): String =
    SvgEmitter.emit(Samples.waters(1).render(Samples.context(theme, Privacy.Named)).head.scene)

  private def render(svg: String): PngImage =
    rasteriser.rasterise(svg, 1).fold(failure => fail(s"render failed: $failure"), identity)

  private def chunkTypes(png: PngImage): Vector[String] =
    val bytes = png.toArray
    Iterator
      .iterate(8)(offset => offset + 12 + java.nio.ByteBuffer.wrap(bytes, offset, 4).getInt)
      .takeWhile(_ < bytes.length)
      .map(offset => String(bytes, offset + 4, 4, StandardCharsets.US_ASCII))
      .toVector

  test("a card renders to an opaque PNG of the canvas size with the token colours"):
    val image = ImageIO.read(ByteArrayInputStream(render(waterSvg(Theme.Light)).toArray))
    assertEquals((image.getWidth, image.getHeight), (1080, 1080))
    def colourAt(x: Int, y: Int): Rgb =
      val pixel = image.getRGB(x, y)
      Rgb((pixel >> 16) & 0xff, (pixel >> 8) & 0xff, pixel & 0xff)
    assertEquals(colourAt(8, 8), Tokens.Light.surface)
    assertEquals(colourAt(1071, 1071), Tokens.Light.surface)
    assertEquals(colourAt(244, 700), Tokens.Light.tide, "the vessel fill at 1.4 of 2.0 l covers this point")
    assertEquals(colourAt(244, 260), Tokens.Light.panel, "the vessel is empty glass above the level")
    val pixels = image.getRGB(0, 0, image.getWidth, image.getHeight, null, 0, image.getWidth)
    assert(pixels.forall(pixel => (pixel >>> 24) == 0xff), "a pixel is not fully opaque")

  test("the same SVG renders to the same pixels every time"):
    val svg = waterSvg(Theme.Dark)
    assertEquals(PixelGoldens.hash(render(svg)), PixelGoldens.hash(render(svg)))

  test("the PNG carries no text chunk and no timestamp (VA-PV-08)"):
    val chunks = chunkTypes(render(waterSvg(Theme.Dark)))
    assertEquals(chunks.head, "IHDR")
    assertEquals(chunks.last, "IEND")
    assertEquals(chunks.filter(Set("tEXt", "zTXt", "iTXt", "tIME", "eXIf")), Vector.empty)

  test("zoom 2 doubles the pixel size for the document path"):
    val image = rasteriser.rasterise(waterSvg(Theme.Light), 2).map(png => ImageIO.read(ByteArrayInputStream(png.toArray)))
    assertEquals(image.map(i => (i.getWidth, i.getHeight)), Right((2160, 2160)))

  test("every fixture renders within the size limit, and its pixels match the pinned hashes"):
    val renderer = CardRenderer(rasteriser)
    val rendered = ContactSheet.entries(Samples.all).map { entry =>
      val image = renderer.render(entry.card).fold(failure => fail(s"${entry.name} failed: $failure"), identity)
      assert(image.png.byteCount < 2 * 1024 * 1024, entry.name)
      entry.name -> PixelGoldens.hash(image.png)
    }
    assert(rendered.size >= 90, "the fixture set shrank")
    needed(resvgVersion.contains(PinnedVersion), s"pixel goldens are pinned to resvg $PinnedVersion")
    assertEquals(PixelGoldens.format(rendered), PixelGoldens.load, "pixels drifted; regenerate with pixelGoldenGenerate and review")

  test("the table measurer agrees with the rasteriser's own text extents within two percent"):
    val samples = Vector(
      "Recorded entries only. Not medical advice." -> TextStyle.Label,
      "Medication by day" -> TextStyle.Title,
      "26 of 28 taken" -> TextStyle.Value,
      "Метформін гідрохлорид" -> TextStyle.BodyMedium,
      "Metformin hydrochloride extended" -> TextStyle.BodyMedium.condensed,
      "100%" -> TextStyle.Hero
    )
    samples.foreach { (text, style) =>
      val measured = Samples.Fitter.width(text, style)
      val drawn = resvgTextWidth(text, style)
      assert(StrictMath.abs(measured - drawn) / drawn < 0.02, s"'$text': table $measured, resvg $drawn")
    }

  test("a rasteriser that hangs is killed at the timeout and reported as a typed failure"):
    val started = System.nanoTime()
    val result = withFake("sleep 30", 400, 1024).rasterise(waterSvg(Theme.Dark), 1)
    val elapsedMillis = (System.nanoTime() - started) / 1000000
    assertEquals(result.left.toOption, Some(RasterFailure.TimedOut))
    assert(elapsedMillis < 5000, s"the caller waited $elapsedMillis ms")

  test("a render that waits for a busy slot ends within its own deadline, waiting included (VISUALS VA-FB-01)"):
    val busy = ResvgRasteriser(ResvgConfig(fakeBinary("sleep 30"), PinnedFonts.dir, PinnedFonts.dir, 600, 1024, 1))
    val first = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    val holding = first.submit(() => busy.rasterise(waterSvg(Theme.Dark), 1))
    Thread.sleep(200)
    val started = System.nanoTime()
    val second = busy.rasterise(waterSvg(Theme.Dark), 1)
    val elapsedMillis = (System.nanoTime() - started) / 1000000
    assert(second.isLeft, s"$second")
    assert(elapsedMillis < 900, s"the second caller waited $elapsedMillis ms against a 600 ms deadline")
    assertEquals(holding.get().left.toOption, Some(RasterFailure.TimedOut))
    first.shutdown()

  test("a rasteriser that never reads its input cannot block the caller"):
    val large = "<svg>" + "x" * (4 * 1024 * 1024) + "</svg>"
    assertEquals(withFake("sleep 30", 400, 1024).rasterise(large, 1).left.toOption, Some(RasterFailure.TimedOut))

  test("a crash is reported with its exit code and the JVM stays up"):
    assertEquals(withFake("cat > /dev/null; exit 3", 2000, 1024).rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Crashed(3)))

  test("output beyond the size limit is refused"):
    val flood = withFake("cat > /dev/null; head -c 100000 /dev/zero", 2000, 1024)
    assertEquals(flood.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.TooLarge))

  test("output that is not a PNG is refused"):
    assertEquals(withFake("cat > /dev/null; echo hello", 2000, 1024).rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.NotPng))

  test("a missing binary is reported as unavailable"):
    val missing = ResvgRasteriser(ResvgBinary.config(Path.of("/nonexistent/resvg")))
    assertEquals(missing.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Unavailable))

  private def resvgVersion: Option[String] =
    binary.map { path =>
      val process = ProcessBuilder(path.toString, "--version").start()
      Using.resource(Source.fromInputStream(process.getInputStream))(_.mkString.trim)
    }

  // The width resvg reports for one text element, through its `--query-all` pass.
  private def resvgTextWidth(text: String, style: TextStyle): Double =
    val svg = s"""<svg xmlns="http://www.w3.org/2000/svg" width="2000" height="400"><text id="t" x="10" y="200" """ +
      s"""font-family="${FontPack.family(style.role)}" font-weight="${style.weight}" """ +
      s"""font-size="${style.sizePx}">${SvgEmitter.escape(text)}</text></svg>"""
    val file = Files.createTempFile("measure", ".svg")
    Files.writeString(file, svg)
    val command = List(binary.get.toString, "--skip-system-fonts", "--use-fonts-dir", PinnedFonts.dir.toString, "--query-all", file.toString)
    val process = ProcessBuilder(command*).start()
    val output = Using.resource(Source.fromInputStream(process.getInputStream))(_.mkString.trim)
    Files.delete(file)
    output.split(',')(3).toDouble
