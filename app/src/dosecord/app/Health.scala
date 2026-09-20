package dosecord.app

import ox.Ox
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerBinding

/** Liveness endpoint (DESIGN.md section 10): Tapir `tapir-netty-server-sync`, direct style. `/readyz` arrives with
  * M2.4.
  */
object Health:
  val DefaultPort = 8080

  val healthz = endpoint.get.in("healthz").out(stringBody)

  def start(port: Int = DefaultPort)(using Ox): NettySyncServerBinding =
    NettySyncServer().host("0.0.0.0").port(port).addEndpoint(healthz.handleSuccess(_ => "ok")).start()
