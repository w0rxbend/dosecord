package dosegram.app

import dosegram.adapter.telegram.TelegramAdapter
import dosegram.adapter.telegram.WebhookConfig
import dosegram.contracts.ChatAdapter
import dosegram.core.ports.Clock
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgOpsHealth
import dosegram.infra.db.PgUnitOfWork
import ox.Ox
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

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
      val durable = StatusImages.Stores.durable(dataSource, botId, Clock.system)
      // The adapters also send the files of the exports (ROADMAP G10.4), from the same store.
      val exportFiles = ExportAssetStore(PgUnitOfWork(dataSource, Clock.system), Clock.system, durable.assets)
      val stores = durable.copy(assets = exportFiles)
      val images = StatusImages.from(settings.render, stores, line => scribe.info(line))
      val adapters = Adapters.build(settings, Clock.system, images, PgInboundInbox(dataSource))
      Parts(Clock.system, adapters, Workers.Cadence.Production, images)

  /** Starts, in the order of DESIGN.md section 12, the workers, then the adapters, then the HTTP edge, each only when
    * the role hosts it, and returns the drain that stops them on SIGTERM.
    */
  def start(settings: Settings, dataSource: DataSource, parts: Parts, log: String => Unit)(using Ox): Drain =
    val inFlight = InFlightRegistry()
    val gate = ClaimGate(inFlight)
    if hostsWorkers(settings.role) then Workers(settings, dataSource, parts).start(gate, log)
    val adapters = if hostsGateway(settings.role) then Adapters.start(settings, dataSource, parts, log) else Nil
    val edge = startHttpEdge(settings, dataSource, parts, log)
    val probes = PgOpsHealth(dataSource)
    val health = Health.start(settings.healthAddress, settings.healthPort, () => Readiness.check(probes, parts.clock))
    log(s"/healthz, /readyz and /metrics listening on ${settings.healthAddress}:${settings.healthPort}")
    Drain(
      inbound = ("health" -> (() => health.stop())) :: edge ::: adapters,
      claiming = List("workers" -> (() => gate.stop())),
      inFlight = inFlight,
      budget = Shutdown.DrainBudget,
      log = log
    )

  /** The public origin is part of the gateway half: the Mini App (`/app/`, `/api/v1/`) behind `MINIAPP_ENABLED`
    * (ROADMAP G1.12) and the Telegram webhook in webhook mode (G1.7). With neither, no listener is opened.
    */
  private def startHttpEdge(settings: Settings, dataSource: DataSource, parts: Parts, log: String => Unit)(using
      Ox
  ): List[(String, () => Unit)] =
    val webhook = if hostsGateway(settings.role) then webhookEndpoint(settings, parts) else None
    settings.miniApp.filter(_ => hostsGateway(settings.role)) match
      case Some(miniApp) =>
        val edge = MiniAppEdge.start(settings, miniApp, dataSource, parts, log, webhook.toList)
        log(s"Mini App and API listening on ${settings.httpAddress}:${settings.httpPort}")
        List("miniapp" -> (() => edge.stop()))
      case None =>
        webhook.toList.map: endpoint =>
          val edge = WebhookEdge.start(settings.httpAddress, settings.httpPort, endpoint)
          log(s"Telegram webhook listening on ${settings.httpAddress}:${settings.httpPort}")
          "webhook" -> (() => edge.stop())

  /** The webhook endpoint, when the Telegram adapter receives its updates by webhook. */
  private def webhookEndpoint(settings: Settings, parts: Parts): Option[ServerEndpoint[Any, Identity]] =
    for
      webhook <- settings.telegram.flatMap(_.webhook)
      adapter <- parts.adapters.values.collectFirst { case telegram: TelegramAdapter => telegram }
    yield WebhookEdge.endpoint(WebhookEdge.handler(webhook, adapter), WebhookConfig.DefaultMaxBodyBytes)

  // The role policy of docs/dosegram/ARCHITECTURE.md section 4.1. `/healthz` is served under every role: it is the
  // liveness probe of the container, whichever half the process hosts.
  private def hostsWorkers(role: Role): Boolean = role match
    case Role.Worker | Role.All => true
    case Role.Gateway           => false

  private def hostsGateway(role: Role): Boolean = role match
    case Role.Gateway | Role.All => true
    case Role.Worker             => false
