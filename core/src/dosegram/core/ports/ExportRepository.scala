package dosegram.core.ports

import dosegram.core.domain.insights.DateRange

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What an export produces (PRODUCT_SPEC section 8.17): the full archive of the account, or the record summary of a
  * period (R-IMX-6). `db` is the stored kind of ARCHITECTURE section 10.13.
  */
enum ExportKind(val db: String):
  case FullArchive extends ExportKind("full_zip")
  case RecordSummary extends ExportKind("record_summary_pdf")

object ExportKind:
  def fromDb(db: String): ExportKind =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown export kind '$db'"))

/** Where an export is (MINI_APP MA-REP-5). A request from the Mini App waits for the confirming tap in the chat; a
  * request from the chat is confirmed by its own tap.
  */
enum ExportStatus(val db: String):
  case AwaitingConfirmation extends ExportStatus("awaiting_confirmation")
  case Queued extends ExportStatus("queued")
  case Ready extends ExportStatus("ready")
  case Downloaded extends ExportStatus("downloaded")
  case Expired extends ExportStatus("expired")
  case Failed extends ExportStatus("failed")

object ExportStatus:
  def fromDb(db: String): ExportStatus =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown export status '$db'"))

/** One export of an account, without its bytes. `period` is the period of a record summary. */
final case class ExportJob(
    id: UUID,
    accountId: UUID,
    kind: ExportKind,
    period: Option[DateRange],
    status: ExportStatus,
    fileName: Option[String],
    byteSize: Option[Int],
    createdAt: Instant,
    readyAt: Option[Instant],
    expiresAt: Option[Instant]
)

/** A new export as its request creates it. */
final case class NewExportJob(
    id: UUID,
    accountId: UUID,
    kind: ExportKind,
    period: Option[DateRange],
    status: ExportStatus,
    at: Instant
):
  require(
    status == ExportStatus.AwaitingConfirmation || status == ExportStatus.Queued,
    "an export starts awaiting its confirmation or queued"
  )
  require(period.isDefined == (kind == ExportKind.RecordSummary), "a record summary, and only it, has a period")

/** The file an export produced. `bytes` is the content as the user downloads it. */
final case class ExportFile(fileName: String, mime: String, bytes: Array[Byte]):
  require(fileName.nonEmpty, "an export file has a name")
  require(bytes.nonEmpty, "an export file is not empty")

/** The SHA-256 of a download ticket and the address prefix of the request that presents it. */
final case class TicketRedemption(ticketHash: Array[Byte], ipPrefix: String)

/** Why a download ticket gave no file (ARCHITECTURE section 9.7). Every reason answers the same to the requester. */
enum TicketRefusal:
  case Unknown, Expired, OtherAddress, RequestsSpent, ExportGone

/** The export jobs and download tickets of ARCHITECTURE section 10.13, on the caller's transaction. Every read and
  * every change of a job names the account, so one account never reaches the export of another.
  */
trait ExportRepository:

  def create(job: NewExportJob): ExportJob

  /** The instants of the account's export requests since `since`, oldest first: the daily limit counts them. */
  def requestedSince(accountId: UUID, since: Instant): List[Instant]

  def find(accountId: UUID, id: UUID): Option[ExportJob]

  /** The account's latest exports, newest first. */
  def recent(accountId: UUID, limit: Int): List[ExportJob]

  /** Moves a job that waits for its confirmation to the queue. `false` when it does not wait. */
  def confirm(accountId: UUID, id: UUID, at: Instant): Boolean

  /** Ends a job that still waits for its confirmation. `false` when it does not wait. */
  def cancel(accountId: UUID, id: UUID, at: Instant): Boolean

  /** Takes the oldest queued job for this transaction; another transaction skips it. */
  def claimQueued(): Option[ExportJob]

  /** Stores the file of a claimed job: the job is ready until `expiresAt`. */
  def complete(id: UUID, file: ExportFile, at: Instant, expiresAt: Instant): Unit

  def fail(id: UUID, at: Instant): Unit

  /** The file of a ready or downloaded job of the account that has not expired at `now`. */
  def file(accountId: UUID, id: UUID, now: Instant): Option[ExportFile]

  /** The file of any job that has not expired at `now`, for the adapter that sends it into the chat. */
  def fileForDelivery(id: UUID, now: Instant): Option[ExportFile]

  def markDownloaded(accountId: UUID, id: UUID, at: Instant): Unit

  /** The deleting job: empties the bytes of every job past its lifetime, ends every request that waited for its
    * confirmation longer than `confirmationWindow`, removes the expired tickets, and returns how many jobs it ended.
    */
  def expire(now: Instant, confirmationWindow: Duration): Int

  /** Records that the user confirmed the warning before the file of `exportId` was sent into the chat: the per-export
    * consent `export_to_chat` (PRODUCT_SPEC R-IDC-6, BOT_UX BUX-EXP-02), with the export as its subject.
    */
  def recordChatConsent(accountId: UUID, exportId: UUID, notice: ConsentNotice, taps: ConsentTaps): Unit

  /** Stores a ticket by its hash, bound to one export of the account. */
  def issueTicket(accountId: UUID, exportId: UUID, ticketHash: Array[Byte], at: Instant, expiresAt: Instant): Unit

  /** Spends one request of a ticket and returns the file it is bound to; the first request fixes the address prefix.
    */
  def redeem(redemption: TicketRedemption, now: Instant): Either[TicketRefusal, ExportFile]

  /** The full archive of the account at `at`: every record of every domain as JSON and CSV in one ZIP (R-IMX-1). */
  def archive(accountId: UUID, at: Instant): ExportFile

object ExportRepository:

  /** ARCHITECTURE section 9.7 and PRODUCT_SPEC R-IMX-2. */
  val FileLifetime: Duration = Duration.ofMinutes(15)
  val TicketLifetime: Duration = Duration.ofMinutes(15)
  val TicketRequests: Int = 5

  /** API-09: a pending step-up expires after 10 minutes. */
  val ConfirmationWindow: Duration = Duration.ofMinutes(10)
