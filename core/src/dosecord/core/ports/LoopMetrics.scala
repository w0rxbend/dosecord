package dosecord.core.ports

/** The reminder loop's metrics (DESIGN.md section 10, ROADMAP M1.8): `dosecord_unknown_total{reason}` — one increment
  * per occurrence that becomes `unknown`, whether the loop's catch-up collapse or the materialiser's outage insert
  * marked it. Implemented by the infra micrometer registry; the core stays registry-free (the `OutboxMetrics` pattern).
  */
trait LoopMetrics:

  /** An occurrence was marked `unknown`; `reason` is the `UnknownReason` db value (`outage` | `undelivered`). */
  def unknownMarked(reason: String): Unit

object LoopMetrics:
  val noop: LoopMetrics = new LoopMetrics:
    override def unknownMarked(reason: String): Unit = ()
