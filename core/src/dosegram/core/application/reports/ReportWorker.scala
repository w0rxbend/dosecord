package dosegram.core.application.reports

import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.chat.InteractionReplyPayload
import dosegram.core.domain.copy.ImageCopy
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.StickerCopy
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.ReportRequest
import dosegram.core.ports.ReportStatus
import dosegram.core.ports.StickerImage
import dosegram.core.ports.StickerSetJob
import dosegram.core.ports.StickerSets
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

/** The report worker of ROADMAP G4.3 (ARCHITECTURE section 12.1, stages 5 to 7): claims the oldest request in a short
  * transaction, draws its pages outside every transaction, and puts its messages in the outbox in the transaction that
  * finishes it, so a crash either leaves the request to be claimed again or has already sent it once. A page that
  * cannot be drawn, and every page of a late request whose picture is not drawn already ([[CardDrawing.ready]]), is
  * sent as its text equivalent (VISUALS VA-FB-01) and counted by its template with `textFallback`. A request that was
  * claimed before and not finished is not drawn again: the drawing may be what stopped the worker. A request taken more
  * than [[ReportWorker.LateNoteAfter]] after it was made (the app was not running) is still answered on its account's
  * local day, with a note naming when it was asked; from an earlier day it is dropped, finished `text_fallback` with
  * the error class `Stale`, logged and counted with `dropped` (supervisor decision, 2026-11-28). The log names the
  * request, the template and the kind of failure only.
  */
final class ReportWorker(
    uow: UnitOfWork,
    drawing: CardDrawing,
    clock: Clock,
    ids: IdSource,
    log: String => Unit,
    textFallback: String => Unit,
    stickers: StickerSets = StickerSets.unavailable,
    dropped: String => Unit = _ => ()
):
  import ReportWorker.*

  /** Draws and sends one request; false when none was waiting. */
  def runOnce(): Boolean =
    uow.transaction(_.reports.claim(clock.now(), Lease)) match
      case None          => false
      case Some(request) =>
        val (lang, zone) = uow.transaction: tx =>
          (
            tx.accounts.languageOf(request.accountId),
            ZoneId.of(tx.accounts.timezoneOf(request.accountId).getOrElse("UTC"))
          )
        // A page sent as its text gets the words around it in the account's language (ROADMAP G2.22).
        Lang.within(lang)(answer(request, zone))
        true

  def purge(): Int = uow.transaction(ReportWorker.purge(_, clock.now()))

  /** On time, the answer; late on the day it was asked, the answer with the late note; from an earlier day, nothing. */
  private def answer(request: ReportRequest, zone: ZoneId): Unit =
    val now = clock.now()
    if Duration.between(request.createdAt, now).compareTo(LateNoteAfter) <= 0 then deliver(request, None)
    else if request.createdAt.atZone(zone).toLocalDate == now.atZone(zone).toLocalDate then
      deliver(request, Some(ImageCopy.lateAnswer(Clock24.withZone(zone).format(request.createdAt))))
    else drop(request)

  private def deliver(request: ReportRequest, lateNote: Option[String]): Unit =
    request.stickers match
      case Some(job) => publishStickers(request, job, lateNote)
      case None      => deliverPages(request, lateNote)

  /** Yesterday's figures are not sent as today's answer: the request is finished with nothing sent. */
  private def drop(request: ReportRequest): Unit =
    log(s"report ${request.id}: dropped: $Stale")
    dropped(request.pages.head.card.templateId)
    uow.transaction(_.reports.finish(request.id, ReportStatus.TextFallback, Some(Stale), None, clock.now()))

  private def isDrawable(request: ReportRequest): Boolean =
    request.attempts == 1 && clock.now().isBefore(request.deadlineAt)

  /** "My mood stickers": the pages are drawn and become the stickers of the account's set, in page order; the answer is
    * the first page's message with the link that adds the set, or with the line that says it could not be made. A late
    * or retried request publishes nothing: the user asks again. The log names the request and the kind of failure only.
    */
  private def publishStickers(request: ReportRequest, job: StickerSetJob, lateNote: Option[String]): Unit =
    val started = System.nanoTime()
    val drawable = isDrawable(request)
    val outcome =
      if !drawable then Left("Late")
      else
        try
          for
            drawn <- request.pages.traverse(page => drawing.draw(page.card, Some(request.chat)))
            images = drawn.zip(job.emojis).map((image, emoji) => StickerImage(image.assetKey, emoji))
            link <- stickers.publish(request.chat, job.setKey, StickerCopy.title, images).left.map(_.productPrefix)
          yield link
        catch case NonFatal(error) => Left(error.getClass.getSimpleName)
    val answer = request.pages.head.message
    val message = outcome match
      case Right(link)  => StickerAnswer.ready(answer, link)
      case Left(reason) =>
        log(s"report ${request.id}: sticker set not published: $reason")
        StickerAnswer.failed(answer)
    val renderMillis = Option.when(drawable)(Duration.ofNanos(System.nanoTime() - started).toMillis)
    publish(request, withNote(List(message), lateNote), outcome.left.toOption, renderMillis)

  private def deliverPages(request: ReportRequest, lateNote: Option[String]): Unit =
    val started = System.nanoTime()
    val drawable = isDrawable(request)
    val drawn = request.pages.map: page =>
      try
        if drawable then drawing.draw(page.card, Some(request.chat))
        else drawing.ready(page.card).toRight("Late")
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
    publish(request, withNote(messages, lateNote), failure, renderMillis)

  /** The late note after the lines of the answer's first message: one note per answer. */
  private def withNote(messages: List[OutboundMessage], note: Option[String]): List[OutboundMessage] =
    (messages, note) match
      case (first :: rest, Some(line)) =>
        first.copy(body = first.body :+ Node.Paragraph(List(Inline.Text(line)))) :: rest
      case _ => messages

  private def publish(
      request: ReportRequest,
      messages: List[OutboundMessage],
      failure: Option[String],
      renderMillis: Option[Long]
  ): Unit =
    val now = clock.now()
    uow.transaction: tx =>
      // The pages go in their order, an image before the text sent in place of the next one (V24).
      val rows = messages.map: message =>
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
      tx.outbox.enqueueInOrder(rows)
      val status = if failure.isEmpty then ReportStatus.Sent else ReportStatus.TextFallback
      tx.reports.finish(request.id, status, failure, renderMillis, now)

object ReportWorker:

  extension [A](items: List[A])
    /** The first `Left`, or every `Right` in order. */
    private def traverse[B](each: A => Either[String, B]): Either[String, List[B]] =
      items
        .foldLeft[Either[String, List[B]]](Right(Nil))((done, item) =>
          done.flatMap(heads => each(item).map(_ :: heads))
        )
        .map(_.reverse)

  /** How long a claim holds before another worker may take the request: well past the 5-second raster deadline. */
  val Lease: Duration = Duration.ofSeconds(30)

  /** A request taken later than this after it was made carries the late note: past the longest wait of a full queue (32
    * requests of 5 seconds on two workers, 80 seconds), so the app was not running when it was asked.
    */
  val LateNoteAfter: Duration = Duration.ofMinutes(2)

  /** The error class of a request dropped because it is from an earlier local day of its account. */
  val Stale = "Stale"

  private val Clock24 = DateTimeFormatter.ofPattern("HH:mm")

  /** Deletes the finished requests, which hold no pages, once the daily limit no longer counts them. An unfinished one
    * is the worker's to answer or drop, however old, so none is deleted unsent (verification of 2026-11-28).
    */
  def purge(tx: Tx, now: Instant): Int = tx.reports.purge(now.minus(CardDelivery.Window))

  /** The answer to a user action, sent by the outbox dispatcher like the safety rows of the mediator. */
  private val OutboxKind = "interaction_reply"
