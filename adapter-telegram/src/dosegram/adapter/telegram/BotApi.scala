package dosegram.adapter.telegram

import dosegram.contracts.MediaKind

import java.time.Duration

/** One button of an inline keyboard. Exactly one action per button (research brief 02, section 7.1). */
enum InlineButton:
  case Callback(text: String, data: String, style: Option[String])
  case WebApp(text: String, url: String)
  case Url(text: String, url: String)

final case class InlineKeyboard(rows: List[List[InlineButton]]):
  def isEmpty: Boolean = rows.isEmpty

object InlineKeyboard:
  val empty: InlineKeyboard = InlineKeyboard(Nil)

/** A message of the bot, addressed the way every edit and delete method takes it. */
final case class MessageTarget(chatId: Long, messageId: Long)

/** The chat statuses the bot shows (`sendChatAction`); only the photo upload is used, while an image is drawn. */
enum ChatAction(val wire: String):
  case UploadPhoto extends ChatAction("upload_photo")

/** How a message is delivered: silently, and with link previews switched off. */
final case class Delivery(silent: Boolean, noPreview: Boolean)

final case class OutgoingText(chatId: Long, html: String, keyboard: InlineKeyboard, delivery: Delivery)

/** The media of a send: a `file_id` the bot already holds, or bytes to upload. */
enum MediaPayload:
  case Cached(fileId: String)
  case Upload(fileName: String, mimeType: String, bytes: Array[Byte])

final case class OutgoingMedia(
    chatId: Long,
    kind: MediaKind,
    payload: MediaPayload,
    captionHtml: Option[String],
    keyboard: InlineKeyboard,
    delivery: Delivery,
    hasSpoiler: Boolean = false
)

enum MenuButton:
  case Commands
  case WebApp(text: String, url: String)

/** The typed Bot API client (ROADMAP G1.4): one method per Bot API method in use, each returning the decoded result or
  * exactly one [[TelegramError]]. It builds requests and reads responses; it does not pace, retry or log.
  */
final class BotApi(transport: TelegramTransport, callTimeout: Duration = BotApi.CallTimeout):

  import BotApi.*

  def getMe(): Either[TelegramError, BotIdentity] =
    call("getMe", ujson.Obj()).flatMap(decoded(TelegramModels.decodeIdentity))

  /** Long polling (research brief 02, section 4.2). `offset` confirms every update below it. */
  def getUpdates(offset: Option[Long], pollTimeout: Duration): Either[TelegramError, List[Update]] =
    val params = ujson.Obj(
      "timeout" -> pollTimeout.toSeconds.toDouble,
      "allowed_updates" -> ujson.Arr.from(AllowedUpdates)
    )
    offset.foreach(value => params("offset") = value.toDouble)
    send(WireRequest("getUpdates", params, None, pollTimeout.plus(callTimeout)))
      .map(_.arrOpt.toList.flatten.flatMap(TelegramModels.decodeUpdate))

  def deleteWebhook(): Either[TelegramError, Unit] = call("deleteWebhook", ujson.Obj()).map(_ => ())

  /** Registers the webhook (ARCHITECTURE section 7.2.1) for the same update kinds that long polling asks for. */
  def setWebhook(registration: WebhookRegistration): Either[TelegramError, Unit] =
    val params = ujson.Obj(
      "url" -> registration.url,
      "secret_token" -> registration.secretToken,
      "allowed_updates" -> ujson.Arr.from(AllowedUpdates),
      "max_connections" -> registration.maxConnections
    )
    call("setWebhook", params).map(_ => ())

  def sendMessage(message: OutgoingText): Either[TelegramError, SentMessage] =
    val params = ujson.Obj("chat_id" -> message.chatId.toDouble, "text" -> message.html, "parse_mode" -> ParseMode)
    applyDelivery(params, message.delivery)
    if message.delivery.noPreview then params("link_preview_options") = ujson.Obj("is_disabled" -> true)
    applyKeyboard(params, message.keyboard)
    call("sendMessage", params).flatMap(decoded(TelegramModels.decodeSent))

  /** `sendPhoto` or `sendDocument`; an upload goes as multipart, a cached `file_id` as plain JSON (TG-35, TG-36). */
  def sendMedia(media: OutgoingMedia): Either[TelegramError, SentMessage] =
    val (method, fieldName) = media.kind match
      case MediaKind.Photo    => ("sendPhoto", "photo")
      case MediaKind.Document => ("sendDocument", "document")
    val params = ujson.Obj("chat_id" -> media.chatId.toDouble)
    media.captionHtml.foreach: caption =>
      params("caption") = caption
      params("parse_mode") = ParseMode
    applyDelivery(params, media.delivery)
    applyKeyboard(params, media.keyboard)
    // Only a photo can be blurred; a document has no spoiler.
    if media.hasSpoiler && media.kind == MediaKind.Photo then params("has_spoiler") = true
    val file = media.payload match
      case MediaPayload.Cached(fileId) =>
        params(fieldName) = fileId
        None
      case MediaPayload.Upload(fileName, mimeType, bytes) => Some(WireFile(fieldName, fileName, mimeType, bytes))
    send(WireRequest(method, params, file, if file.isDefined then UploadTimeout else callTimeout))
      .flatMap(decoded(TelegramModels.decodeSent))

  def editMessageText(target: MessageTarget, html: String, keyboard: InlineKeyboard): Either[TelegramError, Unit] =
    val params = targetParams(target)
    params("text") = html
    params("parse_mode") = ParseMode
    params("reply_markup") = keyboardJson(keyboard)
    call("editMessageText", params).map(_ => ())

  def editMessageCaption(target: MessageTarget, html: String, keyboard: InlineKeyboard): Either[TelegramError, Unit] =
    val params = targetParams(target)
    params("caption") = html
    params("parse_mode") = ParseMode
    params("reply_markup") = keyboardJson(keyboard)
    call("editMessageCaption", params).map(_ => ())

  /** Replaces the keyboard and leaves the text alone; the empty keyboard removes every button. */
  def editMessageReplyMarkup(target: MessageTarget, keyboard: InlineKeyboard): Either[TelegramError, Unit] =
    val params = targetParams(target)
    params("reply_markup") = keyboardJson(keyboard)
    call("editMessageReplyMarkup", params).map(_ => ())

  def deleteMessage(target: MessageTarget): Either[TelegramError, Unit] =
    call("deleteMessage", targetParams(target)).map(_ => ())

  /** Shows a status in the chat for 5 seconds or until the next message arrives (VISUALS P20). */
  def sendChatAction(chatId: Long, action: ChatAction): Either[TelegramError, Unit] =
    call("sendChatAction", ujson.Obj("chat_id" -> chatId.toDouble, "action" -> action.wire)).map(_ => ())

  /** The single answer to a callback query; `text` shows as a toast of at most 200 characters. */
  def answerCallbackQuery(queryId: String, text: Option[String]): Either[TelegramError, Unit] =
    val params = ujson.Obj("callback_query_id" -> queryId)
    text.foreach(value => params("text") = value.take(MaxToastChars))
    call("answerCallbackQuery", params).map(_ => ())

  /** Registers the command list for every private chat (BOT_UX section 6.1); with `languageCode`, the list shown to
    * users whose client has that language (PRODUCT_SPEC R-LOC-10).
    */
  def setMyCommands(commands: List[BotCommand], languageCode: Option[String] = None): Either[TelegramError, Unit] =
    val list = commands.map(c => ujson.Obj("command" -> c.command, "description" -> c.description))
    val params = ujson.Obj("commands" -> ujson.Arr.from(list), "scope" -> privateChatsScope)
    languageCode.foreach(code => params("language_code") = code)
    call("setMyCommands", params).map(_ => ())

  def getMyCommands(languageCode: Option[String] = None): Either[TelegramError, List[BotCommand]] =
    val params = ujson.Obj("scope" -> privateChatsScope)
    languageCode.foreach(code => params("language_code") = code)
    call("getMyCommands", params).map(TelegramModels.decodeCommands)

  /** `chatId = None` sets the default button of every private chat (research brief 02, section 8). */
  def setChatMenuButton(chatId: Option[Long], button: MenuButton): Either[TelegramError, Unit] =
    val params = ujson.Obj("menu_button" -> menuButtonJson(button))
    chatId.foreach(id => params("chat_id") = id.toDouble)
    call("setChatMenuButton", params).map(_ => ())

  private def call(method: String, params: ujson.Obj): Either[TelegramError, ujson.Value] =
    send(WireRequest(method, params, None, callTimeout))

  private def send(request: WireRequest): Either[TelegramError, ujson.Value] =
    transport.call(request) match
      case WireReply.Failed(_)          => Left(TelegramError.Unavailable)
      case WireReply.Http(status, body) => readReply(status, body)

object BotApi:

  val CallTimeout: Duration = Duration.ofSeconds(10)
  val UploadTimeout: Duration = Duration.ofSeconds(30)
  val MaxToastChars = 200

  /** Exactly the kinds the product reads (ARCHITECTURE section 7.2.1); every other kind stays switched off. */
  val AllowedUpdates: List[String] = List("message", "edited_message", "callback_query", "my_chat_member")

  private val ParseMode = "HTML"
  private def privateChatsScope: ujson.Obj = ujson.Obj("type" -> "all_private_chats")

  /** `{"ok": true, "result": ...}` or `{"ok": false, "error_code", "description", "parameters"}`. A body that is not
    * JSON (a proxy error page) is classified by the HTTP status alone.
    */
  private[telegram] def readReply(status: Int, body: String): Either[TelegramError, ujson.Value] =
    val json =
      try Some(ujson.read(body, trace = false))
      catch case _: ujson.ParsingFailedException => None
    json match
      case Some(reply) if TelegramModels.field(reply, "ok").flatMap(_.boolOpt).contains(true) =>
        TelegramModels.field(reply, "result").toRight(TelegramError.Unavailable)
      case Some(reply) =>
        val code = TelegramModels.long(reply, "error_code").map(_.toInt).getOrElse(status)
        val retryAfter = TelegramModels.field(reply, "parameters").flatMap(TelegramModels.long(_, "retry_after"))
        Left(TelegramErrorMapper.classify(code, TelegramModels.str(reply, "description").getOrElse(""), retryAfter))
      case None => Left(TelegramErrorMapper.classify(if status / 100 == 2 then 502 else status, "", None))

  private def decoded[A](decode: ujson.Value => Option[A])(result: ujson.Value): Either[TelegramError, A] =
    decode(result).toRight(TelegramError.Unavailable)

  private def targetParams(target: MessageTarget): ujson.Obj =
    ujson.Obj("chat_id" -> target.chatId.toDouble, "message_id" -> target.messageId.toDouble)

  private def applyDelivery(params: ujson.Obj, delivery: Delivery): Unit =
    if delivery.silent then params("disable_notification") = true

  private def applyKeyboard(params: ujson.Obj, keyboard: InlineKeyboard): Unit =
    if !keyboard.isEmpty then params("reply_markup") = keyboardJson(keyboard)

  private[telegram] def keyboardJson(keyboard: InlineKeyboard): ujson.Obj =
    ujson.Obj("inline_keyboard" -> ujson.Arr.from(keyboard.rows.map(row => ujson.Arr.from(row.map(buttonJson)))))

  private def buttonJson(button: InlineButton): ujson.Obj =
    button match
      case InlineButton.Callback(text, data, style) =>
        val json = ujson.Obj("text" -> text, "callback_data" -> data)
        style.foreach(value => json("style") = value)
        json
      case InlineButton.WebApp(text, url) => ujson.Obj("text" -> text, "web_app" -> ujson.Obj("url" -> url))
      case InlineButton.Url(text, url)    => ujson.Obj("text" -> text, "url" -> url)

  private def menuButtonJson(button: MenuButton): ujson.Obj =
    button match
      case MenuButton.Commands          => ujson.Obj("type" -> "commands")
      case MenuButton.WebApp(text, url) =>
        ujson.Obj("type" -> "web_app", "text" -> text, "web_app" -> ujson.Obj("url" -> url))
end BotApi
