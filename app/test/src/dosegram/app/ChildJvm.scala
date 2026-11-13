package dosegram.app

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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

/** A TCP port that was free a moment ago, for a listener the test starts next. */
def freePort(): Int =
  val socket = ServerSocket(0)
  try socket.getLocalPort
  finally socket.close()
