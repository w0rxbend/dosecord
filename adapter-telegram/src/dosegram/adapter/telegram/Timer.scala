package dosegram.adapter.telegram

import dosegram.core.ports.Clock
import ox.Ox
import ox.discard
import ox.fork

import java.time.Duration
import java.time.Instant
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import scala.collection.mutable

/** The adapter's one concurrency seam: run a task later, off the caller's thread. The acknowledgement watchdog, the
  * removal of a short-lived message and the long-poll loop all go through it, so a test steps them with [[ManualTimer]]
  * on a fake clock.
  */
trait Timer:
  def after(delay: Duration)(task: () => Unit): Unit

/** The production timer: one fork of the adapter's scope takes due tasks from a delay queue and runs each in a fork of
  * its own, so a slow Bot API call never holds up another deadline. `after` only enqueues and may be called from any
  * thread, including one outside the scope. A task that throws ends the scope (supervision is the recovery model).
  */
final class OxTimer(using Ox) extends Timer:

  private final class Entry(val dueNanos: Long, val task: () => Unit) extends Delayed:
    override def getDelay(unit: TimeUnit): Long = unit.convert(dueNanos - System.nanoTime(), TimeUnit.NANOSECONDS)
    override def compareTo(other: Delayed): Int =
      java.lang.Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS))

  private val queue = DelayQueue[Entry]()

  fork:
    while true do
      val due = queue.take()
      fork(due.task()).discard
  .discard

  override def after(delay: Duration)(task: () => Unit): Unit =
    queue.put(Entry(System.nanoTime() + delay.toNanos, task))

/** The test timer: tasks run on the caller's thread when [[advance]] moves the clock past their due time. */
final class ManualTimer(start: Instant) extends Timer, Clock:

  private val lock = new Object
  private var current = start
  private val pending = mutable.ListBuffer.empty[(Instant, () => Unit)]

  override def now(): Instant = lock.synchronized(current)

  override def after(delay: Duration)(task: () => Unit): Unit = lock.synchronized:
    pending += ((current.plus(delay), task))
    ()

  /** Moves the clock and runs every task that is due by then, earliest first; a task may schedule another. */
  def advance(by: Duration): Unit =
    val target = lock.synchronized(current.plus(by))
    var next = takeDue(target)
    while next.isDefined do
      next.foreach: (due, task) =>
        lock.synchronized { if due.isAfter(current) then current = due }
        task()
      next = takeDue(target)
    lock.synchronized { current = target }

  private def takeDue(target: Instant): Option[(Instant, () => Unit)] = lock.synchronized:
    val due = pending.filterNot(_._1.isAfter(target)).minByOption(_._1)
    due.foreach(entry => pending -= entry)
    due
