package dosegram.app

import ox.discard

import java.io.IOException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import scala.collection.mutable

/** The stability review of 2026-11-14: a periodic worker survives a failed pass. The failures are injected into the
  * pass; the waits are recorded instead of slept.
  */
class WorkerLoopSuite extends munit.FunSuite:

  private val backoff = WorkerLoop.Backoff(Duration.ofSeconds(1), Duration.ofSeconds(5))

  private final class Rig:
    val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val waits: mutable.ListBuffer[Duration] = mutable.ListBuffer.empty
    val loop: WorkerLoop = WorkerLoop(log += _, backoff, waits += _)

  /** A pass that plays `script` in order: a throwable is thrown, `true` asks for another pass, `false` ends the loop.
    */
  private def scripted(script: Any*): () => Boolean =
    val steps = mutable.Queue(script*)
    () =>
      steps.dequeue() match
        case error: Throwable => throw error
        case again: Boolean   => again
        case other            => fail(s"not a step: $other")

  /** munit's `intercept` passes an interrupt and a fatal error through, so these are caught here. */
  private def thrownBy(body: => Unit): Throwable =
    try
      body
      fail("nothing was thrown")
    catch case thrown: Throwable => thrown

  test("a database failure is logged by its kind, waited out, and the loop goes on"):
    val rig = Rig()
    rig.loop.repeat("inbox purge")(scripted(SQLException("relation \"secret\" does not exist", "08006"), true, false))
    assertEquals(rig.log.toList, List("worker inbox purge failed: SQLException sqlstate=08006; next pass in 1 s"))
    assertEquals(rig.waits.toList, List(Duration.ofSeconds(1)))

  test("the wait doubles while passes keep failing, stops at its ceiling, and starts again after a good pass"):
    val rig = Rig()
    val blip = () => SQLTransientConnectionException("connection is not available")
    rig.loop.repeat("image purge")(
      scripted(blip(), blip(), blip(), blip(), blip(), true, IOException("broken pipe"), false)
    )
    assertEquals(rig.waits.toList.map(_.toSeconds), List(1L, 2L, 4L, 5L, 5L, 1L))
    assert(rig.log.forall(line => !line.contains("connection is not available")), "the message is never logged")
    assertEquals(rig.log.last, "worker image purge failed: IOException; next pass in 1 s")

  test("a pass that asks for no more ends the loop without a wait"):
    val rig = Rig()
    rig.loop.repeat("dispatcher")(scripted(true, true, false))
    assertEquals(rig.waits.toList, Nil)
    assertEquals(rig.log.toList, Nil)

  test("an interrupt ends the loop: the scope is being cancelled"):
    val rig = Rig()
    assert(thrownBy(rig.loop.repeat("rollup worker")(scripted(InterruptedException()))).isInstanceOf[InterruptedException])
    val wrapped = SQLException("interrupted", "08006", InterruptedException())
    val thrown = intercept[SQLException](rig.loop.repeat("rollup worker")(scripted(wrapped)))
    assertEquals(thrown, wrapped, "an interrupt inside a database error is still an interrupt")
    assertEquals(rig.waits.toList, Nil)

  test("an interrupted thread does not retry a failure"):
    val rig = Rig()
    Thread.currentThread().interrupt()
    try intercept[SQLException](rig.loop.repeat("prompt loop")(scripted(SQLException("closed"))))
    finally Thread.interrupted().discard
    assertEquals(rig.waits.toList, Nil)

  test("a fatal error is not retried"):
    val rig = Rig()
    assert(thrownBy(rig.loop.repeat("materialiser")(scripted(StackOverflowError()))).isInstanceOf[StackOverflowError])
    assertEquals(rig.waits.toList, Nil)

  test("the backoff refuses a ceiling below its first wait"):
    intercept[IllegalArgumentException](WorkerLoop.Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1)))

end WorkerLoopSuite
