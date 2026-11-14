package dosegram.core.application.reports

import dosegram.contracts.OutboundMessage
import dosegram.core.chat.InteractionReplyPayload
import dosegram.core.domain.copy.Lang
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.ReportRequest
import dosegram.core.ports.ReportStatus
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant
import scala.util.control.NonFatal

/** The report worker of ROADMAP G4.3 (ARCHITECTURE section 12.1, stages 5 to 7): claims the oldest request in a short
  * transaction, draws its pages outside every transaction, and puts its messages in the outbox in the transaction that
  * finishes it, so a crash either leaves the request to be claimed again or has already sent it once. A page that
  * cannot be drawn, and every page of a late request, is sent as its text equivalent (VISUALS VA-FB-01) and counted by
  * its template with `textFallback`. A request that was claimed before and not finished is not drawn again: the drawing
  * may be what stopped the worker. The log names the request, the template and the kind of failure only.
  */
final class ReportWorker(
    uow: UnitOfWork,
    drawing: CardDrawing,
    clock: Clock,
    ids: IdSource,
    log: String => Unit,
    textFallback: String => Unit
):
  import ReportWorker.*

  /** Draws and sends one request; false when none was waiting. */
  def runOnce(): Boolean =
    uow.transaction(_.reports.claim(clock.now(), Lease)) match
      case None          => false
      case Some(request) =>
        // A page sent as its text gets the words around it in the account's language (ROADMAP G2.22).
        Lang.within(uow.transaction(_.accounts.languageOf(request.accountId)))(deliver(request))
        true

  def purge(): Int = uow.transaction(ReportWorker.purge(_, clock.now()))

  private def deliver(request: ReportRequest): Unit =
    val started = System.nanoTime()
    val drawable = request.attempts == 1 && clock.now().isBefore(request.deadlineAt)
    val drawn = request.pages.map: page =>
      if !drawable then Left("Late")
      else
        try drawing.draw(page.card, Some(request.chat))
        catch case NonFatal(error) => Left(error.getClass.getSimpleName)
    val messages = request.pages
      .zip(drawn)
      .map:
        case (page, Right(image)) => page.message.copy(attachment = Some(image))
        case (page, Left(reason)) =>
          log(s"report ${request.id}: ${page.card.templateId} sent as text: $reason")
          textFallback(page.card.templateId)
          CardDelivery.textEquivalent(page, None)
    val failure = drawn.collectFirst { case Left(reason) => reason }
    val renderMillis = Option.when(drawable)(Duration.ofNanos(System.nanoTime() - started).toMillis)
    publish(request, messages, failure, renderMillis)

  private def publish(
      request: ReportRequest,
      messages: List[OutboundMessage],
      failure: Option[String],
      renderMillis: Option[Long]
  ): Unit =
    val now = clock.now()
    uow.transaction: tx =>
      messages.foreach: message =>
        tx.outbox.enqueue(
          NewOutboxMessage(
            id = ids.next(now),
            sendKey = message.dedupeKey,
            op = OutboxOp.Send,
            kind = OutboxKind,
            vendor = request.chat.vendor,
            accountId = Some(request.accountId),
            payload = InteractionReplyPayload.encode(message, request.chat),
            importance = OutboxKind,
            nextAttemptAt = now
          )
        )
      val status = if failure.isEmpty then ReportStatus.Sent else ReportStatus.TextFallback
      tx.reports.finish(request.id, status, failure, renderMillis, now)

object ReportWorker:

  /** How long a claim holds before another worker may take the request: well past the 5-second raster deadline. */
  val Lease: Duration = Duration.ofSeconds(30)

  /** An unfinished request, its SVG included, is kept at most this long, like the image bytes of `render_assets`. */
  val Retention: Duration = Duration.ofHours(1)

  /** Deletes the unfinished requests older than [[Retention]], whose SVG holds the account's figures, and the finished
    * ones, which hold no pages, once the daily limit no longer counts them.
    */
  def purge(tx: Tx, now: Instant): Int = tx.reports.purge(now.minus(CardDelivery.Window), now.minus(Retention))

  /** The answer to a user action, sent by the outbox dispatcher like the safety rows of the mediator. */
  private val OutboxKind = "interaction_reply"
