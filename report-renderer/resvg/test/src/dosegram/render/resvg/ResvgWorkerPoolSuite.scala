package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Samples
import dosegram.render.card.Privacy
import dosegram.render.svg.SvgEmitter
import dosegram.render.text.PinnedFonts
import dosegram.render.tokens.Theme

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

/** The long-lived worker adapter (ADR-022 addendum of 2026-11-15) against the real `dosegram-raster` and against fake
  * workers that hang, crash, flood or answer garbage. The tests that need the worker are skipped on a machine without
  * it, and fail instead where `ResvgBinary.isRequired`; the failure-path tests need only a shell.
  */
class ResvgWorkerPoolSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes
  private val worker = ResvgBinary.locateWorker

  private def needed(condition: Boolean, reason: String): Unit =
    if ResvgBinary.isRequired then assert(condition, s"$reason, and DOSEGRAM_REQUIRE_RESVG is set")
    else assume(condition, reason)

  private def pool: ResvgWorkerPool =
    needed(worker.isDefined, "dosegram-raster is not installed")
    ResvgWorkerPool(ResvgBinary.config(worker.get))

  private def fakeWorker(script: String): Path =
    val file = Files.createTempFile("fake-raster", ".sh")
    Files.writeString(file, s"#!/bin/sh\n$script\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    file

  private def withFake(script: String, timeoutMillis: Long, maximumPngBytes: Int, workers: Int = 2): ResvgWorkerPool =
    ResvgWorkerPool(ResvgConfig(fakeWorker(script), PinnedFonts.dir, PinnedFonts.dir, timeoutMillis, maximumPngBytes, workers))

  /** A shell `printf` of a well-formed answer: status 0, eight bytes, the PNG signature. */
  private val PngAnswer = """printf '\000\000\000\000\010\211PNG\r\n\032\n'"""

  private def waterSvg(theme: Theme): String =
    SvgEmitter.emit(Samples.waters(1).render(Samples.context(theme, Privacy.Named)).head.scene)

  private def render(on: ResvgWorkerPool, svg: String, zoom: Int = 1): PngImage =
    on.rasterise(svg, zoom).fold(failure => fail(s"render failed: $failure"), identity)

  private def size(png: PngImage): (Int, Int) =
    val image = ImageIO.read(ByteArrayInputStream(png.toArray))
    (image.getWidth, image.getHeight)

  private def chunkTypes(png: PngImage): Vector[String] =
    val bytes = png.toArray
    Iterator
      .iterate(8)(offset => offset + 12 + java.nio.ByteBuffer.wrap(bytes, offset, 4).getInt)
      .takeWhile(_ < bytes.length)
      .map(offset => String(bytes, offset + 4, 4, StandardCharsets.US_ASCII))
      .toVector

  private def elapsedMillis[A](work: => A): (A, Long) =
    val started = System.nanoTime()
    val result = work
    (result, (System.nanoTime() - started) / 1000000)

  test("the worker reports the resvg release it is built from"):
    needed(worker.isDefined, "dosegram-raster is not installed")
    val process = ProcessBuilder(worker.get.toString, "--version").start()
    assertEquals(Using.resource(Source.fromInputStream(process.getInputStream))(_.mkString.trim), ResvgBinary.PinnedVersion)

  test("every fixture drawn by the worker matches the pinned pixel hashes of resvg"):
    Using.resource(pool) { workers =>
      val renderer = CardRenderer(workers)
      val rendered = ContactSheet.entries(Samples.all).map { entry =>
        val image = renderer.render(entry.card).fold(failure => fail(s"${entry.name} failed: $failure"), identity)
        assert(image.png.byteCount < 2 * 1024 * 1024, entry.name)
        entry.name -> PixelGoldens.hash(image.png)
      }
      assert(rendered.size >= 90, "the fixture set shrank")
      assertEquals(PixelGoldens.format(rendered), PixelGoldens.load, "the worker's pixels differ from resvg's")
    }

  test("the worker draws the pixels of one resvg process per image, at zoom 1 and zoom 2"):
    val binary = ResvgBinary.locate
    needed(binary.isDefined, "resvg is not installed")
    val perImage = ResvgRasteriser(ResvgBinary.config(binary.get))
    Using.resource(pool) { workers =>
      for zoom <- List(1, 2) do
        val svg = waterSvg(Theme.Light)
        val fromProcess = perImage.rasterise(svg, zoom).fold(failure => fail(s"resvg failed: $failure"), identity)
        val fromWorker = render(workers, svg, zoom)
        assertEquals(size(fromWorker), (1080 * zoom, 1080 * zoom))
        assertEquals(PixelGoldens.hash(fromWorker), PixelGoldens.hash(fromProcess), s"zoom $zoom")
    }

  test("the PNG carries no text chunk and no timestamp (VA-PV-08)"):
    Using.resource(pool) { workers =>
      val chunks = chunkTypes(render(workers, waterSvg(Theme.Dark)))
      assertEquals(chunks.head, "IHDR")
      assertEquals(chunks.last, "IEND")
      assertEquals(chunks.filter(Set("tEXt", "zTXt", "iTXt", "tIME", "eXIf")), Vector.empty)
    }

  test("one worker draws many images, and the pool never holds more workers than its slots"):
    Using.resource(pool) { workers =>
      val svg = waterSvg(Theme.Dark)
      render(workers, svg)
      val first = workers.runningWorkers
      (1 to 5).foreach(_ => render(workers, svg))
      assertEquals(first.size, 1)
      assert(workers.runningWorkers.size <= 2, s"${workers.runningWorkers}")
      assert(workers.runningWorkers.contains(first.head), "the first worker was replaced without a failure")
    }

  test("a document the worker cannot read is a typed failure, and the next document still draws"):
    Using.resource(pool) { workers =>
      assertEquals(workers.rasterise("not an svg", 1).left.toOption, Some(RasterFailure.Crashed(3)))
      assertEquals(size(render(workers, waterSvg(Theme.Light))), (1080, 1080))
    }

  test("the worker caps the canvas and the zoom, so one request cannot take unbounded memory"):
    Using.resource(pool) { workers =>
      val huge = """<svg xmlns="http://www.w3.org/2000/svg" width="5000" height="5000"/>"""
      assertEquals(workers.rasterise(huge, 1).left.toOption, Some(RasterFailure.Crashed(4)))
      assertEquals(workers.rasterise(waterSvg(Theme.Light), 5).left.toOption, Some(RasterFailure.Crashed(2)))
      assertEquals(size(render(workers, waterSvg(Theme.Light))), (1080, 1080))
    }

  test("a worker that hangs is killed at the timeout and reported as a typed failure"):
    val (result, elapsed) = elapsedMillis(withFake("exec sleep 30", 400, 1024).rasterise(waterSvg(Theme.Dark), 1))
    assertEquals(result.left.toOption, Some(RasterFailure.TimedOut))
    assert(elapsed < 5000, s"the caller waited $elapsed ms")

  test("a hanging worker started through a child process is killed with its child"):
    val (result, elapsed) = elapsedMillis(withFake("sleep 30", 400, 1024).rasterise(waterSvg(Theme.Dark), 1))
    assertEquals(result.left.toOption, Some(RasterFailure.TimedOut))
    assert(elapsed < 5000, s"the caller waited $elapsed ms")

  test("a render that waits for a busy worker ends within its own deadline, waiting included (VISUALS VA-FB-01)"):
    val busy = withFake("exec sleep 30", 600, 1024, workers = 1)
    val first = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    val holding = first.submit(() => busy.rasterise(waterSvg(Theme.Dark), 1))
    Thread.sleep(200)
    val (second, elapsed) = elapsedMillis(busy.rasterise(waterSvg(Theme.Dark), 1))
    assert(second.isLeft, s"$second")
    assert(elapsed < 900, s"the second caller waited $elapsed ms against a 600 ms deadline")
    assertEquals(holding.get().left.toOption, Some(RasterFailure.TimedOut))
    first.shutdown()

  test("a slow worker start counts against the deadline, and the worker it started draws the next image"):
    val slowStart: ProcessBuilder => Process = builder =>
      Thread.sleep(1500)
      builder.start()
    val config = ResvgConfig(fakeWorker(s"head -c 14 > /dev/null; $PngAnswer; exec sleep 30"), PinnedFonts.dir, PinnedFonts.dir, 400, 1024, 1)
    val slow = ResvgWorkerPool(config, slowStart)
    val (first, elapsed) = elapsedMillis(slow.rasterise("<svg/>", 1))
    assertEquals(first.left.toOption, Some(RasterFailure.TimedOut))
    assert(elapsed < 1200, s"the caller waited $elapsed ms against a 400 ms deadline")
    val started = System.nanoTime()
    while slow.runningWorkers.isEmpty && System.nanoTime() - started < 30_000_000_000L do Thread.onSpinWait()
    val (next, nextElapsed) = elapsedMillis(slow.rasterise("<svg/>", 1))
    assert(next.isRight, s"$next")
    assert(nextElapsed < 1200, s"the next image waited $nextElapsed ms: the started worker was not kept")
    slow.close()

  test("a worker that ended after its answer is replaced before the next image, which still draws"):
    val once = withFake(s"head -c 14 > /dev/null; $PngAnswer; exit 0", 2000, 1024, workers = 1)
    assert(once.rasterise("<svg/>", 1).isRight)
    once.runningWorkers.foreach(pid => ProcessHandle.of(pid).ifPresent(handle => handle.onExit().get()))
    assert(once.rasterise("<svg/>", 1).isRight, "the next image found the ended worker")
    once.close()

  test("a worker that never reads its input cannot block the caller"):
    val large = "<svg>" + "x" * (4 * 1024 * 1024) + "</svg>"
    assertEquals(withFake("exec sleep 30", 400, 1024).rasterise(large, 1).left.toOption, Some(RasterFailure.TimedOut))

  test("a crash is reported with its exit code, the JVM stays up and the next request starts a new worker"):
    val crashing = withFake("head -c 8 > /dev/null; exit 3", 2000, 1024)
    assertEquals(crashing.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Crashed(3)))
    assertEquals(crashing.runningWorkers, Vector.empty)
    assertEquals(crashing.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Crashed(3)))

  test("a worker's own refusal is reported with its status"):
    val refusing = withFake("""head -c 14 > /dev/null; printf '\003\000\000\000\000'; exec sleep 30""", 2000, 1024)
    assertEquals(refusing.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Crashed(3)))

  test("an answer beyond the size limit is refused before it is read, and the worker is stopped"):
    val (result, elapsed) =
      elapsedMillis(withFake("""head -c 14 > /dev/null; printf '\000\177\377\377\377'; exec sleep 30""", 2000, 1024).rasterise("<svg/>", 1))
    assertEquals(result.left.toOption, Some(RasterFailure.TooLarge))
    assert(elapsed < 1500, s"the caller waited $elapsed ms")

  test("an answer that is not a PNG is refused"):
    val garbage = withFake("""head -c 14 > /dev/null; printf '\000\000\000\000\005hello'; exec sleep 30""", 2000, 1024)
    assertEquals(garbage.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.NotPng))

  test("a missing binary is reported as unavailable"):
    val missing = ResvgWorkerPool(ResvgBinary.config(Path.of("/nonexistent/dosegram-raster")))
    assertEquals(missing.rasterise("<svg/>", 1).left.toOption, Some(RasterFailure.Unavailable))

  test("a closed pool ends its workers and draws nothing more"):
    val workers = pool
    render(workers, waterSvg(Theme.Light))
    val running = workers.runningWorkers
    workers.close()
    running.foreach(pid => ProcessHandle.of(pid).ifPresent(handle => handle.onExit().get()))
    assertEquals(workers.rasterise(waterSvg(Theme.Light), 1).left.toOption, Some(RasterFailure.Unavailable))
