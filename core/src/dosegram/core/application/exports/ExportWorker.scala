package dosegram.core.application.exports

import dosegram.core.domain.copy.Lang
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.Clock
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.IdSource
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

/** The background half of the export (PRODUCT_SPEC R-IMX-2, ROADMAP G3.10): builds the oldest confirmed export, keeps
  * its file for 15 minutes, and tells the account's chat that it is ready, or that it could not be made. The deleting
  * job empties every file past its lifetime. A failure is logged by the job and the kind of failure only.
  */
final class ExportWorker(
    uow: UnitOfWork,
    clock: Clock,
    ids: IdSource,
    summaries: RecordSummaries,
    renderer: RecordSummaryRenderer,
    messages: ExportMessages,
    log: String => Unit
):

  /** Builds one queued export; `false` when none was queued. */
  def runOnce(): Boolean =
    uow.transaction: tx =>
      tx.exports.claimQueued() match
        case None => false
        // The file and its message are in the account's language (ROADMAP G2.22).
        case Some(job) => Lang.within(tx.accounts.languageOf(job.accountId))(run(tx, job))

  private def run(tx: Tx, job: ExportJob): Boolean =
    val now = clock.now()
    // A failed build rolls back to here, so the job is failed instead of claimed again.
    val built =
      try tx.savepoint(build(tx, job, now))
      catch case NonFatal(cause) => Left(cause.getClass.getSimpleName)
    built match
      case Right(file) =>
        tx.exports.complete(job.id, file, now, now.plus(ExportRepository.FileLifetime))
        ExportRequests.enqueue(tx, ids, job.accountId, messages.ready(job), s"export:${job.id}:ready", now)
      case Left(reason) =>
        log(s"export ${job.id} (${job.kind.db}) failed: $reason")
        tx.exports.fail(job.id, now)
        ExportRequests.enqueue(tx, ids, job.accountId, messages.failed(job), s"export:${job.id}:failed", now)
    true

  /** The deleting job of ARCHITECTURE section 15.6: returns how many exports it ended. */
  def sweep(): Int = uow.transaction(_.exports.expire(clock.now(), ExportRepository.ConfirmationWindow))

  private def build(tx: Tx, job: ExportJob, now: Instant): Either[String, ExportFile] =
    (job.kind, job.period) match
      case (ExportKind.FullArchive, _)              => Right(tx.exports.archive(job.accountId, now))
      case (ExportKind.RecordSummary, Some(period)) => summary(tx, job, period, now)
      case (ExportKind.RecordSummary, None)         => Left("no period")

  private def summary(tx: Tx, job: ExportJob, period: DateRange, now: Instant): Either[String, ExportFile] =
    val zone = ZoneId.of(tx.accounts.timezoneOf(job.accountId).getOrElse("UTC"))
    val document = summaries.document(tx, job.accountId, period, zone, now)
    renderer
      .render(document)
      .map(pdf =>
        ExportFile(
          s"dosegram-report-${ExportWorker.FileDate.format(now.atZone(zone))}.pdf",
          RecordSummaryRenderer.Mime,
          pdf
        )
      )

object ExportWorker:
  private val FileDate = DateTimeFormatter.ofPattern("yyyyMMdd")
