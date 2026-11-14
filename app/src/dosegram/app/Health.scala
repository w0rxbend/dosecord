package dosegram.app

import dosegram.infra.Metrics
import ox.Ox
import sttp.model.Header
import sttp.model.MediaType
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerBinding

/** The operations listener (DESIGN.md section 10, ARCHITECTURE section 18): `/healthz` (liveness), `/readyz`
  * (readiness, [[Readiness]]) and `/metrics` (the Prometheus exposition of the one registry). Tapir
  * `tapir-netty-server-sync`, direct style. It binds to `HEALTH_ADDRESS`, the loopback interface by default: operations
  * endpoints are not routed by the proxy (ARCHITECTURE section 9.1).
  */
object Health:
  val healthz = endpoint.get.in("healthz").out(stringBody)

  val readyz = endpoint.get
    .in("readyz")
    .out(statusCode)
    .out(header(Header.contentType(MediaType.ApplicationJson)))
    .out(stringBody)

  val metrics = endpoint.get
    .in("metrics")
    .out(header(Header.contentType(MediaType.TextPlain.charset("utf-8"))))
    .out(stringBody)

  def start(address: String, port: Int, readiness: () => Readiness.Report)(using Ox): NettySyncServerBinding =
    NettySyncServer()
      .host(address)
      .port(port)
      .addEndpoint(healthz.handleSuccess(_ => "ok"))
      .addEndpoint(readyz.handleSuccess: _ =>
        val report = readiness()
        (if report.ready then StatusCode.Ok else StatusCode.ServiceUnavailable, report.json))
      .addEndpoint(metrics.handleSuccess(_ => Metrics.scrape()))
      .start()
