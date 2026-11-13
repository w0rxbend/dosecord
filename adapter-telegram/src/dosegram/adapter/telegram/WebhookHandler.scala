package dosegram.adapter.telegram

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** One webhook delivery as the HTTP edge hands it over. `body` is read only after the secrets matched. */
final case class WebhookRequest(
    pathSecret: String,
    headerSecret: Option[String],
    contentLength: Option[Long],
    body: () => String
)

enum WebhookResponse(val status: Int):
  /** The update is stored, or it is a body that will never decode: Telegram must not send it again. */
  case Accepted extends WebhookResponse(200)

  /** A wrong path or header secret. Nothing was read. */
  case NotFound extends WebhookResponse(404)
  case TooLarge extends WebhookResponse(413)

  /** The update could not be stored; Telegram retries any answer that is not 2xx. */
  case Unavailable extends WebhookResponse(503)

/** `headerSecrets` holds one value, or two for the ten minutes of a rotation (ARCHITECTURE section 7.2.1). */
final case class WebhookConfig(pathSecret: String, headerSecrets: List[String], maxBodyBytes: Long):
  require(pathSecret.nonEmpty, "the webhook path secret must not be empty")
  require(headerSecrets.nonEmpty && headerSecrets.forall(_.nonEmpty), "at least one webhook header secret")
  require(maxBodyBytes > 0, "the webhook body limit must be positive")

object WebhookConfig:
  /** The body limit of ARCHITECTURE section 7.2.1; the real maximum of an update is not documented. */
  val DefaultMaxBodyBytes: Long = 1024L * 1024L

/** The webhook endpoint as a function of the request (ARCHITECTURE section 7.2.1, steps 2 to 7), ready to be mounted at
  * `POST /tg/webhook/{path_secret}`. `store` is the durable write of the update and answers whether it is stored: 200
  * is returned only then (TG-03). The handler logs nothing and never reports why a request was refused.
  */
final class WebhookHandler(config: WebhookConfig, store: Update => Boolean):

  def handle(request: WebhookRequest): WebhookResponse =
    if !authentic(request) then WebhookResponse.NotFound
    else if request.contentLength.exists(_ > config.maxBodyBytes) then WebhookResponse.TooLarge
    else
      val body = request.body()
      if body.getBytes(UTF_8).length > config.maxBodyBytes then WebhookResponse.TooLarge
      else decode(body).fold(WebhookResponse.Accepted)(accept)

  private def accept(update: Update): WebhookResponse =
    if store(update) then WebhookResponse.Accepted else WebhookResponse.Unavailable

  /** Both secrets are always compared, each in constant time, so a response time tells nothing about which was wrong.
    */
  private def authentic(request: WebhookRequest): Boolean =
    val pathMatches = sameText(request.pathSecret, config.pathSecret)
    val presented = request.headerSecret.getOrElse("")
    val headerMatches = config.headerSecrets.map(sameText(presented, _)).contains(true)
    pathMatches && headerMatches

  private def sameText(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.getBytes(UTF_8), right.getBytes(UTF_8))

  /** A body that is not an update is acknowledged and dropped: sending it again would not make it decode. */
  private def decode(body: String): Option[Update] =
    val json =
      try Some(ujson.read(body, trace = false))
      catch case _: ujson.ParsingFailedException => None
    json.flatMap(TelegramModels.decodeUpdate)
