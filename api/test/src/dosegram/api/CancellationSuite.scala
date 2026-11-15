package dosegram.api

import java.net.SocketException
import java.sql.SQLException

/** The cycle 10 verification: a request its client abandons fails in the blocking call it was in, and that failure is
  * the client's leaving, answered `client_closed` and not logged as an internal error.
  */
final class CancellationSuite extends munit.FunSuite:

  /** Runs `body` on a thread of its own that is interrupted, or not, first. */
  private def onThread[A](interrupted: Boolean)(body: => A): A =
    var result = Option.empty[A]
    val thread = Thread: () =>
      if interrupted then Thread.currentThread().interrupt()
      result = Some(body)
    thread.start()
    thread.join()
    result.get

  private val brokenByInterrupt =
    SQLException("An I/O error occurred while sending to the backend.", "08006", SocketException("Closed by interrupt"))

  test("a failure on an interrupted thread is a cancellation, whatever its class"):
    assert(onThread(interrupted = true)(Cancellation.isCancellation(brokenByInterrupt)))
    assert(onThread(interrupted = true)(Cancellation.isCancellation(SQLException("Connection is closed"))))

  test("an interruption among the causes or the suppressed exceptions is a cancellation"):
    assert(onThread(interrupted = false)(Cancellation.isCancellation(SQLException("x", InterruptedException()))))
    val rollback = SQLException("Connection is closed")
    rollback.addSuppressed(java.io.InterruptedIOException())
    assert(onThread(interrupted = false)(Cancellation.isCancellation(rollback)))

  test("the same failure on a thread nobody interrupted is a fault of the server"):
    assert(!onThread(interrupted = false)(Cancellation.isCancellation(brokenByInterrupt)))
    assert(!onThread(interrupted = false)(Cancellation.isCancellation(IllegalStateException("bug"))))

  test("a cycle of causes ends the walk"):
    val first = SQLException("first")
    val second = SQLException("second", first)
    first.initCause(second)
    assert(!onThread(interrupted = false)(Cancellation.isCancellation(first)))

  test("an abandoned request answers 499 client_closed"):
    val failure = ApiFailure.clientClosed()
    assertEquals(failure.status.code, 499)
    assertEquals(failure.problem.code.wire, "client_closed")
