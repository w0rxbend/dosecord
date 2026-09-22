package dosecord.core.ports

/** The outbox dispatcher's metrics (DESIGN.md section 10): `dosecord_outbox_dead_total` and
  * `dosecord_possible_duplicates_total{vendor}`. Implemented by the infra micrometer registry; the core stays
  * registry-free.
  */
trait OutboxMetrics:

  /** A row went `dead` (attempt budget spent or channelFatal). */
  def outboxDead(): Unit

  /** A send was flagged `possible_duplicate` (ADR-009's bounded-duplicate rule). */
  def possibleDuplicate(vendor: String): Unit

object OutboxMetrics:
  val noop: OutboxMetrics = new OutboxMetrics:
    override def outboxDead(): Unit = ()
    override def possibleDuplicate(vendor: String): Unit = ()
