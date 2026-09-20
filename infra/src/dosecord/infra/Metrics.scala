package dosecord.infra

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.core.metrics.Info

/** Micrometer registry stub (ROADMAP M0.11): the one Prometheus meter registry of the process. M2.4 exposes it on
  * `/metrics` and registers the core metric set of DESIGN.md section 10; only the tzdb info metric exists now.
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

  def scrape(): String = registry.scrape()
