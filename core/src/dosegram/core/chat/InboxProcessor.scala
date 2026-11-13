package dosegram.core.chat

import dosegram.core.ports.Clock
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.PendingInboxEntry
import ox.parLimit

import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** The inbox processor (ARCHITECTURE section 7.2.3, ROADMAP G1.7). It offers each stored entry to `deliver`, which
  * returns once the entry's effect is durable (true) or has failed (false), and marks the entry processed only after a
  * true. The chats of a pass run concurrently; the entries of one chat run one at a time in sequence order, and a
  * failed entry holds back the later ones of its chat until it succeeds or is quarantined (TG-06, TG-09).
  *
  * Exactly once: an entry whose delivery committed but whose mark was lost (the process died between them) is offered
  * again after a restart, and the receiver's own idempotency key makes that second delivery change nothing (TG-08).
  */
final class InboxProcessor(
    inbox: InboundInbox,
    deliver: PendingInboxEntry => Boolean,
    clock: Clock,
    log: String => Unit,
    settings: InboxProcessor.Settings = InboxProcessor.Settings.Default,
    quarantined: () => Unit = () => ()
):

  private val signal = Semaphore(0)
  private val running = AtomicBoolean(true)

  /** Something was stored: the waiting loop starts its next pass at once. */
  def wake(): Unit = signal.release()

  def stop(): Unit =
    running.set(false)
    signal.release()

  /** Passes until [[stop]]. A pass that did nothing, or that the database refused, waits for a wake or the idle poll.
    */
  def run(): Unit =
    while running.get() do
      val worked =
        try processOnce()
        catch
          case _: SQLException =>
            log("inbox pass failed: database unavailable")
            0
      if worked == 0 && running.get() then
        signal.tryAcquire(settings.idlePoll.toMillis, TimeUnit.MILLISECONDS)
        signal.drainPermits()
        ()

  /** One pass over the pending entries; returns how many were processed or quarantined. Throws `SQLException` when the
    * pending entries cannot be read.
    */
  def processOnce(): Int =
    val chats = inbox.pending(settings.perChat, settings.batch).groupBy(p => (p.entry.vendor, p.entry.chatKey))
    val passes = chats.values.toList.map(entries => () => processChat(entries.sortBy(_.entry.sequence), 0))
    parLimit(settings.parallelism)(passes).sum

  @tailrec
  private def processChat(entries: List[PendingInboxEntry], done: Int): Int =
    entries match
      case pending :: rest if settled(pending) => processChat(rest, done + 1)
      case _                                   => done

  /** Delivers one entry and records the outcome: true when it is processed or quarantined, false when its chat waits
    * for the next pass. A failed inbox write ends this chat's pass only, so it never cancels another chat's delivery.
    */
  private def settled(pending: PendingInboxEntry): Boolean =
    try
      if delivered(pending) then
        inbox.markProcessed(pending.entry, clock.now())
        true
      else if inbox.recordFailure(pending.entry, settings.maxAttempts, clock.now()) then
        quarantine(pending.entry)
        true
      else false
    catch
      case _: SQLException =>
        log(s"inbox write failed: ${pending.entry.vendorEventId}")
        false

  private def delivered(pending: PendingInboxEntry): Boolean =
    try deliver(pending)
    catch
      case NonFatal(error) =>
        log(s"inbox delivery failed: ${pending.entry.vendorEventId} (${error.getClass.getSimpleName})")
        false

  private def quarantine(entry: InboxEntry): Unit =
    quarantined()
    log(s"inbox entry quarantined: ${entry.vendorEventId}")

object InboxProcessor:

  /** `maxAttempts` is TG-09. The other values are this slice's own: a pass takes up to `perChat` entries of a chat and
    * `batch` in all, runs `parallelism` chats at once, and waits `idlePoll` for a wake when it found nothing.
    */
  final case class Settings(maxAttempts: Int, perChat: Int, batch: Int, parallelism: Int, idlePoll: Duration)

  object Settings:
    val Default: Settings =
      Settings(maxAttempts = 3, perChat = 20, batch = 500, parallelism = 16, idlePoll = Duration.ofSeconds(1))
