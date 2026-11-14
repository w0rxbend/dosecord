package dosegram.core.application.exports

import dosegram.contracts.OutboundMessage
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.LocalDays
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewExportJob
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Why an export was not requested. */
enum ExportRefusal:
  case UnknownAccount

  /** PRODUCT_SPEC R-IMX-2, AC-IMX-3: at most 3 exports per 24 hours; the next one is possible at `nextAt`. */
  case DailyLimit(nextAt: Instant)

  /** A record summary covers 1 to 366 days that have begun (MINI_APP S-14). */
  case PeriodOutOfRange

/** What an export is asked for: the full archive, or the record summary of a period. */
enum ExportOrder:
  case FullArchive
  case RecordSummary(range: DateRange)

  def kind: ExportKind = this match
    case FullArchive      => ExportKind.FullArchive
    case _: RecordSummary => ExportKind.RecordSummary

  def period: Option[DateRange] = this match
    case FullArchive          => None
    case RecordSummary(range) => Some(range)

/** The export requests of both surfaces (PRODUCT_SPEC R-IMX-2). A request from the chat is confirmed by its own tap; a
  * request from the Mini App waits for the confirming tap in the private chat (ARCHITECTURE API-09), which this sends.
  * Every request counts toward the daily limit, on the caller's transaction.
  */
final class ExportRequests(clock: Clock, ids: IdSource, messages: ExportMessages):
  import ExportRequests.*

  def fromChat(tx: Tx, accountId: UUID, order: ExportOrder): Either[ExportRefusal, ExportJob] =
    create(tx, accountId, order, ExportStatus.Queued).map(audited(tx, _, AuditChannel.Bot))

  /** The request of the Mini App and its confirmation message in the account's chat. Without a chat to confirm in, the
    * request stays waiting and ends after the confirmation window.
    */
  def fromApp(tx: Tx, accountId: UUID, order: ExportOrder): Either[ExportRefusal, ExportJob] =
    create(tx, accountId, order, ExportStatus.AwaitingConfirmation).map: job =>
      ExportRequests.enqueue(tx, ids, accountId, messages.confirmation(job), s"export:${job.id}:confirm", clock.now())
      tx.auditLog.record(
        AuditEvent.byUser(
          AuditEventType.StepUpRequested,
          accountId,
          "export",
          Some(job.id),
          "export",
          AuditChannel.MiniApp
        )
      )
      audited(tx, job, AuditChannel.MiniApp)

  /** The record summary of the last `days` days in the account's zone, today included. */
  def lastDays(tx: Tx, accountId: UUID, days: Int): Either[ExportRefusal, ExportOrder] =
    for
      zone <- zoneOf(tx, accountId)
      _ <- Either.cond(days >= 1 && days <= MaxSummaryDays, (), ExportRefusal.PeriodOutOfRange)
    yield ExportOrder.RecordSummary(DateRange.endingOn(LocalDays.dateOf(clock.now(), zone), days))

  /** A record summary of a chosen period: at most 366 days, none of them after today. */
  def between(tx: Tx, accountId: UUID, period: DateRange): Either[ExportRefusal, ExportOrder] =
    for
      zone <- zoneOf(tx, accountId)
      today = LocalDays.dateOf(clock.now(), zone)
      _ <- Either.cond(period.days <= MaxSummaryDays && !period.end.isAfter(today), (), ExportRefusal.PeriodOutOfRange)
    yield ExportOrder.RecordSummary(period)

  private def create(
      tx: Tx,
      accountId: UUID,
      order: ExportOrder,
      status: ExportStatus
  ): Either[ExportRefusal, ExportJob] =
    val now = clock.now()
    for
      _ <- zoneOf(tx, accountId)
      _ <- withinLimit(tx, accountId, now)
    yield tx.exports.create(NewExportJob(ids.next(now), accountId, order.kind, order.period, status, now))

  /** `data.export.requested` (research brief 06 section 10.3), on the transaction of the request. */
  private def audited(tx: Tx, job: ExportJob, channel: AuditChannel): ExportJob =
    tx.auditLog.record(
      AuditEvent.byUser(AuditEventType.ExportRequested, job.accountId, "export", Some(job.id), "export", channel)
    )
    job

  private def withinLimit(tx: Tx, accountId: UUID, now: Instant): Either[ExportRefusal, Unit] =
    val requested = tx.exports.requestedSince(accountId, now.minus(Window))
    if requested.size < DailyLimit then Right(())
    else Left(ExportRefusal.DailyLimit(requested(requested.size - DailyLimit).plus(Window)))

  private def zoneOf(tx: Tx, accountId: UUID): Either[ExportRefusal, ZoneId] =
    tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(ExportRefusal.UnknownAccount)

object ExportRequests:

  /** PRODUCT_SPEC R-IMX-2 (PROPOSED in R06 section 9.2): 3 exports per 24 hours. */
  val DailyLimit = 3
  val Window: Duration = Duration.ofHours(24)

  /** MINI_APP S-14: a custom period of up to 366 days. */
  val MaxSummaryDays = 366

  /** The outbox kind of the export messages; the channel renderer sends them to the account's primary chat. */
  val OutboxKind = "export"

  /** Enqueues `message` for the account's first healthy primary channel; `false` when the account has none. */
  def enqueue(
      tx: Tx,
      ids: IdSource,
      accountId: UUID,
      message: OutboundMessage,
      sendKey: String,
      now: Instant
  ): Boolean =
    tx.channels.activePrimaryChannels(accountId).headOption match
      case Some(target) =>
        tx.outbox.enqueue(
          NewOutboxMessage(
            id = ids.next(now),
            sendKey = sendKey,
            op = OutboxOp.Send,
            kind = OutboxKind,
            vendor = target.vendor,
            accountId = Some(accountId),
            channelId = Some(target.channelId),
            payload = OutboundMessage.toJson(message),
            importance = "info",
            nextAttemptAt = now
          )
        )
      case None => false
end ExportRequests
