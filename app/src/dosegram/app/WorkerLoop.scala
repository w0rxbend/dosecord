package dosegram.app

import java.sql.SQLException
import java.time.Duration
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Keeps a periodic worker of the process alive across a failed pass (stability review of 2026-11-14): a database blip
  * must not end the root scope and with it the process. A pass that throws a non-fatal error is logged by the worker's
  * name and the error's kind, never its message, which can carry data; the loop then waits and runs the next pass. The
  * wait doubles while passes keep failing, up to the ceiling, and starts again from the first wait after a good pass.
  *
  * An interrupt is never retried: it is the scope being cancelled, whether it arrives as an `InterruptedException`, as
  * the cause of another error, or as the interrupt status of the thread. A fatal error (`NonFatal` says which) is not
  * retried either. What must fail the start stays outside the loop: a worker runs its first, required step before it
  * enters [[repeat]].
  */
final class WorkerLoop(log: String => Unit, backoff: WorkerLoop.Backoff, sleep: Duration => Unit):

  /** Runs `pass` until it returns false. */
  def repeat(worker: String)(pass: () => Boolean): Unit = loop(worker, pass, backoff.first)

  @tailrec
  private def loop(worker: String, pass: () => Boolean, wait: Duration): Unit =
    val outcome =
      try Right(pass())
      catch case NonFatal(error) if !WorkerLoop.isInterrupt(error) => Left(error)
    outcome match
      case Right(true)  => loop(worker, pass, backoff.first)
      case Right(false) => ()
      case Left(error)  =>
        log(s"worker $worker failed: ${WorkerLoop.kindOf(error)}; next pass in ${wait.toSeconds} s")
        sleep(wait)
        loop(worker, pass, backoff.after(wait))

object WorkerLoop:

  /** The wait after the first failed pass, and the most it grows to. */
  final case class Backoff(first: Duration, ceiling: Duration):
    require(!first.isNegative && !first.isZero, "the first wait is positive")
    require(ceiling.compareTo(first) >= 0, "the ceiling is at least the first wait")

    def after(wait: Duration): Duration =
      val doubled = wait.multipliedBy(2)
      if doubled.compareTo(ceiling) > 0 then ceiling else doubled

  /** One second rides out a pool that is briefly empty; a minute bounds the delay after a longer outage, and stays well
    * below the 15-minute materialiser period and the 30-minute session idle limit.
    */
  val DefaultBackoff: Backoff = Backoff(Duration.ofSeconds(1), Duration.ofMinutes(1))

  def standard(log: String => Unit): WorkerLoop = WorkerLoop(log, DefaultBackoff, wait => Thread.sleep(wait.toMillis))

  /** How deep a chain of causes is searched for an interrupt; real chains are two or three deep. */
  private val CauseDepth = 16

  private def isInterrupt(error: Throwable): Boolean =
    Thread.currentThread().isInterrupted ||
      Iterator
        .iterate(error)(_.getCause)
        .take(CauseDepth)
        .takeWhile(_ != null)
        .exists(_.isInstanceOf[InterruptedException])

  /** The class of the error, and for a database error its SQLSTATE, which names the kind of failure and no data. */
  private def kindOf(error: Throwable): String =
    error match
      case sql: SQLException if sql.getSQLState != null =>
        s"${error.getClass.getSimpleName} sqlstate=${sql.getSQLState}"
      case _ => error.getClass.getSimpleName
