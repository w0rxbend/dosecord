package dosegram.render.resvg

import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The long-lived rasteriser (ADR-022 addendum of 2026-11-15): `config.maximumConcurrentRenders` worker processes of
  * `dosegram-raster` (report-renderer/worker), each started on first use with the pinned fonts loaded once, then fed
  * one request at a time over standard input. A worker draws exactly what one resvg process per image draws; it only
  * compresses the PNG faster. The limits are the per-image adapter's: one deadline for the wait and the render, the PNG
  * size cap, and the worker's own caps on request size and canvas pixels. A worker that fails in any way, a timeout
  * included, is killed and replaced on the next request, so a crash or a hang never reaches the JVM. Standard error is
  * discarded, never logged.
  */
final class ResvgWorkerPool(config: ResvgConfig) extends Rasteriser, AutoCloseable:
  private val idle = LinkedBlockingQueue[Slot]()
  private val slots = Vector.fill(config.maximumConcurrentRenders)(Slot())
  slots.foreach(idle.add)
  private val isClosed = AtomicBoolean(false)

  def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis)
    Option(idle.poll(config.timeoutMillis, TimeUnit.MILLISECONDS)) match
      case None       => Left(RasterFailure.QueueFull)
      case Some(slot) =>
        try
          if isClosed.get then Left(RasterFailure.Unavailable)
          else slot.render(svg, zoom, StrictMath.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
        finally idle.add(slot)

  /** The process ids of the workers now running, for tests. */
  private[resvg] def runningWorkers: Vector[Long] = slots.flatMap(_.processId)

  /** Ends every worker. A worker also ends by itself when the JVM exits, because its input closes. */
  def close(): Unit =
    isClosed.set(true)
    slots.foreach(_.discard())

  /** One worker's place in the pool, used by one caller at a time: the queue hands it out. */
  private final class Slot:
    @volatile private var current: Option[Worker] = None

    def render(svg: String, zoom: Int, timeoutMillis: Long): Either[RasterFailure, PngImage] =
      worker.flatMap { running =>
        val hasExpired = AtomicBoolean(false)
        val alarm = Deadlines.alarms.schedule(
          (() => { hasExpired.set(true); running.kill() }): Runnable,
          timeoutMillis,
          TimeUnit.MILLISECONDS
        )
        val result =
          try running.ask(svg, zoom)
          finally alarm.cancel(false)
        if result.isLeft then discard()
        result.left.map(failure => if hasExpired.get then RasterFailure.TimedOut else failure)
      }

    def processId: Option[Long] = current.map(_.processId)

    def discard(): Unit =
      current.foreach(_.kill())
      current = None

    private def worker: Either[RasterFailure, Worker] =
      current match
        case Some(running) => Right(running)
        case None          =>
          val started = Worker.start(config)
          current = started.toOption
          started

private object Deadlines:
  /** One daemon thread for the deadlines of every pool: it only kills processes. */
  val alarms: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(task =>
    Thread.ofPlatform().daemon().name("dosegram-raster-deadline").unstarted(task)
  )

private final class Worker(process: Process, maximumPngBytes: Int):
  private val input = DataOutputStream(BufferedOutputStream(process.getOutputStream))
  private val output = DataInputStream(BufferedInputStream(process.getInputStream))

  def processId: Long = process.pid()

  /** One request and its answer. A worker that never reads, never answers or floods is stopped by the caller's
    * deadline, which kills the process and so ends the blocked read or write here.
    */
  def ask(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
    try
      val bytes = svg.getBytes(StandardCharsets.UTF_8)
      input.writeInt(zoom)
      input.writeInt(bytes.length)
      input.write(bytes)
      input.flush()
      answer(status = output.readUnsignedByte(), length = output.readInt())
    catch case _: IOException => Left(RasterFailure.Crashed(exitCode))

  private def answer(status: Int, length: Int): Either[RasterFailure, PngImage] =
    if status != Worker.StatusPng then Left(RasterFailure.Crashed(status))
    else if length < 0 || length > maximumPngBytes then Left(RasterFailure.TooLarge)
    else
      val png = output.readNBytes(length)
      if png.length < length then Left(RasterFailure.Crashed(exitCode))
      else if !png.startsWith(Worker.PngSignature) then Left(RasterFailure.NotPng)
      else Right(PngImage(IArray.unsafeFromArray(png)))

  // The pipe ended, so the process has exited or is exiting; the caller's deadline bounds the wait.
  private def exitCode: Int = process.waitFor()

  // Descendants first: a child that outlives its parent would keep the pipes open and the reader blocked.
  def kill(): Unit =
    process.descendants().forEach(descendant => descendant.destroyForcibly())
    process.destroyForcibly()
    ()

private object Worker:
  private val StatusPng = 0
  private val PngSignature: Array[Byte] = Array(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  def start(config: ResvgConfig): Either[RasterFailure, Worker] =
    val command = List(
      config.binary.toString,
      "--fonts-dir",
      config.fontsDir.toString,
      "--resources-dir",
      config.resourcesDir.toString
    )
    try
      Right(
        Worker(ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.DISCARD).start(), config.maximumPngBytes)
      )
    catch case _: IOException => Left(RasterFailure.Unavailable)
