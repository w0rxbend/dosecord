package dosegram.app

import dosegram.api.ApiModule
import dosegram.api.TrackingUseCases
import dosegram.api.auth.Ed25519InitDataCheck
import dosegram.api.auth.InitDataHmac
import dosegram.api.auth.InitDataVerifier
import dosegram.api.auth.TelegramInitDataVerifier
import dosegram.infra.MiniAppSettings
import dosegram.infra.Settings
import dosegram.infra.db.PgIdempotencyKeys
import dosegram.infra.db.PgMiniAppSessions
import dosegram.infra.db.PgUnitOfWork
import ox.Ox
import ox.fork
import sttp.model.Header
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestInterceptor
import sttp.tapir.server.interceptor.RequestInterceptor.RequestResultTransform
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import sttp.tapir.server.netty.sync.NettySyncServerOptions

import java.nio.file.Path
import java.time.Duration
import java.util.HexFormat
import javax.sql.DataSource

/** The public origin of ARCHITECTURE section 9.1: the Mini App under `/app/` and its API under `/api/v1/`, on one
  * listener of their own. It exists only when `MINIAPP_ENABLED=true` (ROADMAP G1.12); `/healthz` stays on the internal
  * port. The headers are set here, by the application, so a test covers them (section 9.8).
  */
object MiniAppEdge:

  /** Research brief 06 section 12.4. `X-Frame-Options` is absent on purpose: Telegram Web frames the app, and
    * `frame-ancestors` is the control. No CORS header is sent: the app and the API share the origin.
    */
  val SecurityHeaders: List[Header] = List(
    Header(
      "Content-Security-Policy",
      List(
        "default-src 'none'",
        "script-src 'self' https://telegram.org",
        "style-src 'self'",
        "img-src 'self' blob: data:",
        "font-src 'self'",
        "connect-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        "object-src 'none'",
        "frame-ancestors https://web.telegram.org",
        "require-trusted-types-for 'script'"
      ).mkString("; ")
    ),
    Header("Strict-Transport-Security", "max-age=31536000"),
    Header("X-Content-Type-Options", "nosniff"),
    Header("Referrer-Policy", "no-referrer"),
    Header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"),
    Header("Cross-Origin-Resource-Policy", "same-origin")
  )

  private val NoStore = Header("Cache-Control", "no-store")
  private val SweepPeriod = Duration.ofMinutes(1)

  /** Starts the listener and the fork that deletes expired sessions and idempotency records. `extra` endpoints share
    * the listener: the Telegram webhook in webhook mode (ROADMAP G1.7).
    */
  def start(
      settings: Settings,
      miniApp: MiniAppSettings,
      dataSource: DataSource,
      parts: Launch.Parts,
      log: String => Unit,
      extra: List[ServerEndpoint[Any, Identity]] = Nil
  )(using
      Ox
  ): NettySyncServerBinding =
    val uow = PgUnitOfWork(dataSource, parts.clock)
    val tracking = TrackingServices.over(uow, parts.clock)
    val api = ApiModule(
      uow,
      parts.clock,
      verifier(settings, miniApp),
      PgSessionStore(PgMiniAppSessions(dataSource)),
      PgIdempotencyStore(PgIdempotencyKeys(dataSource), parts.clock),
      TrackingUseCases(tracking.stats, tracking.hydration, tracking.mood, tracking.sleep, tracking.nutrition),
      MoodBlobAsset.states,
      log
    )
    fork:
      WorkerLoop
        .standard(log)
        .repeat("mini app sweep"): () =>
          Thread.sleep(SweepPeriod.toMillis)
          api.sweep()
          true
    val static = miniApp.staticDir.map(dir => StaticApp.load(Path.of(dir))).getOrElse(StaticApp.absent)
    // Requests are not logged: a line would hold the path, and a path can hold a record id (AGENTS.md).
    val options = NettySyncServerOptions.customiseInterceptors
      .prependInterceptor(headers)
      .decodeFailureHandler(ApiModule.decodeFailureHandler)
      .serverLog(None)
      .options
    NettySyncServer(options, NettyConfig.default.copy(serverHeader = None))
      .host(settings.httpAddress)
      .port(settings.httpPort)
      .addEndpoints(extra ++ api.endpoints :+ static.endpoint :+ elsewhere)
      .start()

  /** The launch-data checks of ARCHITECTURE API-02 and API-03. The HMAC key is derived here, so the `api` module never
    * holds the bot token (DEP-05).
    */
  private def verifier(settings: Settings, miniApp: MiniAppSettings): InitDataVerifier =
    val publicKey =
      miniApp.initDataPublicKey.map(HexFormat.of().parseHex).getOrElse(Ed25519InitDataCheck.TelegramProductionKey)
    val hmacSecret =
      for token <- settings.telegramToken if miniApp.hmacFallback
      yield InitDataHmac.secretKeyFor(token.value)
    TelegramInitDataVerifier(miniApp.botId.map(Ed25519InitDataCheck(_, publicKey)), hmacSecret)

  /** Any path outside `/app/` and `/api/` answers 404 through an endpoint, so it carries the headers too. */
  private val elsewhere: ServerEndpoint[Any, Identity] =
    endpoint.in(paths).errorOut(statusCode).handleError(_ => StatusCode.NotFound)

  /** Adds the header set to every answer, and `no-store` to every `/api/` answer (section 9.1). */
  private val headers: RequestInterceptor[Identity] = RequestInterceptor.transformResult(
    new RequestResultTransform[Identity]:
      override def apply[B](request: ServerRequest, result: RequestResult[B]): RequestResult[B] = result match
        case RequestResult.Response(response, source) =>
          val forApi = if request.pathSegments.headOption.contains("api") then List(NoStore) else Nil
          RequestResult.Response(response.addHeaders(SecurityHeaders ++ forApi), source)
        case failure: RequestResult.Failure => failure
  )
