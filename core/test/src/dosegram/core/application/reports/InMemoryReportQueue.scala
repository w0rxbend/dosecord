package dosegram.core.application.reports

import dosegram.core.ports.NewReportRequest
import dosegram.core.ports.ReportQueue
import dosegram.core.ports.ReportRequest
import dosegram.core.ports.ReportStatus

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The report queue of one test world. */
final class InMemoryReportQueue extends ReportQueue:

  final case class Row(
      request: NewReportRequest,
      status: ReportStatus,
      attempts: Int,
      leaseUntil: Option[Instant],
      pagesKept: Boolean = true
  )

  private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, Row]

  def all: List[Row] = synchronized(rows.values.toList)

  def statusOf(id: UUID): Option[ReportStatus] = synchronized(rows.get(id).map(_.status))

  override def enqueue(request: NewReportRequest): Unit = synchronized:
    rows.put(request.id, Row(request, ReportStatus.Queued, 0, None))
    ()

  override def waiting(): Int = synchronized(rows.values.count(row => open(row.status)))

  override def isBusy(accountId: UUID): Boolean =
    synchronized(rows.values.exists(row => row.request.accountId == accountId && open(row.status)))

  override def requestedSince(accountId: UUID, since: Instant): List[Instant] = synchronized:
    rows.values
      .map(_.request)
      .filter(request => request.accountId == accountId && !request.createdAt.isBefore(since))
      .map(_.createdAt)
      .toList
      .sorted

  override def claim(now: Instant, lease: Duration): Option[ReportRequest] = synchronized:
    rows.values
      .find(row =>
        row.status == ReportStatus.Queued ||
          (row.status == ReportStatus.Rendering && row.leaseUntil.exists(!_.isAfter(now)))
      )
      .map: row =>
        val claimed = row.copy(status = ReportStatus.Rendering, attempts = row.attempts + 1, leaseUntil = Some(now.plus(lease)))
        rows.put(row.request.id, claimed)
        val r = row.request
        ReportRequest(r.id, r.accountId, r.chat, r.pages, r.createdAt, r.deadlineAt, claimed.attempts)

  override def finish(
      id: UUID,
      status: ReportStatus,
      errorClass: Option[String],
      renderMillis: Option[Long],
      now: Instant
  ): Unit = synchronized:
    rows.get(id).foreach(row => rows.put(id, row.copy(status = status, leaseUntil = None, pagesKept = false)))

  override def purge(before: Instant, unfinishedBefore: Instant): Int = synchronized:
    val old = rows.values
      .filter(row => row.request.createdAt.isBefore(if open(row.status) then unfinishedBefore else before))
      .map(_.request.id)
      .toList
    old.foreach(rows.remove)
    old.size

  private def open(status: ReportStatus): Boolean = status == ReportStatus.Queued || status == ReportStatus.Rendering
