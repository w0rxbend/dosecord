package dosegram.adapter.telegram

import java.time.Duration

/** The bot token (ARCHITECTURE TG-02). It is part of every request URL, so its text is readable only inside this
  * package and never appears in a `toString`, a log line or an exception message.
  */
final class BotToken(private[telegram] val secret: String):
  // The message never quotes the value: it may be a mistyped but real token.
  require(BotToken.isWellFormed(secret), "bot token is not of the form <digits>:<letters, digits, _ or ->")

  override def toString: String = "BotToken(***)"

object BotToken:
  /** `<bot id>:<secret>`, URL-safe characters only, so a request URL built from it is always a legal URI. */
  def isWellFormed(text: String): Boolean =
    text.split(":", -1) match
      case Array(id, secret) =>
        id.nonEmpty && id.forall(c => c >= '0' && c <= '9') && secret.nonEmpty &&
        secret.forall(c => c.isLetterOrDigit && c < 128 || c == '_' || c == '-')
      case _ => false

/** One file part of a multipart upload. A plain class: the bytes take no part in equality or in a `toString`. */
final class WireFile(val field: String, val fileName: String, val mimeType: String, val bytes: Array[Byte])

/** One Bot API call as the transport sends it: the method name, its JSON parameters and, for an upload, one file. */
final case class WireRequest(method: String, params: ujson.Obj, file: Option[WireFile], timeout: Duration)

/** Why a call produced no HTTP response. It carries no text: an exception message of the HTTP client may contain the
  * request URL, and the URL contains the token.
  */
enum WireFailure:
  case Timeout, Connection

enum WireReply:
  case Http(status: Int, body: String)
  case Failed(failure: WireFailure)

/** The transport seam of ARCHITECTURE section 7.1: everything above it is pure and runs against
  * [[FakeTelegramTransport]]; [[HttpTelegramTransport]] is the only implementation that touches the network.
  */
trait TelegramTransport:
  def call(request: WireRequest): WireReply
