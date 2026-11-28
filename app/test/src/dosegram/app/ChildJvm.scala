package dosegram.app

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** A main class of the test classpath in a child JVM, so a test can send it a real SIGTERM and read its exit code. The
  * child gets exactly `env`, nothing from the parent process, and its stdout and stderr go to one file.
  */
final class ChildJvm private (process: Process, log: java.nio.file.Path):

  def output: String = Files.readString(log)

  /** Polls the output until it contains `marker`; `false` when the child exits or `within` elapses first. */
  def awaitOutput(marker: String, within: FiniteDuration): Boolean =
    val deadline = System.nanoTime() + within.toNanos
    while !output.contains(marker) && process.isAlive && System.nanoTime() < deadline do Thread.sleep(50)
    output.contains(marker)

  /** `Process.destroy` is SIGTERM on Linux. */
  def sigterm(): Unit = process.destroy()

  /** The exit code, or `None` when the child is still running after `within` (it is then killed). */
  def exitCode(within: FiniteDuration): Option[Int] =
    if process.waitFor(within.toMillis, TimeUnit.MILLISECONDS) then Some(process.exitValue())
    else
      process.destroyForcibly()
      None

object ChildJvm:

  def start(mainClass: String, args: List[String], env: Map[String, String]): ChildJvm =
    val log = Files.createTempFile("dosegram-child-jvm", ".log")
    log.toFile.deleteOnExit()
    val command =
      List(s"${System.getProperty("java.home")}/bin/java", "-cp", System.getProperty("java.class.path"), mainClass)
    val builder = ProcessBuilder((command ++ args).asJava)
    builder.environment().clear()
    builder.environment().putAll(env.asJava)
    builder.redirectErrorStream(true)
    builder.redirectOutput(log.toFile)
    new ChildJvm(builder.start(), log)

/** A TCP port for a listener the test starts next. A bind to port 0 hands out a port of the kernel's ephemeral range,
  * where every outgoing connection of this JVM (JDBC, Testcontainers, HTTP clients) also takes its local port, so one of
  * them could take it before the listener binds it. This port comes from just below that range instead, which the
  * kernel never assigns on its own; a counter keeps the calls of this JVM apart, a port that does not bind is skipped,
  * and a port is taken for the rest of this JVM's life by a lock file, so another test JVM running at once (Mill runs
  * a module's suites in several) never gets it too (verification of 2026-11-28, third round).
  */
def freePort(): Int = FreePorts.next()

private[app] object FreePorts:

  /** Ports per JVM before the counter starts again at the bottom of the range. */
  private val Span = 10_000

  private val top: Int =
    val range = Path.of("/proc/sys/net/ipv4/ip_local_port_range")
    // A file of /proc reports no size, so it is read by line: readString would stop early.
    if Files.exists(range) then Files.readAllLines(range).get(0).trim.split("\\s+").head.toInt else 32768

  private val bottom = (top - Span).max(1024)

  // Two test JVMs that run at once start apart.
  private val counter = AtomicInteger((ProcessHandle.current().pid() % (top - bottom)).toInt)

  /** One lock file per port, shared by every test JVM of this user on the machine. */
  private val lockDir: Path =
    Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "dosegram-test-ports"))

  /** The locks this JVM holds; the operating system releases them when it exits, however it exits. */
  private val held = ConcurrentLinkedQueue[FileChannel]()

  @annotation.tailrec
  def next(): Int =
    val port = bottom + Math.floorMod(counter.getAndIncrement(), top - bottom)
    if take(port) then port else next()

  /** Takes `port` for this JVM: `false` when another test JVM holds it, this JVM took it before, or it does not bind. */
  def take(port: Int): Boolean = lock(port) && bindable(port)

  private def lock(port: Int): Boolean =
    val channel = FileChannel.open(lockDir.resolve(s"$port.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val taken =
      try Option(channel.tryLock()).isDefined
      catch case _: OverlappingFileLockException => false
    if taken then held.add(channel): Unit
    else channel.close()
    taken

  private def bindable(port: Int): Boolean =
    try
      val socket = ServerSocket()
      try
        socket.bind(InetSocketAddress("127.0.0.1", port))
        true
      finally socket.close()
    catch case _: java.io.IOException => false
