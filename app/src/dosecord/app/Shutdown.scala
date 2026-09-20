package dosecord.app

import ox.Ox
import ox.supervised

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** Tracks in-flight units of work (a vendor send, a tick row) so shutdown can drain them. */
final class InFlightRegistry:
  private var active = 0

  def begin(): Unit = synchronized { active += 1 }

  def end(): Unit = synchronized {
    active -= 1
    if active == 0 then notifyAll()
  }

  /** Waits until every in-flight unit finished or the budget is spent; `true` when drained. */
  def awaitQuiesce(budget: FiniteDuration): Boolean =
    val deadline = System.nanoTime() + budget.toNanos
    synchronized {
      while active > 0 && System.nanoTime() < deadline do
        val remainingMs = (deadline - System.nanoTime()) / 1000000
        if remainingMs > 0 then wait(remainingMs)
      active == 0
    }

/** Orderly SIGTERM shutdown (DESIGN.md section 12): stop accepting inbound, stop claiming new work, finish in-flight
  * within the drain budget.
  */
final class Drain(
    inbound: List[(String, () => Unit)],
    claiming: List[(String, () => Unit)],
    inFlight: InFlightRegistry,
    budget: FiniteDuration,
    log: String => Unit
):
  /** `true` when every in-flight unit finished within the budget. */
  def execute(): Boolean =
    inbound.foreach((name, stop) => { log(s"shutdown: stopping inbound ($name)"); stop() })
    claiming.foreach((name, stop) => { log(s"shutdown: stopping claiming ($name)"); stop() })
    val drained = inFlight.awaitQuiesce(budget)
    if drained then log("shutdown: in-flight work drained")
    else log(s"shutdown: in-flight work did not finish within $budget")
    drained

object Shutdown:

  /** In-flight drain budget on SIGTERM (DESIGN.md section 12). */
  val DrainBudget: FiniteDuration = 20.seconds

  /** Runs `body` inside the single Ox `supervised` root scope of the process and parks until SIGTERM. On the signal the
    * drain runs (stop inbound, stop claiming, finish in-flight within [[DrainBudget]]), the scope then cancels the
    * remaining forks, and the process halts 0 on a clean drain or 1 when the drain or the scope overruns
    * `hardDeadline`.
    */
  def serve(body: Ox ?=> Drain, log: String => Unit, hardDeadline: FiniteDuration = 25.seconds): Int =
    val stop = CountDownLatch(1)
    val done = CountDownLatch(1)
    @volatile var exitCode = 1
    val hook = new Thread(() => {
      log("SIGTERM received; draining")
      stop.countDown()
      val finished = done.await(hardDeadline.toMillis, TimeUnit.MILLISECONDS)
      Runtime.getRuntime.halt(if finished then exitCode else 1)
    })
    Runtime.getRuntime.addShutdownHook(hook)
    supervised {
      val drain = body
      stop.await()
      exitCode = if drain.execute() then 0 else 1
    }
    done.countDown()
    exitCode
