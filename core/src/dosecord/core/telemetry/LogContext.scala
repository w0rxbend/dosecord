package dosecord.core.telemetry

import ox.ForkLocal
import ox.Ox

/** Per-fork log context keys (DESIGN.md section 10), bound by the mediator, the reminder loop and the outbox dispatcher
  * so every log line written inside the scope carries them. Vendor-neutral: Ox `ForkLocal` is the concurrency-stack
  * mechanism (core already depends on Ox); the scribe/micrometer wiring lives in `infra` (`dosecord.infra.Telemetry`).
  */
final case class LogContext(
    correlationId: Option[String] = None,
    vendorEventId: Option[String] = None,
    accountId: Option[String] = None,
    vendor: Option[String] = None,
    handler: Option[String] = None,
    occurrenceId: Option[String] = None,
    sendKey: Option[String] = None
):
  /** Bound keys as log field pairs (snake_case, DESIGN.md section 10); unbound keys are omitted. */
  def fields: List[(String, String)] =
    List(
      correlationId.map("correlation_id" -> _),
      vendorEventId.map("vendor_event_id" -> _),
      accountId.map("account_id" -> _),
      vendor.map("vendor" -> _),
      handler.map("handler" -> _),
      occurrenceId.map("occurrence_id" -> _),
      sendKey.map("send_key" -> _)
    ).flatten

object LogContext:
  val empty: LogContext = LogContext()

  private val local: ForkLocal[LogContext] = ForkLocal(empty)

  /** The context bound to the most-nested concurrency scope ([[empty]] outside any binding). */
  def current: LogContext = local.get()

  /** Runs `f` in a nested scope with `context` bound; forks created inside inherit it (Ox `ForkLocal` semantics). */
  def scoped[U](context: LogContext)(f: Ox ?=> U): U = local.supervisedWhere(context)(f)

  /** Runs `f` in a nested scope with the current context updated by `update`. */
  def scopedWith[U](update: LogContext => LogContext)(f: Ox ?=> U): U = local.supervisedWhere(update(current))(f)
