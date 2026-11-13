package dosegram.app

import dosegram.adapter.telegram.TelegramAdapter
import dosegram.adapter.telegram.WebhookConfig
import dosegram.adapter.telegram.WebhookHandler
import dosegram.infra.TelegramWebhookSettings
import ox.Ox
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.model.EndpointExtensions.*
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import sttp.tapir.server.netty.sync.NettySyncServerOptions

/** `POST /tg/webhook/{path_secret}` on the HTTP edge (ARCHITECTURE section 7.2.1, ROADMAP G1.7). The secrets are the
  * security input, so a request whose path or header secret is wrong is answered 404 before its body is read; the body
  * is limited to 1 MiB; 200 is returned only once the update is stored (TG-03). Requests are not logged: the path holds
  * a secret.
  */
object WebhookEdge:

  val SecretHeader = "X-Telegram-Bot-Api-Secret-Token"

  def handler(webhook: TelegramWebhookSettings, adapter: TelegramAdapter): WebhookHandler =
    WebhookHandler(
      WebhookConfig(webhook.pathSecret, List(webhook.secretToken), WebhookConfig.DefaultMaxBodyBytes),
      adapter.receive
    )

  def endpoint(handler: WebhookHandler, maxBodyBytes: Long): ServerEndpoint[Any, Identity] =
    sttp.tapir.endpoint.post
      .securityIn("tg" / "webhook" / path[String]("secret"))
      .securityIn(header[Option[String]](SecretHeader))
      .in(header[Option[Long]]("Content-Length"))
      .in(stringBody)
      .out(statusCode)
      .errorOut(statusCode)
      .handleSecurity((secret, presented) => Either.cond(handler.authentic(secret, presented), (), StatusCode.NotFound))
      .handle(_ => (length, body) => Right(StatusCode(handler.receive(length, () => body).status)))
      .maxRequestBodyLength(maxBodyBytes)

  /** The listener when the Mini App is off: the webhook alone; any other path is 404. */
  def start(address: String, port: Int, endpoint: ServerEndpoint[Any, Identity])(using Ox): NettySyncServerBinding =
    val options = NettySyncServerOptions.customiseInterceptors.serverLog(None).options
    NettySyncServer(options, NettyConfig.default.copy(serverHeader = None))
      .host(address)
      .port(port)
      .addEndpoint(endpoint)
      .start()
