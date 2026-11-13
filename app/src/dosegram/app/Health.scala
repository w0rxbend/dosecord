package dosegram.app

import ox.Ox
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerBinding

/** Liveness endpoint (DESIGN.md section 10): Tapir `tapir-netty-server-sync`, direct style. `/readyz` arrives with
  * M2.4. It binds to `HEALTH_ADDRESS`, the loopback interface by default: operations endpoints are not routed by the
  * proxy (ARCHITECTURE section 9.1).
  */
object Health:
  val healthz = endpoint.get.in("healthz").out(stringBody)

  def start(address: String, port: Int)(using Ox): NettySyncServerBinding =
    NettySyncServer().host(address).port(port).addEndpoint(healthz.handleSuccess(_ => "ok")).start()
