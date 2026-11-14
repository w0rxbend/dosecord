package dosegram.adapter.telegram

import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.MediaKind
import dosegram.contracts.MediaRef
import dosegram.contracts.MessageHandle
import dosegram.contracts.StartPayload
import dosegram.core.chat.CallbackPeek

import java.time.Instant
import java.util.Locale
import java.util.UUID

/** The pure mapping of ARCHITECTURE section 7.3: one update becomes at most one inbound event. Only private chats
  * produce events (TG-10). Nothing here trusts the update: a command is only split into a name and its raw text, a
  * start payload is checked against its grammar, and callback data is passed on for the mediator to verify.
  */
private[telegram] object UpdateMapper:

  /** What one update maps to before the adapter adds identifiers, the clock and the live callback handle. */
  final case class Draft(
      chatId: Long,
      userId: Long,
      sentAt: Option[Instant],
      body: Inbound,
      callback: Option[CallbackSource],
      localeHint: Option[String] = None
  )

  final case class CallbackSource(queryId: String, source: Option[MessageHandle])

  enum Mapped:
    case Deliver(draft: Draft)

    /** A callback that cannot become an event. It is still answered, so the client stops its progress indicator. */
    case Dismiss(queryId: String)

    /** `reason` is a fixed label for the log; it never holds content of the update. */
    case Ignore(reason: String)

  /** BOT_UX BUX-DL-01: a `start` payload has at most 24 characters. */
  val MaxStartPayloadChars = 24

  /** The photo size the adapter records (ARCHITECTURE section 7.3). */
  val PreferredPhotoSide = 1280

  private val StartCommand = "start"
  private val MaxCommandChars = 32

  def map(update: Update, botUsername: Option[String]): Mapped =
    update.body match
      case UpdateBody.NewMessage(message)     => mapMessage(message, botUsername)
      case UpdateBody.Callback(query)         => mapCallback(query)
      case UpdateBody.EditedMessage(_)        => Mapped.Ignore("edited_message")
      case UpdateBody.ChatMemberChanged(_, _) => Mapped.Ignore("my_chat_member")
      case UpdateBody.Unknown(_)              => Mapped.Ignore("unknown_kind")

  private def mapMessage(message: TgMessage, botUsername: Option[String]): Mapped =
    (message.from, message.content) match
      case _ if !message.chat.isPrivate            => Mapped.Ignore("not_private")
      case (None, _)                               => Mapped.Ignore("no_sender")
      case (Some(from), MessageContent.Text(text)) =>
        textBody(message, text, botUsername).fold(Mapped.Ignore(_), deliver(message, from, _))
      case (Some(from), MessageContent.Photo(sizes, caption)) =>
        deliver(message, from, Inbound.MediaReceived(MediaKind.Photo, MediaRef(preferredSize(sizes).fileId), caption))
      case (Some(from), MessageContent.Document(fileId, caption)) =>
        deliver(message, from, Inbound.MediaReceived(MediaKind.Document, MediaRef(fileId), caption))
      case (_, MessageContent.WebAppData) => Mapped.Ignore("web_app_data")
      case (_, MessageContent.Other)      => Mapped.Ignore("unsupported_content")

  private def deliver(message: TgMessage, from: TgUser, body: Inbound): Mapped =
    Mapped.Deliver(Draft(message.chat.id, from.id, message.sentAt, body, None, from.locale))

  /** The size whose long side is nearest to [[PreferredPhotoSide]]; a decoded photo always has at least one size. */
  private def preferredSize(sizes: List[TgPhotoSize]): TgPhotoSize =
    sizes.minBy(size => math.abs(size.longSide - PreferredPhotoSide))

  /** A command, a deep link or plain text; `Left` holds the reason an addressed command is not for this bot. */
  private def textBody(message: TgMessage, text: String, botUsername: Option[String]): Either[String, Inbound] =
    val trimmed = text.trim
    parseCommand(trimmed) match
      case Some(command) if !command.addressedTo(botUsername) => Left("command_for_another_bot")
      case Some(command) if command.name == StartCommand      =>
        val payload = StartPayload.parse(command.argument).filter(_.value.length <= MaxStartPayloadChars)
        Right(Inbound.ConversationStarted(payload))
      case Some(command) => Right(Inbound.CommandInvoked(command.name, Map.empty, trimmed))
      case None          =>
        val replyTo = message.replyTo.map(MessageIds.handle(message.chat.id, _))
        Right(Inbound.MessageReceived(trimmed, replyTo, truncated = false))

  private final case class Command(name: String, mention: Option[String], argument: String):
    /** BOT_UX BUX-CMD-01: a `@name` suffix is accepted when it is this bot's; matching ignores case. */
    def addressedTo(botUsername: Option[String]): Boolean =
      (mention, botUsername) match
        case (Some(named), Some(own)) => named.equalsIgnoreCase(own)
        case _                        => true

  /** `/name[@bot] [argument]`. The name is lower-cased; text that only looks like a command stays text. */
  private def parseCommand(text: String): Option[Command] =
    if !text.startsWith("/") then None
    else
      val (head, rest) = text.drop(1).span(c => !c.isWhitespace)
      val (name, mention) = head.span(_ != '@')
      val lowered = name.toLowerCase(Locale.ROOT)
      Option.when(isCommandName(lowered))(Command(lowered, Option(mention.drop(1)).filter(_.nonEmpty), rest.trim))

  private def isCommandName(name: String): Boolean =
    name.nonEmpty && name.length <= MaxCommandChars &&
      name.forall(c => (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')

  private def mapCallback(query: TgCallbackQuery): Mapped =
    (query.message, query.data.filter(_.nonEmpty)) match
      case (Some(message), Some(data)) if message.chat.isPrivate =>
        val source = CallbackSource(query.id, Some(MessageIds.handle(message.chat.id, message.ref)))
        val body = Inbound.InteractionSubmitted(callbackRef(data), source.source)
        Mapped.Deliver(Draft(message.chat.id, query.from.id, None, body, Some(source), query.from.locale))
      case _ => Mapped.Dismiss(query.id)

  /** The clear-text fields of the token. Data that is not a token of ours keeps its raw form under an action no handler
    * has, so the mediator's verification rejects it with the stale-button toast and nothing reaches the core.
    */
  private def callbackRef(data: String): CallbackRef =
    CallbackPeek.decode(data) match
      case Some(peeked) => CallbackRef(peeked.actionId, peeked.subject, peeked.value, peeked.slot, data)
      case None         => CallbackRef(UnknownAction, UUID(0L, 0L), 0L, slot = false, data)

  private val UnknownAction = 0
end UpdateMapper
