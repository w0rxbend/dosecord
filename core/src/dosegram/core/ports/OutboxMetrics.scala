package dosegram.core.ports

/** Why an outbox row was retried, failed or went dead: a fixed code with no user content, safe for a metric label and a
  * log line (ARCHITECTURE section 18.1, rule SEC-12). A vendor's error text is never one of these.
  */
enum OutboxReason(val code: String):
  case RateLimited extends OutboxReason("rate_limited")
  case Retryable extends OutboxReason("retryable")
  case TooOld extends OutboxReason("too_old")
  case ChannelFatal extends OutboxReason("channel_fatal")
  case Permanent extends OutboxReason("permanent")
  case Unrenderable extends OutboxReason("unrenderable")

/** The outbox dispatcher's metrics (DESIGN.md section 10, ARCHITECTURE section 18.2): sent, retried and dead rows, rows
  * that failed permanently, and `dosegram_possible_duplicates_total{vendor}`. `kind` is the row's outbox kind, a fixed
  * name. Implemented by the infra micrometer registry; the core stays registry-free.
  */
trait OutboxMetrics:

  /** A row was delivered. */
  def sent(vendor: String, kind: String): Unit

  /** A row will be tried again after a backoff. */
  def retried(vendor: String, kind: String, reason: OutboxReason): Unit

  /** A row went `dead` (attempt budget spent or channelFatal). */
  def dead(vendor: String, kind: String, reason: OutboxReason): Unit

  /** A row ended `failed_permanent` without a channel action. */
  def failed(vendor: String, kind: String, reason: OutboxReason): Unit

  /** A send was flagged `possible_duplicate` (ADR-009's bounded-duplicate rule). */
  def possibleDuplicate(vendor: String): Unit

object OutboxMetrics:
  val noop: OutboxMetrics = new OutboxMetrics:
    override def sent(vendor: String, kind: String): Unit = ()
    override def retried(vendor: String, kind: String, reason: OutboxReason): Unit = ()
    override def dead(vendor: String, kind: String, reason: OutboxReason): Unit = ()
    override def failed(vendor: String, kind: String, reason: OutboxReason): Unit = ()
    override def possibleDuplicate(vendor: String): Unit = ()
