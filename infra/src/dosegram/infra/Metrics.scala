package dosegram.infra

import dosegram.core.ports.Clock
import dosegram.core.ports.LoopMetrics
import dosegram.core.ports.OutboxMetrics
import dosegram.core.ports.OutboxReason
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

  private val possibleDuplicateCounters =
    new java.util.concurrent.ConcurrentHashMap[String, Counter]()

  private val unknownCounters =
    new java.util.concurrent.ConcurrentHashMap[String, Counter]()

  private val imageFallbackCounters =
    new java.util.concurrent.ConcurrentHashMap[String, Counter]()

  /** `dosegram_image_text_fallback_total{template}` (VISUALS VA-FB-01): images sent as their text equivalent because
    * they could not be drawn, or not in time; the template identifier is the only label.
    */
  def imageTextFallback(templateId: String): Unit =
    imageFallbackCounters
      .computeIfAbsent(
        templateId,
        t => Counter.builder("dosegram_image_text_fallback_total").tag("template", t).register(registry)
      )
      .increment()

  /** `dosegram_unknown_total{reason}` (DESIGN.md section 10, ROADMAP M1.8): occurrences marked `unknown`, tagged by
    * reason (`outage` when no worker was healthy across the due window, `undelivered` when workers were healthy but no
    * reminder was confirmed sent); incremented by the reminder loop's catch-up collapse and the materialiser's outage
    * inserts.
    */
  def unknownMarked(reason: String): Unit =
    unknownCounters
      .computeIfAbsent(
        reason,
        r => Counter.builder("dosegram_unknown_total").tag("reason", r).register(registry)
      )
      .increment()

  /** `dosegram_outbox_dead_total{vendor,reason}` (DESIGN.md section 10, ROADMAP M1.7): terminal `dead` outbox rows
    * (attempt budget spent or channelFatal); the owner alert of OPS-A2 reads the rows themselves.
    */
  def outboxDead(vendor: String, reason: OutboxReason): Unit = outboxCounter("dead", vendor, reason.code)

  /** `dosegram_outbox_sent_total{vendor,kind}`: delivered outbox rows by their fixed kind. */
  def outboxSent(vendor: String, kind: String): Unit =
    Counter.builder("dosegram_outbox_sent_total").tag("vendor", vendor).tag("kind", kind).register(registry).increment()

  /** `dosegram_outbox_retried_total{vendor,reason}`: rows put back for another attempt. */
  def outboxRetried(vendor: String, reason: OutboxReason): Unit = outboxCounter("retried", vendor, reason.code)

  /** `dosegram_outbox_failed_total{vendor,reason}`: rows that ended `failed_permanent`. */
  def outboxFailed(vendor: String, reason: OutboxReason): Unit = outboxCounter("failed", vendor, reason.code)

  // Micrometer returns the registered counter for a name and tag set it already holds.
  private def outboxCounter(outcome: String, vendor: String, reason: String): Unit =
    Counter
      .builder(s"dosegram_outbox_${outcome}_total")
      .tag("vendor", vendor)
      .tag("reason", reason)
      .register(registry)
      .increment()

  /** `dosegram_possible_duplicates_total{vendor}` (DESIGN.md section 10, ROADMAP M1.7): sends flagged
    * `possible_duplicate` under ADR-009's bounded-duplicate rule.
    */
  def possibleDuplicate(vendor: String): Unit =
    possibleDuplicateCounters
      .computeIfAbsent(
        vendor,
        v => Counter.builder("dosegram_possible_duplicates_total").tag("vendor", v).register(registry)
      )
      .increment()

  /** `dosegram_tzdb_info{version}` = 1 (DESIGN.md section 10): the runtime IANA tzdb version resolved by [[TzdbGuard]].
    */
  def registerTzdb(version: String): Unit =
    Info
      .builder()
      .name("dosegram_tzdb_info")
      .help("Runtime IANA tzdb version")
      .labelNames("version")
      .register(registry.getPrometheusRegistry)
      .setLabelValues(version)

  /** `dosegram_scheduler_tick_age_seconds{instance}` (DESIGN.md section 10, ROADMAP M1.6): the age of the loop
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
        "dosegram_scheduler_tick_age_seconds",
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

/** The report worker's count of images sent as text (ROADMAP G4.3, VISUALS VA-FB-01). */
object MicrometerImageMetrics:
  def textFallback(templateId: String): Unit = Metrics.imageTextFallback(templateId)

/** The dispatcher's metrics port over the shared registry (ROADMAP M1.7). A retried, failed or dead row is also one log
  * line of fixed words: the vendor, the row's kind and the reason code, never the row's stored error or payload.
  */
final class MicrometerOutboxMetrics(log: String => Unit = line => scribe.warn(line)) extends OutboxMetrics:
  override def sent(vendor: String, kind: String): Unit = Metrics.outboxSent(vendor, kind)

  override def retried(vendor: String, kind: String, reason: OutboxReason): Unit =
    Metrics.outboxRetried(vendor, reason)
    log(s"outbox row retried: vendor=$vendor kind=$kind reason=${reason.code}")

  override def dead(vendor: String, kind: String, reason: OutboxReason): Unit =
    Metrics.outboxDead(vendor, reason)
    log(s"outbox row dead: vendor=$vendor kind=$kind reason=${reason.code}")

  override def failed(vendor: String, kind: String, reason: OutboxReason): Unit =
    Metrics.outboxFailed(vendor, reason)
    log(s"outbox row failed: vendor=$vendor kind=$kind reason=${reason.code}")

  override def possibleDuplicate(vendor: String): Unit = Metrics.possibleDuplicate(vendor)

/** The reminder loop's metrics port over the shared registry (ROADMAP M1.8). */
final class MicrometerLoopMetrics extends LoopMetrics:
  override def unknownMarked(reason: String): Unit = Metrics.unknownMarked(reason)
