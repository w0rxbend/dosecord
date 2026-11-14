package dosegram.app

import dosegram.core.ports.Clock
import dosegram.infra.db.PgOpsHealth

import java.time.Duration
import scala.util.control.NonFatal

/** `/readyz` (ops track, supervisor decision 2026-11-14, ARCHITECTURE section 18.4): ready when the database answers
  * and the oldest due outbox row is younger than [[Readiness.MaxPendingAge]]. The body is a small JSON object of counts
  * and ages; it names no account and no content.
  */
object Readiness:

  /** `ready` decides the status code: 200 or 503. */
  final case class Report(ready: Boolean, json: String)

  /** Five minutes is the dispatcher's `fallback_after`: a due row older than that means sending has stopped, while a
    * burst that the per-chat budget spreads over a minute or two stays ready.
    */
  val MaxPendingAge: Duration = Duration.ofMinutes(5)

  def check(probes: PgOpsHealth, clock: Clock, maxPendingAge: Duration = MaxPendingAge): Report =
    try
      val now = clock.now()
      val backlog = probes.outboxBacklog(now)
      val ageSeconds = backlog.oldestDueAt.map(due => math.max(0L, Duration.between(due, now).getSeconds)).getOrElse(0L)
      val ready = ageSeconds <= maxPendingAge.getSeconds
      val status = if ready then "ready" else "not_ready"
      Report(
        ready,
        s"""{"status":"$status","database":"up","outbox_pending":${backlog.pending},""" +
          s""""outbox_oldest_pending_seconds":$ageSeconds}"""
      )
    catch
      // Whatever the database said stays out of the body: it can carry a host name or a statement.
      case NonFatal(_) => Report(ready = false, """{"status":"not_ready","database":"down"}""")
