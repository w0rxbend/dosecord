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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The long-lived rasteriser (ADR-022 addendum of 2026-11-15): `config.maximumConcurrentRenders` worker processes of
  * `dosegram-raster` (report-renderer/worker), each started on first use with the pinned fonts loaded once, then fed
  * one request at a time over standard input. A worker draws exactly what one resvg process per image draws; it only
  * compresses the PNG faster. The limits are the per-image adapter's: one deadline for the wait, the worker's start and
  * the render, the PNG size cap, and the worker's own caps on request size and canvas pixels. A worker starts on a
  * thread of its own, so a slow start ends the render at its deadline and the started worker serves the next one. A
  * worker that fails in any way, a timeout included, is killed and replaced on the next request, and so is one that
  * ended between requests, so a crash or a hang never reaches the JVM. Standard error is discarded, never logged.
  * `launch` starts the process; tests slow it down.
  */
final class ResvgWorkerPool private[resvg] (config: ResvgConfig, launch: ProcessBuilder => Process)
    extends Rasteriser,
      AutoCloseable:
  def this(config: ResvgConfig) = this(config, _.start())

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
          else slot.render(svg, zoom, deadline)
        finally idle.add(slot)

  /** The process ids of the workers now running, for tests. */
  private[resvg] def runningWorkers: Vector[Long] = slots.flatMap(_.processId)

  /** Ends every worker, one still starting included. A worker also ends by itself when the JVM exits, because its input
    * closes.
    */
  def close(): Unit =
    isClosed.set(true)
    slots.foreach(_.discard())

  /** One worker's place in the pool, used by one caller at a time: the queue hands it out. */
  private final class Slot:
    @volatile private var current: Option[CompletableFuture[Either[RasterFailure, Worker]]] = None

    def render(svg: String, zoom: Int, deadline: Long): Either[RasterFailure, PngImage] =
      worker(deadline).flatMap(running => ask(running, svg, zoom, deadline))

    def processId: Option[Long] = started.map(_.processId)

    def discard(): Unit =
      current.foreach(_.thenAccept(_.foreach(_.kill())))
      current = None

    private def started: Option[Worker] =
      current.filter(_.isDone).flatMap(_.getNow(Left(RasterFailure.Unavailable)).toOption)

    /** The slot's worker, waited for until the deadline; a start that is late keeps going for the next request. */
    private def worker(deadline: Long): Either[RasterFailure, Worker] =
      val starting = current.filter(isUsable).getOrElse(start())
      try
        val started = starting.get(StrictMath.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        if started.isLeft then current = None
        started
      catch
        case _: TimeoutException        => Left(RasterFailure.TimedOut)
        case failed: ExecutionException => throw failed.getCause

    /** A start still running, or a worker that has not ended since its last answer. */
    private def isUsable(starting: CompletableFuture[Either[RasterFailure, Worker]]): Boolean =
      !starting.isDone || started.exists(_.isAlive)

    private def start(): CompletableFuture[Either[RasterFailure, Worker]] =
      discard()
      val starting = CompletableFuture.supplyAsync(() => Worker.start(config, launch), Deadlines.starts)
      current = Some(starting)
      starting

    /** One request against the rest of the deadline. A worker whose deadline fired is discarded even when its answer
      * came in time, because the alarm may have killed it after the answer.
      */
    private def ask(running: Worker, svg: String, zoom: Int, deadline: Long): Either[RasterFailure, PngImage] =
      val remaining = deadline - System.nanoTime()
      if remaining <= 0 then Left(RasterFailure.TimedOut)
      else
        val hasExpired = AtomicBoolean(false)
        val alarm = Deadlines.alarms.schedule(
          (() => { hasExpired.set(true); running.kill() }): Runnable,
          remaining,
          TimeUnit.NANOSECONDS
        )
        val result =
          try running.ask(svg, zoom)
          finally if !alarm.cancel(false) then hasExpired.set(true)
        if result.isLeft || hasExpired.get then discard()
        result.left.map(failure => if hasExpired.get then RasterFailure.TimedOut else failure)

private object Deadlines:
  /** One daemon thread for the deadlines of every pool: it only kills processes. */
  val alarms: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(task =>
    Thread.ofPlatform().daemon().name("dosegram-raster-deadline").unstarted(task)
  )

  /** Daemon threads that start worker processes, so a caller waits for a start no longer than its deadline. */
  val starts: ExecutorService =
    Executors.newCachedThreadPool(task => Thread.ofPlatform().daemon().name("dosegram-raster-start").unstarted(task))

private final class Worker(process: Process, maximumPngBytes: Int):
  private val input = DataOutputStream(BufferedOutputStream(process.getOutputStream))
  private val output = DataInputStream(BufferedInputStream(process.getInputStream))

  def processId: Long = process.pid()

  def isAlive: Boolean = process.isAlive

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

  def start(config: ResvgConfig, launch: ProcessBuilder => Process): Either[RasterFailure, Worker] =
    val command = List(
      config.binary.toString,
      "--fonts-dir",
      config.fontsDir.toString,
      "--resources-dir",
      config.resourcesDir.toString
    )
    try
      Right(
        Worker(launch(ProcessBuilder(command*).redirectError(ProcessBuilder.Redirect.DISCARD)), config.maximumPngBytes)
      )
    catch case _: IOException => Left(RasterFailure.Unavailable)
