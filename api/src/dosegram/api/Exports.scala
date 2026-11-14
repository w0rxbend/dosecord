package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.api.ApiJson.jsonBody
import dosegram.core.application.exports.ExportOrder
import dosegram.core.application.exports.ExportRefusal
import dosegram.core.application.exports.ExportRequests
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.Clock
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.TicketRedemption
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.tapir.*

import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import scala.util.Try

// ---------- documents (MINI_APP section 15.1, S-14) ----------

enum ExportKindWire(val wire: String):
  case FullZip extends ExportKindWire("full_zip")
  case RecordSummaryPdf extends ExportKindWire("record_summary_pdf")

object ExportKindWire:
  given ReadWriter[ExportKindWire] = ApiJson.wireEnum(values, _.wire)
  given Schema[ExportKindWire] = Schema.derivedEnumeration[ExportKindWire](encode = Some(_.wire))

/** MA-REP-5: waiting for the confirmation in chat, preparing, ready until `expiresAt`, downloaded, expired, failed. */
enum ExportState(val wire: String):
  case AwaitingConfirmation extends ExportState("awaiting_confirmation")
  case Preparing extends ExportState("preparing")
  case Ready extends ExportState("ready")
  case Downloaded extends ExportState("downloaded")
  case Expired extends ExportState("expired")
  case Failed extends ExportState("failed")

object ExportState:
  given ReadWriter[ExportState] = ApiJson.wireEnum(values, _.wire)
  given Schema[ExportState] = Schema.derivedEnumeration[ExportState](encode = Some(_.wire))

/** A request: the full export, or the record summary of the last `days` days in the account's zone, or of `from` to
  * `to` (both or neither); with none of them, the last 90 days.
  */
final case class ExportInput(
    kind: ExportKindWire,
    days: Option[Int] = None,
    from: Option[LocalDate] = None,
    to: Option[LocalDate] = None
) derives ReadWriter,
      Schema

final case class ExportView(
    id: UUID,
    kind: ExportKindWire,
    state: ExportState,
    from: Option[LocalDate],
    to: Option[LocalDate],
    fileName: Option[String],
    byteSize: Option[Int],
    createdAt: Instant,
    expiresAt: Option[Instant]
) derives ReadWriter,
      Schema

final case class ExportList(items: List[ExportView]) derives ReadWriter, Schema

/** `url` is fetched by the Telegram client with `downloadFile` (MA-NATIVE-13); the ticket in it is its only credential.
  */
final case class DownloadTicket(url: String, fileName: String, expiresAt: Instant) derives ReadWriter, Schema

/** The file a ticket gives, with the headers ARCHITECTURE section 9.7 asks for. */
final case class Download(contentDisposition: String, contentType: String, bytes: Array[Byte])

// ---------- use cases ----------

/** The export routes (ARCHITECTURE sections 9.5 and 9.7; ROADMAP G3.10). A request is confirmed in the chat before
  * anything is made (API-09, AC-IMX-4); the file is fetched with a download ticket that is bound to one export of the
  * account, valid for 15 minutes and for at most 5 requests from the address prefix of its first request.
  */
final class Exports(uow: UnitOfWork, clock: Clock, requests: ExportRequests):
  import Exports.*

  private val random = SecureRandom()

  def request(accountId: UUID, input: ExportInput): Either[ApiFailure, ExportView] =
    uow.transaction: tx =>
      val order = input.kind match
        case ExportKindWire.FullZip          => Right(ExportOrder.FullArchive)
        case ExportKindWire.RecordSummaryPdf =>
          (input.from, input.to) match
            case (None, None) =>
              val days = input.days.getOrElse(DefaultSummaryDays)
              requests.lastDays(tx, accountId, days).left.map(refusalOf(_, clock.now()))
            case (Some(from), Some(to)) if !to.isBefore(from) =>
              requests.between(tx, accountId, DateRange.between(from, to)).left.map(refusalOf(_, clock.now()))
            case (Some(_), Some(_)) => Left(ApiFailure.invalid("to", "before_from"))
            case (Some(_), None)    => Left(ApiFailure.invalid("to", "required"))
            case (None, Some(_))    => Left(ApiFailure.invalid("from", "required"))
      order.flatMap(requests.fromApp(tx, accountId, _).left.map(refusalOf(_, clock.now()))).map(viewOf)

  def list(accountId: UUID): ExportList =
    ExportList(uow.transaction(_.exports.recent(accountId, ListLimit)).map(viewOf))

  def get(accountId: UUID, id: UUID): Either[ApiFailure, ExportView] =
    uow.transaction(_.exports.find(accountId, id)).map(viewOf).toRight(ApiFailure.notFound())

  /** A ticket for an export whose file is ready and not yet downloaded (R-IMX-2: one download). */
  def ticket(accountId: UUID, id: UUID): Either[ApiFailure, DownloadTicket] =
    val now = clock.now()
    uow.transaction: tx =>
      tx.exports.find(accountId, id) match
        case None                                                               => Left(ApiFailure.notFound())
        case Some(job) if job.status == ExportStatus.AwaitingConfirmation       => Left(ApiFailure.stepupPending())
        case Some(job) if job.status != ExportStatus.Ready || !isHeld(job, now) => Left(ApiFailure.notFound())
        case Some(job)                                                          =>
          val ticket = newTicket()
          val expiresAt = now.plus(ExportRepository.TicketLifetime)
          tx.exports.issueTicket(accountId, id, hashOf(ticket), now, expiresAt)
          Right(DownloadTicket(s"/api/v1/downloads/$ticket", job.fileName.getOrElse("dosegram-export"), expiresAt))

  /** The ticket route: any refusal answers as not found, so the answer says nothing about the ticket. */
  def download(ticket: String, address: Option[String]): Either[ApiFailure, Download] =
    val now = clock.now()
    val redeemed =
      for
        _ <- Either.cond(TicketShape.matches(ticket), (), ())
        prefix <- address.flatMap(prefixOf).toRight(())
        file <- uow.transaction(_.exports.redeem(TicketRedemption(hashOf(ticket), prefix), now)).left.map(_ => ())
      yield file
    redeemed.left.map(_ => ApiFailure.notFound()).map(downloadOf)

  private def newTicket(): String =
    val bytes = new Array[Byte](TicketBytes)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

end Exports

object Exports:

  /** VISUALS DOC-01: the record summary covers the last 90 days unless the user chooses. */
  val DefaultSummaryDays = 90
  val ListLimit = 20
  private val TicketBytes = 32
  private val TicketShape = "^[A-Za-z0-9_-]{43}$".r

  /** The origin `downloadFile` asks the route to allow (ARCHITECTURE section 9.7). */
  val AllowedOrigin = "https://web.telegram.org"

  private def isHeld(job: ExportJob, now: Instant): Boolean = job.expiresAt.exists(_.isAfter(now))

  def hashOf(ticket: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(UTF_8))

  /** The address prefix a ticket is bound to: the /24 of an IPv4 address, the /48 of an IPv6 address. */
  def prefixOf(address: String): Option[String] =
    Try(InetAddress.ofLiteral(address.trim)).toOption.map: inet =>
      val bytes = inet.getAddress
      if bytes.length == 4 then bytes.take(3).map(b => (b & 0xff).toString).mkString(".")
      else bytes.take(6).map(b => f"${b & 0xff}%02x").mkString(":")

  /** The client address: the last `X-Forwarded-For` entry, which the TLS proxy appends (DEPLOY_MINIAPP section 3), or
    * the address of the connection when no proxy is in front.
    */
  def clientAddress(forwardedFor: Option[String], remote: Option[String]): Option[String] =
    forwardedFor.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty).lastOption).orElse(remote)

  private def refusalOf(refusal: ExportRefusal, now: Instant): ApiFailure =
    refusal match
      case ExportRefusal.UnknownAccount   => ApiFailure.notFound()
      case ExportRefusal.PeriodOutOfRange => ApiFailure.invalid("to", "range_too_long")
      case ExportRefusal.DailyLimit(next) =>
        ApiFailure.rateLimited(Duration.between(now, next).toSeconds.max(1L))

  def viewOf(job: ExportJob): ExportView =
    ExportView(
      id = job.id,
      kind = job.kind match
        case ExportKind.FullArchive   => ExportKindWire.FullZip
        case ExportKind.RecordSummary => ExportKindWire.RecordSummaryPdf,
      state = job.status match
        case ExportStatus.AwaitingConfirmation => ExportState.AwaitingConfirmation
        case ExportStatus.Queued               => ExportState.Preparing
        case ExportStatus.Ready                => ExportState.Ready
        case ExportStatus.Downloaded           => ExportState.Downloaded
        case ExportStatus.Expired              => ExportState.Expired
        case ExportStatus.Failed               => ExportState.Failed,
      from = job.period.map(_.start),
      to = job.period.map(_.end),
      fileName = job.fileName,
      byteSize = job.byteSize,
      createdAt = job.createdAt,
      expiresAt = job.expiresAt
    )

  private def downloadOf(file: ExportFile): Download =
    Download(s"attachment; filename=\"${file.fileName}\"", file.mime, file.bytes)

// ---------- endpoints ----------

/** The Tapir definitions of the export routes, mounted under `/api/v1` like the others. */
object ExportEndpoints:

  private val id = path[UUID]("id")
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID per user action; required (API-16)")

  val createExport =
    Endpoints.secured.post
      .in("exports")
      .in(idempotencyKey)
      .in(jsonBody[ExportInput])
      .out(statusCode(StatusCode.Accepted))
      .out(jsonBody[ExportView])
      .name("createExport")
      .description(
        "Requests the full export or the record summary. Nothing is made before the confirming tap in the chat; " +
          "at most 3 requests per 24 hours."
      )

  val listExports = Endpoints.secured.get.in("exports").out(jsonBody[ExportList]).name("listExports")

  val getExport = Endpoints.secured.get.in("exports" / id).out(jsonBody[ExportView]).name("getExport")

  val createDownloadTicket =
    Endpoints.secured.post
      .in("exports" / id / "download-tickets")
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[DownloadTicket])
      .name("createDownloadTicket")
      .description("A ticket URL for downloadFile: 15 minutes, one export, 5 requests from one address prefix.")

  val download =
    Endpoints.v1.get
      .in("downloads" / path[String]("ticket"))
      .in(header[Option[String]]("X-Forwarded-For"))
      .in(extractFromRequest(_.connectionInfo.remote.map(_.getAddress.getHostAddress)))
      .out(header[String]("Content-Disposition"))
      .out(header[String]("Content-Type"))
      .out(header("Access-Control-Allow-Origin", Exports.AllowedOrigin))
      .out(byteArrayBody)
      .name("download")
      .description("The file of an export. The ticket is the only credential; the Telegram client fetches it.")

  val documented: List[AnyEndpoint] = List(createExport, listExports, getExport, createDownloadTicket, download)
end ExportEndpoints
