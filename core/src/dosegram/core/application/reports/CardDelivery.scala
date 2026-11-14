package dosegram.core.application.reports

import dosegram.contracts.ChatRef
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.domain.copy.ImageCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewReportRequest
import dosegram.core.ports.ReportPage
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The image half of an answer (ROADMAP G4.3). On the event's transaction it queues the laid-out pages for the report
  * worker, which draws them after the commit and outside every transaction (ARCHITECTURE RND-05). When the queue is
  * full, when the account already has an image underway, or when it had its images of the day, the pages are answered
  * at once with their text equivalents (VISUALS VA-FB-01, VA-FB-03; RND-07).
  */
final class CardDelivery(clock: Clock, ids: IdSource):
  import CardDelivery.*

  /** The messages to send at once: none when the pages were queued. */
  def answer(tx: Tx, accountId: UUID, chat: ChatRef, pages: List[ReportPage]): List[OutboundMessage] =
    if pages.isEmpty then Nil
    else
      val now = clock.now()
      refusal(tx, accountId, now) match
        case None =>
          tx.reports.enqueue(NewReportRequest(ids.next(now), accountId, chat, pages, now, now.plus(Deadline)))
          Nil
        case Some(note) => pages.map(page => textEquivalent(page, note))

  /** Why the pages cannot be queued, with the line to add to their text; `None` when they can. */
  private def refusal(tx: Tx, accountId: UUID, now: Instant): Option[Option[String]] =
    if tx.reports.waiting() >= QueueCapacity || tx.reports.isBusy(accountId) then Some(None)
    else
      val today = tx.reports.requestedSince(accountId, now.minus(Window))
      Option.when(today.size >= DailyLimit):
        val resetAt = today(today.size - DailyLimit).plus(Window)
        val zone = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))
        Some(ImageCopy.dailyLimit(DailyLimit, Clock24.withZone(zone).format(resetAt)))

object CardDelivery:

  /** VISUALS VA-FB-01: an image not drawn within 5 seconds of the request is sent as its text. */
  val Deadline: Duration = Duration.ofSeconds(5)

  /** ARCHITECTURE RND-07: the bounded queue of the report worker. */
  val QueueCapacity = 32

  /** VISUALS VA-FB-03 and ARCHITECTURE section 15: 20 on-demand images per account in 24 hours. */
  val DailyLimit = 20
  val Window: Duration = Duration.ofHours(24)

  private val Clock24 = DateTimeFormatter.ofPattern("HH:mm")

  /** The message of `page` with its image's text equivalent in place of the image, and `note` after its own lines. */
  def textEquivalent(page: ReportPage, note: Option[String]): OutboundMessage =
    val inPlace = paragraph(ImageCopy.inPlace(page.card.altText))
    // A caption that is the text equivalent itself is not repeated.
    val caption = page.message.body.filterNot(_ == paragraph(page.card.altText))
    page.message.copy(body = (inPlace :: caption) ++ note.map(paragraph), attachment = None)

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))
