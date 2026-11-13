package dosegram.adapter.telegram

import dosegram.contracts.ChatError

import java.time.Duration

/** The closed set of Bot API failures (ARCHITECTURE section 7.10). No case carries the description text: a description
  * can quote the request ("can't parse entities: ... tag ..."), and message text must not reach a log line or an
  * exception message (TG-02).
  */
enum TelegramError:
  /** 429: flood control, with the wait the API asked for. */
  case RateLimited(retryAfter: Duration)

  /** The user blocked the bot, deactivated the account or never started the chat. */
  case Unreachable

  /** An edit that changes nothing. It is a success (TG-22). */
  case NotModified

  /** The message to edit or delete is gone, or is not editable. */
  case MissingTarget

  /** A delete outside the 48 hour window. */
  case NotDeletable

  /** A `file_id` the API does not accept any more. */
  case BadMedia

  /** `answerCallbackQuery` for a query that is too old. It is a success: nothing can be shown any more. */
  case CallbackExpired

  /** Any other 400: a defect in our request. */
  case BadRequest

  /** 401 or 404: the token is wrong or revoked. */
  case TokenInvalid

  /** 409 on `getUpdates`: a second consumer of the same token exists. */
  case Conflict

  /** 5xx, a timeout or a connection failure. */
  case Unavailable

object TelegramErrorMapper:

  /** Used when a 429 carries no `retry_after` (that every flood rejection carries one is an assumption). */
  val DefaultRetryAfter: Duration = Duration.ofSeconds(5)

  private val unreachable = List("chat not found")
  private val notModified = List("message is not modified")
  private val missingTarget =
    List("message to edit not found", "message can't be edited", "message to delete not found", "message_id_invalid")
  private val notDeletable = List("message can't be deleted")
  private val badMedia = List("wrong file identifier", "wrong remote file identifier", "file reference expired")
  private val callbackExpired = List("query is too old", "query id is invalid")

  /** Maps one failed call to exactly one case. `description` is matched and then dropped. The strings are the widely
    * reported ones and stay assumptions until a live spike records the real responses (ARCHITECTURE section 7.10).
    */
  def classify(status: Int, description: String, retryAfterSeconds: Option[Long]): TelegramError =
    val text = description.toLowerCase
    def mentions(phrases: List[String]): Boolean = phrases.exists(text.contains)
    status match
      case 429                              => TelegramError.RateLimited(retryAfter(retryAfterSeconds))
      case 403                              => TelegramError.Unreachable
      case 401 | 404                        => TelegramError.TokenInvalid
      case 409                              => TelegramError.Conflict
      case 400 if mentions(notModified)     => TelegramError.NotModified
      case 400 if mentions(missingTarget)   => TelegramError.MissingTarget
      case 400 if mentions(notDeletable)    => TelegramError.NotDeletable
      case 400 if mentions(callbackExpired) => TelegramError.CallbackExpired
      case 400 if mentions(badMedia)        => TelegramError.BadMedia
      case 400 if mentions(unreachable)     => TelegramError.Unreachable
      case 400                              => TelegramError.BadRequest
      case _                                => TelegramError.Unavailable

  private def retryAfter(seconds: Option[Long]): Duration =
    seconds.filter(_ > 0).map(Duration.ofSeconds).getOrElse(DefaultRetryAfter)

  /** The `ChatError` the core sees for a failed `op`. The two success-equivalent cases have none: the caller treats
    * them as success before it gets here. A rejected token is `Retryable` for a send: the row backs off and nothing is
    * marked against the user's channel; the long-poll reader stops on it by itself.
    */
  def toChatError(error: TelegramError, op: String): Option[ChatError] =
    error match
      case TelegramError.NotModified | TelegramError.CallbackExpired => None
      case TelegramError.RateLimited(retryAfter)                     => Some(ChatError.RateLimited(retryAfter))
      case TelegramError.Unreachable   => Some(ChatError.Unreachable(s"telegram $op: recipient unreachable"))
      case TelegramError.MissingTarget =>
        Some(
          ChatError.Permanent(
            s"telegram $op: target message missing",
            channelFatal = false,
            ChatError.PermanentReason.MissingTarget
          )
        )
      case TelegramError.NotDeletable =>
        Some(ChatError.Permanent(s"telegram $op: message not deletable", channelFatal = false))
      case TelegramError.BadMedia   => Some(ChatError.Permanent(s"telegram $op: media rejected", channelFatal = false))
      case TelegramError.BadRequest =>
        Some(ChatError.Permanent(s"telegram $op: request rejected", channelFatal = false))
      case TelegramError.TokenInvalid => Some(ChatError.Retryable(s"telegram $op: token rejected"))
      case TelegramError.Conflict     => Some(ChatError.Retryable(s"telegram $op: conflicting consumer"))
      case TelegramError.Unavailable  => Some(ChatError.Retryable(s"telegram $op: service unavailable"))
end TelegramErrorMapper
