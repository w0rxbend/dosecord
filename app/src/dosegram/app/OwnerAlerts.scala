package dosegram.app

import dosegram.contracts.ChatAdapter
import dosegram.contracts.ChatRef
import dosegram.contracts.RenderedMessage
import dosegram.core.domain.copy.OpsCopy
import dosegram.core.ports.Clock
import dosegram.infra.db.PgOpsHealth

import java.time.Duration
import java.time.Instant
import scala.util.control.NonFatal

/** The owner alerts of OPS-A2 (ARCHITECTURE section 18.4): a dead outbox row or a quarantined inbox update sends one
  * short, silent message to the owner's chat, at most once per condition per [[OwnerAlerts.Interval]]. The text is a
  * fixed sentence with a count ([[OpsCopy]]); it names no account and no content.
  *
  * Each condition counts the rows that reached it after its watermark, which starts when the process starts (the
  * dispatcher and the inbox processor that kill rows run in this process, so nothing dies while it is down). A
  * condition that is held back by the interval keeps its watermark, so the next alert counts every row since the last
  * one. The alert goes straight to the adapter, not through the outbox: it must arrive when the outbox is what is
  * failing, and it never sits in the queue between a reminder and its follow-up.
  */
final class OwnerAlerts(
    probes: PgOpsHealth,
    adapter: ChatAdapter,
    chat: ChatRef,
    clock: Clock,
    log: String => Unit,
    interval: Duration = OwnerAlerts.Interval
):
  import OwnerAlerts.Condition

  private val started = clock.now()
  private val watermarks = scala.collection.mutable.Map[Condition, Instant]().withDefaultValue(started)
  private val lastSent = scala.collection.mutable.Map[Condition, Instant]()

  /** One pass over every condition; returns the number of alerts sent. */
  def checkOnce(): Int = Condition.values.count(check)

  private def check(condition: Condition): Boolean =
    val now = clock.now()
    val heldBack = lastSent.get(condition).exists(sent => Duration.between(sent, now).compareTo(interval) < 0)
    if heldBack then false
    else
      val count = condition.count(probes, watermarks(condition))
      count > 0 && send(condition, count, now)

  private def send(condition: Condition, count: Long, now: Instant): Boolean =
    try
      val message = RenderedMessage(chunks = List(condition.text(count)), silent = true)
      adapter.send(chat, message, s"ops:${condition.id}:${now.getEpochSecond}")
      watermarks(condition) = now
      lastSent(condition) = now
      true
    catch
      case NonFatal(error) =>
        // Tried again at the next pass; the error's message may quote the request, so only its kind is logged.
        log(s"owner alert ${condition.id} not sent: ${error.getClass.getSimpleName}")
        false

object OwnerAlerts:

  /** One alert per condition per hour (ROADMAP G2.18, OpsCopy). */
  val Interval: Duration = Duration.ofHours(1)

  enum Condition(val id: String):
    case OutboxDead extends Condition("outbox_dead")
    case InboxQuarantined extends Condition("inbox_quarantined")

    def count(probes: PgOpsHealth, since: Instant): Long = this match
      case OutboxDead       => probes.deadSince(since)
      case InboxQuarantined => probes.quarantinedSince(since)

    def text(count: Long): String = this match
      case OutboxDead       => OpsCopy.outboxDead(count)
      case InboxQuarantined => OpsCopy.inboxQuarantined(count)
