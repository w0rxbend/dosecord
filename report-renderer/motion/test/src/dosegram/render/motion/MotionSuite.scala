package dosegram.render.motion

import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import dosegram.render.RenderGoldens
import dosegram.render.Samples
import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.MoodMotionCard
import dosegram.render.card.Privacy
import dosegram.render.resvg.ResvgBinary
import dosegram.render.resvg.ResvgWorkerPool
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.Theme
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.ByteBufferSeekableByteChannel

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

/** The video form of the animated mood blob (VISUALS section 4.6): PNG frames read without AWT, an MP4 that a decoder
  * plays back as the frames it was given, the drawing budget, and the size and time of a real loop drawn by the pinned
  * rasteriser. The tests that need `dosegram-raster` are skipped on a machine without it, and fail instead where
  * `DOSEGRAM_REQUIRE_RESVG` is set.
  */
class MotionSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private def png(width: Int, height: Int, kind: Int)(pixel: (Int, Int) => Int): Array[Byte] =
    val image = BufferedImage(width, height, kind)
    for y <- 0 until height; x <- 0 until width do image.setRGB(x, y, pixel(x, y))
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    out.toByteArray

  private def gradient(x: Int, y: Int): Int = 0xff000000 | ((x * 7) & 0xff) << 16 | ((y * 5) & 0xff) << 8 | ((x + y) & 0xff)

  private def frame(width: Int, height: Int, shift: Int): RgbFrame =
    val rgb = Array.ofDim[Byte](width * height * 3)
    for y <- 0 until height; x <- 0 until width do
      val inside = (x - width / 2 - shift) * (x - width / 2 - shift) + (y - height / 2) * (y - height / 2) < 400
      val base = (y * width + x) * 3
      rgb(base) = (if inside then 230 else 20).toByte
      rgb(base + 1) = (if inside then 180 else 40).toByte
      rgb(base + 2) = (60 + y).toByte
    RgbFrame(width, height, rgb)

  test("a PNG of the rasteriser's kind is read to the same pixels as AWT reads it, with RGB and RGBA rows"):
    for kind <- Vector(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB) do
      val decoded = PngPixels.decode(png(37, 23, kind)(gradient)).fold(fail(_), identity)
      assertEquals((decoded.width, decoded.height), (37, 23))
      for y <- 0 until 23; x <- 0 until 37 do
        val expected = gradient(x, y)
        val base = (y * 37 + x) * 3
        val actual = (decoded.rgb(base) & 0xff) << 16 | (decoded.rgb(base + 1) & 0xff) << 8 | (decoded.rgb(base + 2) & 0xff)
        assertEquals(actual, expected & 0xffffff, s"pixel $x,$y")

  test("anything that is not such a PNG is refused, not guessed"):
    val good = png(8, 8, BufferedImage.TYPE_INT_ARGB)(gradient)
    assert(PngPixels.decode(Array.emptyByteArray).isLeft)
    assert(PngPixels.decode("<svg/>".getBytes).isLeft)
    val broken = good.clone()
    broken(40) = (broken(40) ^ 0xff).toByte
    assert(PngPixels.decode(broken).isLeft, "a flipped byte fails its chunk checksum")
    assert(PngPixels.decode(good.take(good.length - 20)).isLeft, "a truncated file")
    val grey = png(8, 8, BufferedImage.TYPE_BYTE_GRAY)(gradient)
    assertEquals(PngPixels.decode(grey), Left("unsupported pixel format"))

  test("frames become an MP4 of H.264 video with one sample per frame and the loop's duration"):
    val frames = (0 until 60).toVector.map(index => frame(64, 48, index % 20))
    val bytes = Mp4Encoder.encode(frames, 20)
    val top = Mp4Boxes.parse(bytes)
    assertEquals(top.map(_.kind).filter(Set("ftyp", "moov", "mdat")), Vector("ftyp", "mdat", "moov"))
    assertEquals(Mp4Boxes.codec(top), "avc1")
    assertEquals(Mp4Boxes.samples(top), 60)
    assertEqualsDouble(Mp4Boxes.seconds(top), 3.0, 0.001)
    val avcC = top.find(_.kind == "moov").flatMap(_.child("trak", "mdia", "minf", "stbl", "stsd"))
    assert(avcC.exists(box => String(box.payload.duplicate().array(), "ISO-8859-1").contains("avcC")), "decoder setup present")

  test("the video plays back as the frames it was given (decoded by an independent reader of the file)"):
    val frames = (0 until 10).toVector.map(index => frame(64, 48, index))
    val bytes = Mp4Encoder.encode(frames, 20)
    val grab = FrameGrab.createFrameGrab(ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes)))
    frames.zipWithIndex.foreach: (expected, index) =>
      val picture = grab.getNativeFrame
      assert(picture != null, s"frame $index decodes")
      val luma = picture.getPlaneData(0)
      val errors = (0 until 64 * 48).map: pixel =>
        val r = expected.rgb(pixel * 3) & 0xff
        val g = expected.rgb(pixel * 3 + 1) & 0xff
        val b = expected.rgb(pixel * 3 + 2) & 0xff
        val y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16
        val difference = (luma(pixel) + 128) - y
        difference * difference
      val psnr = 10 * math.log10(255.0 * 255.0 / (errors.sum.toDouble / errors.size).max(1e-9))
      assert(psnr > 30, s"frame $index: luma PSNR $psnr dB")

  test("the same frames give the same video stream; only the file's clock may differ"):
    val frames = (0 until 12).toVector.map(index => frame(32, 32, index))
    def mdat(bytes: Array[Byte]) = Mp4Boxes.parse(bytes).find(_.kind == "mdat").map(box => ByteBuffer.wrap(bytes).slice(box.payload.position(), box.payload.remaining)).get
    assertEquals(mdat(Mp4Encoder.encode(frames, 20)), mdat(Mp4Encoder.encode(frames, 20)))

  test("a side that is not a multiple of 16, or frames of two sizes, are refused before encoding"):
    intercept[IllegalArgumentException](Mp4Encoder.encode(Vector(frame(30, 32, 0)), 20))
    intercept[IllegalArgumentException](Mp4Encoder.encode(Vector(frame(32, 32, 0), frame(48, 32, 0)), 20))
    intercept[IllegalArgumentException](Mp4Encoder.encode(Vector.empty, 20))

  private val tinyPng = png(32, 32, BufferedImage.TYPE_INT_ARGB)(gradient)
  private val tinySvg = """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><rect width="32" height="32" fill="#0b292c"/></svg>"""

  private final class Counting(answer: Either[RasterFailure, PngImage], tick: () => Unit = () => ()) extends Rasteriser:
    val calls = AtomicLong()
    def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      calls.incrementAndGet()
      tick()
      answer

  test("every frame goes through the rasteriser port at photo zoom and the result is a video"):
    val rasteriser = Counting(Right(PngImage(IArray.unsafeFromArray(tinyPng))))
    val video = MotionRenderer(rasteriser, 1000).render(Vector.fill(6)(tinySvg), 20).fold(failure => fail(failure.toString), identity)
    assertEquals(rasteriser.calls.get, 6L)
    assertEquals(Mp4Boxes.samples(Mp4Boxes.parse(video.toArray)), 6)

  test("a frame the rasteriser cannot draw ends the drawing with its failure; garbage bytes are NotPng"):
    assertEquals(MotionRenderer(Counting(Left(RasterFailure.QueueFull)), 1000).render(Vector(tinySvg), 20), Left(RasterFailure.QueueFull))
    val garbage = Counting(Right(PngImage(IArray.unsafeFromArray("nope".getBytes))))
    assertEquals(MotionRenderer(garbage, 1000).render(Vector(tinySvg, tinySvg), 20), Left(RasterFailure.NotPng))
    assertEquals(garbage.calls.get, 1L)
    val outside = MotionRenderer(Counting(Left(RasterFailure.Unavailable)), 1000).render(Vector("<svg><script/></svg>"), 20)
    assert(outside.left.exists(_.isInstanceOf[RasterFailure.OutsideProfile]), outside.toString)

  test("the drawing stops at its budget: no frame starts after it, and the answer is TimedOut"):
    val clock = AtomicLong(0)
    val rasteriser = Counting(Right(PngImage(IArray.unsafeFromArray(tinyPng))), () => { clock.addAndGet(400_000_000L); () })
    val result = MotionRenderer(rasteriser, 1000, () => clock.get).render(Vector.fill(10)(tinySvg), 20)
    assertEquals(result, Left(RasterFailure.TimedOut))
    assertEquals(rasteriser.calls.get, 3L)

  // The real loop through the pinned worker: the size, the time and the frame goldens.

  private def needed(condition: Boolean, reason: String): Unit =
    if ResvgBinary.isRequired then assert(condition, s"$reason, and DOSEGRAM_REQUIRE_RESVG is set")
    else assume(condition, reason)

  private lazy val pool: ResvgWorkerPool =
    needed(ResvgBinary.locateWorker.isDefined, "dosegram-raster is not installed")
    ResvgWorkerPool(ResvgBinary.config(ResvgBinary.locateWorker.get))

  private def frames(state: MoodState, theme: Theme) =
    MoodMotionCard.render(MoodReading(state, isEnergyGiven = true), Samples.context(theme, Privacy.Aggregate)).frames.map(SvgEmitter.emit)

  test("a loop of the liveliest state drawn by the pinned worker is a small MP4 of 60 frames and 3 seconds"):
    val svgs = frames(MoodState(7, 5), Theme.Dark)
    MotionRenderer(pool, 60000).render(svgs.take(2), 20) // warm the worker and the encoder
    val started = System.nanoTime()
    val video = MotionRenderer(pool, 60000).render(svgs, 20).fold(failure => fail(failure.toString), identity)
    val millis = (System.nanoTime() - started) / 1_000_000
    // The time is a measurement for PERFORMANCE.md section 4.7, not a check: a loaded build host is slower.
    println(s"motion: blob.v7.e5 dark, ${svgs.size} frames, ${video.byteCount} bytes, $millis ms")
    assert(video.byteCount < 512 * 1024, s"${video.byteCount} bytes")
    val top = Mp4Boxes.parse(video.toArray)
    assertEquals(Mp4Boxes.samples(top), 60)
    assertEqualsDouble(Mp4Boxes.seconds(top), 3.0, 0.001)

  test("pixel goldens of the frames: the decoded pixels of the golden frames, for the pinned worker and fonts"):
    val expected = MotionPixelGoldens.load
    val actual = MotionPixelGoldens.hashes(pool)
    assertEquals(MotionPixelGoldens.format(actual), expected, "regenerate with motionPixelGoldenGenerate and review")

/** The SHA-256 of the decoded pixels of the golden frames of [[RenderGoldens.MotionStates]], drawn by the pinned
  * worker. Regenerate with `./mill report-renderer.motion.test.runMain dosegram.render.motion.motionPixelGoldenGenerate`.
  */
object MotionPixelGoldens:
  val ResourceFile = "report-renderer/motion/test/resources/goldens/motion-pixel-hashes.txt"

  def hashes(rasteriser: Rasteriser): Vector[(String, String)] =
    for
      state <- RenderGoldens.MotionStates
      all = MoodMotionCard.render(MoodReading(state, isEnergyGiven = true), Samples.context(Theme.Dark, Privacy.Aggregate)).frames
      index <- RenderGoldens.MotionFrames
    yield
      val png = rasteriser.rasterise(SvgEmitter.emit(all(index)), 1).fold(failure => throw IllegalStateException(failure.toString), identity)
      val pixels = PngPixels.decode(png.toArray).fold(reason => throw IllegalStateException(reason), identity)
      f"${state.id}.f$index%02d" -> MessageDigest.getInstance("SHA-256").digest(pixels.rgb).map(byte => f"$byte%02x").mkString

  def format(hashes: Vector[(String, String)]): String = hashes.map((name, hash) => s"$name $hash\n").mkString

  def load: String =
    val stream = Option(getClass.getResourceAsStream("/goldens/motion-pixel-hashes.txt"))
      .getOrElse(throw IllegalStateException("/goldens/motion-pixel-hashes.txt is not on the test classpath"))
    scala.util.Using.resource(scala.io.Source.fromInputStream(stream, "UTF-8"))(_.mkString)

@main def motionPixelGoldenGenerate(): Unit =
  val worker = ResvgBinary.locateWorker.getOrElse(throw IllegalStateException("dosegram-raster is not installed"))
  val pool = ResvgWorkerPool(ResvgBinary.config(worker))
  try
    val target = java.nio.file.Path.of(MotionPixelGoldens.ResourceFile)
    java.nio.file.Files.createDirectories(target.getParent)
    java.nio.file.Files.writeString(target, MotionPixelGoldens.format(MotionPixelGoldens.hashes(pool)))
    println(s"wrote motion pixel hashes to ${MotionPixelGoldens.ResourceFile}")
  finally pool.close()
