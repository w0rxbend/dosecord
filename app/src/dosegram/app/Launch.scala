package dosegram.app

import dosegram.contracts.ChatAdapter
import dosegram.core.ports.Clock
import dosegram.infra.Role
import dosegram.infra.Settings
import ox.Ox

import javax.sql.DataSource

/** The assembled application (docs/dosegram/ROADMAP.md G0.5): what `dosegram run` starts inside the root scope. The
  * launch-path tests start it through the same call, so no worker is started by a test.
  */
object Launch:

  /** What the process is assembled from besides its settings and its pool. Production takes all of it from
    * [[Parts.production]]; a launch-path test passes its own clock, its own console streams and a shorter cadence, and
    * its own images when it looks at them.
    */
  final class Parts(
      val clock: Clock,
      val adapters: Map[String, ChatAdapter],
      val cadence: Workers.Cadence,
      val images: StatusImages
  ):
    /** Without images: every attachment is its text equivalent. */
    def this(clock: Clock, adapters: Map[String, ChatAdapter], cadence: Workers.Cadence) =
      this(clock, adapters, cadence, StatusImages.textOnly)

  object Parts:
    def production(settings: Settings, dataSource: DataSource)(using Ox): Parts =
      // The file ids belong to the one bot of the process; without that adapter nothing asks for one.
      val botId = settings.telegram.map(_.botId).getOrElse(0L)
      val stores = StatusImages.Stores.durable(dataSource, botId, Clock.system)
      val images = StatusImages.from(settings.render, stores, line => scribe.info(line))
      Parts(Clock.system, Adapters.build(settings, Clock.system, images), Workers.Cadence.Production, images)

  /** Starts, in the order of DESIGN.md section 12, the workers, then the adapters, then the HTTP edge, each only when
    * the role hosts it, and returns the drain that stops them on SIGTERM.
    */
  def start(settings: Settings, dataSource: DataSource, parts: Parts, log: String => Unit)(using Ox): Drain =
    val inFlight = InFlightRegistry()
    val gate = ClaimGate(inFlight)
    if hostsWorkers(settings.role) then Workers(settings, dataSource, parts).start(gate, log)
    val adapters = if hostsGateway(settings.role) then Adapters.start(settings, dataSource, parts, log) else Nil
    val miniApp = startMiniApp(settings, dataSource, parts, log)
    val health = Health.start(settings.healthPort)
    log(s"/healthz listening on :${settings.healthPort}")
    Drain(
      inbound = ("health" -> (() => health.stop())) :: miniApp ::: adapters,
      claiming = List("workers" -> (() => gate.stop())),
      inFlight = inFlight,
      budget = Shutdown.DrainBudget,
      log = log
    )

  /** The public origin (`/app/`, `/api/v1/`) is part of the gateway half and exists only behind `MINIAPP_ENABLED`
    * (ROADMAP G1.12); with the switch off no listener is opened.
    */
  private def startMiniApp(settings: Settings, dataSource: DataSource, parts: Parts, log: String => Unit)(using
      Ox
  ): List[(String, () => Unit)] =
    settings.miniApp
      .filter(_ => hostsGateway(settings.role))
      .toList
      .map: miniApp =>
        val edge = MiniAppEdge.start(settings, miniApp, dataSource, parts, log)
        log(s"Mini App and API listening on :${settings.httpPort}")
        "miniapp" -> (() => edge.stop())

  // The role policy of docs/dosegram/ARCHITECTURE.md section 4.1. `/healthz` is served under every role: it is the
  // liveness probe of the container, whichever half the process hosts.
  private def hostsWorkers(role: Role): Boolean = role match
    case Role.Worker | Role.All => true
    case Role.Gateway           => false

  private def hostsGateway(role: Role): Boolean = role match
    case Role.Gateway | Role.All => true
    case Role.Worker             => false
