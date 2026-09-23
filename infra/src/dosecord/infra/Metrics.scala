package dosecord.infra

import dosecord.core.ports.Clock
import dosecord.core.ports.LoopMetrics
import dosecord.core.ports.OutboxMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.core.metrics.Info

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Micrometer registry stub (ROADMAP M0.11): the one Prometheus meter registry of the process. M2.4 exposes it on
  * `/metrics` and registers the rest of the core metric set of DESIGN.md section 10; the tzdb info metric, the reminder
  * loop's tick age, and the outbox dispatcher's dead / possible-duplicate counters exist now.
  */
object Metrics:
  val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

  private val outboxDeadCounter: Counter =
    Counter.builder("dosecord_outbox_dead_total").register(registry)

  private val possibleDuplicateCounters =
    new java.util.concurrent.ConcurrentHashMap[String, Counter]()

  private val unknownCounters =
    new java.util.concurrent.ConcurrentHashMap[String, Counter]()

  /** `dosecord_unknown_total{reason}` (DESIGN.md section 10, ROADMAP M1.8): occurrences marked `unknown`, tagged by
    * reason (`outage` when no worker was healthy across the due window, `undelivered` when workers were healthy but no
    * reminder was confirmed sent); incremented by the reminder loop's catch-up collapse and the materialiser's outage
    * inserts.
    */
  def unknownMarked(reason: String): Unit =
    unknownCounters
      .computeIfAbsent(
        reason,
        r => Counter.builder("dosecord_unknown_total").tag("reason", r).register(registry)
      )
      .increment()

  /** `dosecord_outbox_dead_total` (DESIGN.md section 10, ROADMAP M1.7): terminal `dead` outbox rows (attempt budget
    * spent or channelFatal); the M2.4 alert source.
    */
  def outboxDead(): Unit = outboxDeadCounter.increment()

  /** `dosecord_possible_duplicates_total{vendor}` (DESIGN.md section 10, ROADMAP M1.7): sends flagged
    * `possible_duplicate` under ADR-009's bounded-duplicate rule.
    */
  def possibleDuplicate(vendor: String): Unit =
    possibleDuplicateCounters
      .computeIfAbsent(
        vendor,
        v => Counter.builder("dosecord_possible_duplicates_total").tag("vendor", v).register(registry)
      )
      .increment()

  /** `dosecord_tzdb_info{version}` = 1 (DESIGN.md section 10): the runtime IANA tzdb version resolved by [[TzdbGuard]].
    */
  def registerTzdb(version: String): Unit =
    Info
      .builder()
      .name("dosecord_tzdb_info")
      .help("Runtime IANA tzdb version")
      .labelNames("version")
      .register(registry.getPrometheusRegistry)
      .setLabelValues(version)

  /** `dosecord_scheduler_tick_age_seconds{instance}` (DESIGN.md section 10, ROADMAP M1.6): the age of the loop
    * instance's last completed tick, read off `lastTickCompletedAt` against `clock` at scrape time; NaN (dropped by the
    * Prometheus exposition) until the first tick completes. The `/readyz` tick-age check and the tick-age alert (M2.4)
    * read the same value.
    */
  def registerSchedulerTickAge(
      instance: String,
      clock: Clock,
      lastTickCompletedAt: AtomicReference[Instant]
  ): Unit =
    Gauge
      .builder(
        "dosecord_scheduler_tick_age_seconds",
        lastTickCompletedAt,
        (ref: AtomicReference[Instant]) =>
          Option(ref.get)
            .map(tick => Duration.between(tick, clock.now()).toMillis.toDouble / 1000.0)
            .getOrElse(Double.NaN)
      )
      .tag("instance", instance)
      .register(registry)
    ()

  def scrape(): String = registry.scrape()

/** The dispatcher's metrics port over the shared registry (ROADMAP M1.7). */
final class MicrometerOutboxMetrics extends OutboxMetrics:
  override def outboxDead(): Unit = Metrics.outboxDead()
  override def possibleDuplicate(vendor: String): Unit = Metrics.possibleDuplicate(vendor)

/** The reminder loop's metrics port over the shared registry (ROADMAP M1.8). */
final class MicrometerLoopMetrics extends LoopMetrics:
  override def unknownMarked(reason: String): Unit = Metrics.unknownMarked(reason)
