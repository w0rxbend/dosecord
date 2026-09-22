package dosecord.infra

import dosecord.core.ports.Clock
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.core.metrics.Info

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Micrometer registry stub (ROADMAP M0.11): the one Prometheus meter registry of the process. M2.4 exposes it on
  * `/metrics` and registers the core metric set of DESIGN.md section 10; only the tzdb info metric and the reminder
  * loop's tick age exist now.
  */
object Metrics:
  val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

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
