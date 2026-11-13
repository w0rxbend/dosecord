package dosegram.render.resvg

import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import ox.fork
import ox.supervised

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** How the resvg child process is run. `resourcesDir` should be an empty directory: the SVG profile has no external
  * reference, and this keeps it that way even for a document that slipped past the lint.
  */
final case class ResvgConfig(
    binary: Path,
    fontsDir: Path,
    resourcesDir: Path,
    timeoutMillis: Long,
    maximumPngBytes: Int,
    maximumConcurrentRenders: Int
)

object ResvgConfig:
  /** The limits of research brief 04 sections 7.5 and 13: 5 s per render, 2 MB per image, two child processes. */
  def standard(binary: Path, fontsDir: Path, resourcesDir: Path): ResvgConfig =
    ResvgConfig(binary, fontsDir, resourcesDir, 5000, 2 * 1024 * 1024, 2)

/** The resvg adapter (research brief 04 section 7.5): one short-lived child process per image, SVG on standard input,
  * PNG on standard output, pinned fonts only, no temporary file. A hang is killed at the timeout and a crash cannot
  * take the JVM down. Standard error is discarded, never logged, because a parser message could quote user text.
  */
final class ResvgRasteriser(config: ResvgConfig) extends Rasteriser:
  private val permits = Semaphore(config.maximumConcurrentRenders)

  def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
    permits.acquire()
    try start(zoom).flatMap(process => collect(process, svg))
    finally permits.release()

  private def start(zoom: Int): Either[RasterFailure, Process] =
    val command = List(
      config.binary.toString,
      "--skip-system-fonts",
      "--use-fonts-dir",
      config.fontsDir.toString,
      "--resources-dir",
      config.resourcesDir.toString,
      "-z",
      zoom.toString,
      "-",
      "-c"
    )
    try Right(ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.DISCARD).start())
    catch case _: IOException => Left(RasterFailure.Unavailable)

  // Both pipes are served by forks, so neither a process that never reads nor one that never stops writing can block
  // this thread; the wait below is the only deadline. Killing the process tree closes its pipes and ends the forks.
  private def collect(process: Process, svg: String): Either[RasterFailure, PngImage] =
    try
      supervised {
        val output = fork(readCapped(process))
        val input = fork(writeAll(process, svg))
        val hasExited = process.waitFor(config.timeoutMillis, TimeUnit.MILLISECONDS)
        if !hasExited then kill(process)
        input.join()
        classify(hasExited, process, output.join())
      }
    finally kill(process)

  // Descendants first: a child that outlives its parent would keep the pipes open and the reader blocked.
  private def kill(process: Process): Unit =
    process.descendants().forEach(descendant => descendant.destroyForcibly())
    process.destroyForcibly()

  private def classify(hasExited: Boolean, process: Process, png: Array[Byte]): Either[RasterFailure, PngImage] =
    if png.length > config.maximumPngBytes then Left(RasterFailure.TooLarge)
    else if !hasExited then Left(RasterFailure.TimedOut)
    else if process.exitValue != 0 then Left(RasterFailure.Crashed(process.exitValue))
    else if !png.startsWith(ResvgRasteriser.PngSignature) then Left(RasterFailure.NotPng)
    else Right(PngImage(IArray.unsafeFromArray(png)))

  // Reads one byte past the limit to tell "too large" from "exactly at the limit", then stops the writer.
  private def readCapped(process: Process): Array[Byte] =
    val png =
      try process.getInputStream.readNBytes(config.maximumPngBytes + 1)
      catch case _: IOException => Array.emptyByteArray
    if png.length > config.maximumPngBytes then kill(process)
    png

  // A process that exits or is killed before it has read everything breaks the pipe; the exit code or the timeout
  // already reports that failure, so the write error itself carries no further information.
  private def writeAll(process: Process, svg: String): Unit =
    val stdin = process.getOutputStream
    try
      stdin.write(svg.getBytes(StandardCharsets.UTF_8))
      stdin.close()
    catch case _: IOException => ()

object ResvgRasteriser:
  private val PngSignature: Array[Byte] = Array(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
