package dosecord.app

import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

/** ROADMAP M0.5 acceptance: a SIGTERM test completes a fake in-flight fork and exits 0 within 20 s. Runs the real
  * shutdown machinery ([[Shutdown.serve]]) in a child JVM and sends a real SIGTERM (`Process.destroy` on Linux).
  */
class ShutdownSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  test("Drain stops inbound, then claiming, then waits for in-flight"):
    val order    = ArrayBuffer.empty[String]
    val inFlight = InFlightRegistry()
    inFlight.begin()
    val worker = new Thread(() => { Thread.sleep(300); inFlight.end(); order += "work done"; () })
    worker.start()
    val drain = Drain(
      inbound = List("health" -> (() => order += "inbound")),
      claiming = List("loop" -> (() => order += "claiming")),
      inFlight = inFlight,
      budget = 5.seconds,
      log = _ => ()
    )
    assert(drain.execute(), "drain should succeed")
    worker.join(5000)
    assertEquals(order.toList, List("inbound", "claiming", "work done"))

  test("Drain gives up after the budget when in-flight never finishes"):
    val inFlight = InFlightRegistry()
    inFlight.begin()
    val started = System.nanoTime()
    val drained = Drain(Nil, Nil, inFlight, 300.millis, _ => ()).execute()
    val elapsed = (System.nanoTime() - started) / 1_000_000
    assert(!drained, "drain should report failure")
    assert(elapsed >= 250 && elapsed < 5000, s"budget not honoured (${elapsed} ms)")

  test("SIGTERM completes a fake in-flight fork and exits 0 within 20 s"):
    val javaBin = s"${System.getProperty("java.home")}/bin/java"
    val outFile = Files.createTempFile("dosecord-shutdown-smoke", ".log").toFile
    val pb = ProcessBuilder(
      javaBin,
      "-cp",
      System.getProperty("java.class.path"),
      "dosecord.app.shutdownSmokeMain"
    )
    pb.redirectErrorStream(true)
    pb.redirectOutput(outFile)
    val process = pb.start()

    def output() = Files.readString(outFile.toPath)

    val readyDeadline = System.nanoTime() + 30_000_000_000L
    while !output().contains("READY") && process.isAlive && System.nanoTime() < readyDeadline do Thread.sleep(50)
    assert(output().contains("READY"), s"child never became ready; output:\n${output()}")

    val sigtermAt = System.nanoTime()
    process.destroy() // SIGTERM on Linux
    val exited  = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
    val elapsed = (System.nanoTime() - sigtermAt) / 1_000_000_000.0

    assert(exited, s"child did not exit within 20 s of SIGTERM; output:\n${output()}")
    assertEquals(process.exitValue(), 0, s"output:\n${output()}")
    assert(elapsed >= 3.0, s"exit after ${elapsed}s means the 4 s in-flight fork was not awaited")
    assert(output().contains("in-flight work completed"), s"in-flight fork did not complete; output:\n${output()}")
    assert(output().contains("in-flight work drained"), s"drain did not finish; output:\n${output()}")
    println(f"SIGTERM-to-exit: $elapsed%.1f s (limit 20 s)")
