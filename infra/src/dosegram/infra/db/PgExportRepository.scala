package dosegram.infra.db

import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.AuditLog
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewExportJob
import dosegram.core.ports.TicketRedemption
import dosegram.core.ports.TicketRefusal

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.language.implicitConversions

/** `export_jobs` and `export_download_tickets` (ARCHITECTURE section 10.13) on the connection of the surrounding
  * transaction. Every statement on a job of an account names the account.
  */
final class PgExportRepository(conn: Connection, ids: IdSource, audit: AuditLog) extends ExportRepository:
  private given Connection = conn

  private val Columns =
    "id, account_id, kind, params, status, file_name, byte_size, created_at, ready_at, expires_at"

  private given RowMapper[ExportJob] = rs =>
    ExportJob(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      kind = ExportKind.fromDb(rs.getString("kind")),
      period = periodOf(rs.getString("params")),
      status = ExportStatus.fromDb(rs.getString("status")),
      fileName = rs.optString("file_name"),
      byteSize = rs.optInt("byte_size"),
      createdAt = rs.instant("created_at"),
      readyAt = rs.optInstant("ready_at"),
      expiresAt = rs.optInstant("expires_at")
    )

  private given RowMapper[ExportFile] = rs =>
    ExportFile(rs.getString("file_name"), rs.getString("mime"), rs.getBytes("content"))

  override def create(job: NewExportJob): ExportJob =
    val confirmedAt = Option.when(job.status == ExportStatus.Queued)(job.at)
    sql"""INSERT INTO export_jobs (id, account_id, kind, params, status, created_at, confirmed_at)
          VALUES (${job.id}, ${job.accountId}, ${job.kind.db}, ${Jsonb(paramsOf(job.period))}, ${job.status.db},
                  ${job.at}, $confirmedAt)""".execute()
    ExportJob(job.id, job.accountId, job.kind, job.period, job.status, None, None, job.at, None, None)

  override def requestedSince(accountId: UUID, since: Instant): List[Instant] =
    sql"""SELECT created_at FROM export_jobs WHERE account_id = $accountId AND created_at > $since
          ORDER BY created_at""".query[Instant]()

  override def find(accountId: UUID, id: UUID): Option[ExportJob] =
    SqlQuery(s"SELECT $Columns FROM export_jobs WHERE account_id = ? AND id = ?", Vector[SqlBind](accountId, id))
      .queryOne[ExportJob]()

  override def recent(accountId: UUID, limit: Int): List[ExportJob] =
    SqlQuery(
      s"SELECT $Columns FROM export_jobs WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
      Vector[SqlBind](accountId, limit)
    ).query[ExportJob]()

  override def confirm(accountId: UUID, id: UUID, at: Instant): Boolean =
    sql"""UPDATE export_jobs SET status = 'queued', confirmed_at = $at
          WHERE account_id = $accountId AND id = $id AND status = 'awaiting_confirmation'""".execute() == 1

  override def cancel(accountId: UUID, id: UUID, at: Instant): Boolean =
    sql"""UPDATE export_jobs SET status = 'expired'
          WHERE account_id = $accountId AND id = $id AND status = 'awaiting_confirmation'""".execute() == 1

  override def claimQueued(): Option[ExportJob] =
    SqlQuery(
      s"""SELECT $Columns FROM export_jobs WHERE status = 'queued'
          ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED""",
      Vector.empty
    ).queryOne[ExportJob]()

  override def complete(id: UUID, file: ExportFile, at: Instant, expiresAt: Instant): Unit =
    sql"""UPDATE export_jobs
          SET status = 'ready', file_name = ${file.fileName}, mime = ${file.mime}, byte_size = ${file.bytes.length},
              content = ${file.bytes}, ready_at = $at, expires_at = $expiresAt
          WHERE id = $id AND status = 'queued'""".execute()
    ()

  override def fail(id: UUID, at: Instant): Unit =
    sql"UPDATE export_jobs SET status = 'failed' WHERE id = $id AND status = 'queued'".execute()
    ()

  override def file(accountId: UUID, id: UUID, now: Instant): Option[ExportFile] =
    sql"""SELECT file_name, mime, content FROM export_jobs
          WHERE account_id = $accountId AND id = $id AND content IS NOT NULL AND expires_at > $now"""
      .queryOne[ExportFile]()

  override def fileForDelivery(id: UUID, now: Instant): Option[ExportFile] =
    sql"""SELECT file_name, mime, content FROM export_jobs
          WHERE id = $id AND content IS NOT NULL AND expires_at > $now""".queryOne[ExportFile]()

  override def markDownloaded(accountId: UUID, id: UUID, at: Instant): Unit =
    sql"""UPDATE export_jobs SET status = 'downloaded', downloaded_at = $at
          WHERE account_id = $accountId AND id = $id AND status = 'ready'""".execute()
    ()

  override def expire(now: Instant, confirmationWindow: Duration): Int =
    val lapsed =
      sql"""UPDATE export_jobs SET status = 'expired', content = NULL
            WHERE status IN ('ready', 'downloaded') AND expires_at <= $now""".execute()
    val unconfirmed =
      sql"""UPDATE export_jobs SET status = 'expired'
            WHERE status = 'awaiting_confirmation' AND created_at <= ${now.minus(confirmationWindow)}""".execute()
    sql"DELETE FROM export_download_tickets WHERE expires_at <= $now".execute()
    lapsed + unconfirmed

  override def recordChatConsent(accountId: UUID, exportId: UUID, notice: ConsentNotice, taps: ConsentTaps): Unit =
    val hash = MessageDigest.getInstance("SHA-256").digest(notice.statement.getBytes(UTF_8))
    sql"""INSERT INTO consent_events (id, account_id, purpose, action, policy_version, notice_hash, locale, agreed_at,
                                      channel, subject_ref, occurred_at)
          VALUES (${ids.next(taps.confirmedAt)}, $accountId, 'export_to_chat', 'granted', ${notice.policyVersion},
                  $hash, ${notice.locale}, ${taps.agreedAt}, 'bot', $exportId, ${taps.confirmedAt})""".execute()
    ()

  override def issueTicket(
      accountId: UUID,
      exportId: UUID,
      ticketHash: Array[Byte],
      at: Instant,
      expiresAt: Instant
  ): Unit =
    sql"""INSERT INTO export_download_tickets (ticket_hash, export_id, account_id, created_at, expires_at)
          VALUES ($ticketHash, $exportId, $accountId, $at, $expiresAt)""".execute()
    ()

  override def redeem(redemption: TicketRedemption, now: Instant): Either[TicketRefusal, ExportFile] =
    val ticket = sql"""SELECT export_id, account_id, expires_at, ip_prefix, requests FROM export_download_tickets
                       WHERE ticket_hash = ${redemption.ticketHash} FOR UPDATE""".queryOne[Ticket]()
    for
      held <- ticket.toRight(TicketRefusal.Unknown)
      _ <- Either.cond(held.expiresAt.isAfter(now), (), TicketRefusal.Expired)
      _ <- Either.cond(held.ipPrefix.forall(_ == redemption.ipPrefix), (), TicketRefusal.OtherAddress)
      _ <- Either.cond(held.requests < ExportRepository.TicketRequests, (), TicketRefusal.RequestsSpent)
      found <- file(held.accountId, held.exportId, now).toRight(TicketRefusal.ExportGone)
    yield
      sql"""UPDATE export_download_tickets SET requests = requests + 1, ip_prefix = ${redemption.ipPrefix}
            WHERE ticket_hash = ${redemption.ticketHash}""".execute()
      markDownloaded(held.accountId, held.exportId, now)
      audit.record(
        AuditEvent.byUser(
          AuditEventType.ExportDownloaded,
          held.accountId,
          "export",
          Some(held.exportId),
          "export",
          AuditChannel.MiniApp
        )
      )
      found

  override def archive(accountId: UUID, at: Instant): ExportFile = AccountArchive.build(conn, accountId, at)

  private final case class Ticket(
      exportId: UUID,
      accountId: UUID,
      expiresAt: Instant,
      ipPrefix: Option[String],
      requests: Int
  )

  private given RowMapper[Ticket] = rs =>
    Ticket(
      rs.uuid("export_id"),
      rs.uuid("account_id"),
      rs.instant("expires_at"),
      rs.optString("ip_prefix"),
      rs.getInt(5)
    )

  private def paramsOf(period: Option[DateRange]): String =
    period.fold("{}")(range => ujson.write(ujson.Obj("from" -> range.start.toString, "to" -> range.end.toString)))

  private def periodOf(params: String): Option[DateRange] =
    val json = ujson.read(params)
    for
      from <- json.obj.get("from").map(value => LocalDate.parse(value.str))
      to <- json.obj.get("to").map(value => LocalDate.parse(value.str))
    yield DateRange.between(from, to)
end PgExportRepository
