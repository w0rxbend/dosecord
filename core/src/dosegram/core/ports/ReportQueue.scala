package dosegram.core.ports

import dosegram.contracts.ChatRef
import dosegram.contracts.OutboundMessage

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One image of an answer: the laid-out card and the message that carries it, its caption and controls. */
final case class ReportPage(card: CardPage, message: OutboundMessage)

/** Where a request stands (ARCHITECTURE section 10, `report_requests`). `Sent` and `TextFallback` mean the messages are
  * in the outbox, with the images or with their text equivalents.
  */
enum ReportStatus(val db: String):
  case Queued extends ReportStatus("queued")
  case Rendering extends ReportStatus("rendering")
  case Sent extends ReportStatus("sent")
  case TextFallback extends ReportStatus("text_fallback")

object ReportStatus:
  def fromDb(db: String): ReportStatus =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown report status '$db'"))

/** What an event queues: the pages of one answer, in order, for one chat. Past `deadlineAt` they are sent as text. */
final case class NewReportRequest(
    id: UUID,
    accountId: UUID,
    chat: ChatRef,
    pages: List[ReportPage],
    createdAt: Instant,
    deadlineAt: Instant
):
  require(pages.nonEmpty, "a request has at least one page")

/** A claimed request. `attempts` counts the claims, this one included: more than one means a worker took it before and
  * did not finish.
  */
final case class ReportRequest(
    id: UUID,
    accountId: UUID,
    chat: ChatRef,
    pages: List[ReportPage],
    createdAt: Instant,
    deadlineAt: Instant,
    attempts: Int
)

/** The report preparation queue of ROADMAP G4.3 (ARCHITECTURE section 12.1). An event writes its request on its own
  * transaction; the report worker claims it in a short transaction, draws outside any, and finishes it in the
  * transaction that puts its messages in the outbox.
  */
trait ReportQueue:
  def enqueue(request: NewReportRequest): Unit

  /** Requests queued or being drawn, for every account: the bounded queue of RND-07. */
  def waiting(): Int

  /** Whether the account has a request queued or being drawn: one render at a time per account (VA-FB-03). */
  def isBusy(accountId: UUID): Boolean

  /** When the account's requests since `since` were made, oldest first: the daily limit of VA-FB-03. */
  def requestedSince(accountId: UUID, since: Instant): List[Instant]

  /** The oldest queued request, or one whose claim lapsed, now `rendering` until `now + lease`. */
  def claim(now: Instant, lease: Duration): Option[ReportRequest]

  /** Ends a claimed request and drops its pages: what stays is what the limits count. */
  def finish(id: UUID, status: ReportStatus, errorClass: Option[String], renderMillis: Option[Long], now: Instant): Unit

  /** Deletes the finished requests made before `before` and the unfinished ones made before `unfinishedBefore`; returns
    * how many.
    */
  def purge(before: Instant, unfinishedBefore: Instant): Int
