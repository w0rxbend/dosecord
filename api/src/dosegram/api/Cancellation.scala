package dosegram.api

import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import scala.annotation.tailrec

/** A request whose client went away: the server interrupts the thread that serves it, and the blocking call it was in,
  * a query on a pooled connection for one, fails. That failure is the client's leaving, not a fault of the server.
  */
object Cancellation:

  /** Whether `cause`, thrown on the current thread, comes from the cancellation of the current request: the thread is
    * interrupted, or an interruption is among its causes or the exceptions suppressed on the way.
    */
  def isCancellation(cause: Throwable): Boolean =
    Thread.currentThread().isInterrupted || interruptedWithin(List(cause), Set.empty)

  private def isInterruption(cause: Throwable): Boolean = cause match
    case _: InterruptedException | _: InterruptedIOException | _: ClosedByInterruptException => true
    case _                                                                                   => false

  // Each cause and suppressed exception once; a cycle of causes ends the walk.
  @tailrec
  private def interruptedWithin(pending: List[Throwable], seen: Set[Throwable]): Boolean = pending match
    case Nil                        => false
    case next :: rest if seen(next) => interruptedWithin(rest, seen)
    case next :: rest               =>
      if isInterruption(next) then true
      else interruptedWithin(Option(next.getCause).toList ++ next.getSuppressed ++ rest, seen + next)
