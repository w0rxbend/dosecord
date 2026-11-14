package dosegram.adapter.telegram

import dosegram.core.domain.copy.Lang

import java.time.Instant

/** The part of the Bot API object model this adapter reads (ARCHITECTURE section 7.1), decoded by hand from `ujson`.
  * Decoding is tolerant (TG-01): an unknown field is ignored, an update kind the adapter does not know and a known kind
  * whose content does not have the expected shape both decode to [[UpdateBody.Unknown]]. No decoder keeps or reports
  * the text it could not read.
  */
/** `locale` is the shipped locale the user's `language_code` names (PRODUCT_SPEC R-LOC-1), never the raw tag. */
final case class TgUser(id: Long, locale: Option[String] = None)

final case class TgChat(id: Long, isPrivate: Boolean)

final case class TgPhotoSize(fileId: String, width: Int, height: Int):
  def longSide: Int = math.max(width, height)

enum MessageContent:
  case Text(text: String)
  case Photo(sizes: List[TgPhotoSize], caption: Option[String])
  case Document(fileId: String, caption: Option[String])

  /** Data from a reply-keyboard Mini App. It is unauthenticated, so it is never read (ARCHITECTURE section 7.3). */
  case WebAppData

  /** Anything else: a sticker, a voice note, a service message, or a message the bot can no longer access. */
  case Other

  /** A message whose text lives in a caption: it is edited with `editMessageCaption`, not `editMessageText`. */
  def isMedia: Boolean = this match
    case _: Photo | _: Document => true
    case _                      => false

/** A message named by another one: the message a reply answers. */
final case class MessageRef(messageId: Long, carriesMedia: Boolean)

final case class TgMessage(
    messageId: Long,
    chat: TgChat,
    from: Option[TgUser],
    sentAt: Option[Instant],
    content: MessageContent,
    replyTo: Option[MessageRef]
):
  def ref: MessageRef = MessageRef(messageId, content.isMedia)

final case class TgCallbackQuery(id: String, from: TgUser, message: Option[TgMessage], data: Option[String])

enum UpdateBody:
  case NewMessage(message: TgMessage)
  case EditedMessage(message: TgMessage)
  case Callback(query: TgCallbackQuery)

  /** `my_chat_member`: in a private chat it reports that the user blocked (`kicked`) or unblocked (`member`) the bot.
    */
  case ChatMemberChanged(chat: TgChat, status: String)
  case Unknown(kind: String)

final case class Update(updateId: Long, body: UpdateBody)

final case class BotIdentity(id: Long, username: Option[String])

/** What a send returned: the message id and, for media, the `file_id` to reuse (TG-35). */
final case class SentMessage(messageId: Long, fileId: Option[String])

final case class BotCommand(command: String, description: String)

object TelegramModels:

  private val KnownKinds = List("message", "edited_message", "callback_query", "my_chat_member")

  /** `None` only when the envelope has no `update_id`: such a body is not an update at all. */
  def decodeUpdate(json: ujson.Value): Option[Update] =
    long(json, "update_id").map: updateId =>
      val kind = KnownKinds.find(field(json, _).isDefined)
      val body = kind.flatMap(name => decodeBody(name, json(name)))
      Update(updateId, body.getOrElse(UpdateBody.Unknown(kind.getOrElse(unknownKind(json)))))

  private def decodeBody(kind: String, json: ujson.Value): Option[UpdateBody] =
    kind match
      case "message"        => decodeMessage(json).map(UpdateBody.NewMessage(_))
      case "edited_message" => decodeMessage(json).map(UpdateBody.EditedMessage(_))
      case "callback_query" => decodeCallback(json).map(UpdateBody.Callback(_))
      case "my_chat_member" =>
        for
          chat <- field(json, "chat").flatMap(decodeChat)
          status <- field(json, "new_chat_member").flatMap(str(_, "status"))
        yield UpdateBody.ChatMemberChanged(chat, status)
      case _ => None

  /** The name of the one optional field of an update of a kind this adapter does not know. A field name comes from the
    * Bot API schema, not from a user; it is still clipped to the characters a field name has.
    */
  private def unknownKind(json: ujson.Value): String =
    json.objOpt
      .flatMap(_.keys.find(_ != "update_id"))
      .map(_.filter(c => c.isLetterOrDigit || c == '_').take(48))
      .filter(_.nonEmpty)
      .getOrElse("unknown")

  def decodeMessage(json: ujson.Value): Option[TgMessage] =
    for
      messageId <- long(json, "message_id")
      chat <- field(json, "chat").flatMap(decodeChat)
    yield TgMessage(
      messageId = messageId,
      chat = chat,
      from = field(json, "from").flatMap(decodeUser),
      // An inaccessible message has date 0.
      sentAt = long(json, "date").filter(_ > 0).map(Instant.ofEpochSecond),
      content = decodeContent(json),
      replyTo = field(json, "reply_to_message").flatMap: replied =>
        long(replied, "message_id").map(MessageRef(_, decodeContent(replied).isMedia))
    )

  private def decodeContent(json: ujson.Value): MessageContent =
    val caption = str(json, "caption")
    val photo = list(json, "photo").flatMap(decodePhotoSize)
    val document = field(json, "document").flatMap(str(_, "file_id"))
    (photo, document) match
      case (sizes, _) if sizes.nonEmpty               => MessageContent.Photo(sizes, caption)
      case (_, Some(fileId))                          => MessageContent.Document(fileId, caption)
      case _ if field(json, "web_app_data").isDefined => MessageContent.WebAppData
      case _ => str(json, "text").map(MessageContent.Text(_)).getOrElse(MessageContent.Other)

  private def decodePhotoSize(json: ujson.Value): Option[TgPhotoSize] =
    for
      fileId <- str(json, "file_id")
      width <- long(json, "width")
      height <- long(json, "height")
    yield TgPhotoSize(fileId, width.toInt, height.toInt)

  private def decodeCallback(json: ujson.Value): Option[TgCallbackQuery] =
    for
      id <- str(json, "id")
      from <- field(json, "from").flatMap(decodeUser)
    yield TgCallbackQuery(id, from, field(json, "message").flatMap(decodeMessage), str(json, "data"))

  /** The inbox stores the shipped locale as `locale`; an update from Telegram carries the client's `language_code`. */
  private def decodeUser(json: ujson.Value): Option[TgUser] =
    val locale = str(json, "locale").orElse(str(json, "language_code")).flatMap(Lang.ofCode).map(_.code)
    long(json, "id").map(TgUser(_, locale))

  private def decodeChat(json: ujson.Value): Option[TgChat] =
    long(json, "id").map(id => TgChat(id, isPrivate = str(json, "type").contains("private")))

  def decodeIdentity(json: ujson.Value): Option[BotIdentity] =
    long(json, "id").map(BotIdentity(_, str(json, "username")))

  /** The `file_id` to reuse is the largest photo size, or the document's. */
  def decodeSent(json: ujson.Value): Option[SentMessage] =
    long(json, "message_id").map: messageId =>
      val photo = list(json, "photo").flatMap(decodePhotoSize).maxByOption(_.longSide).map(_.fileId)
      SentMessage(messageId, photo.orElse(field(json, "document").flatMap(str(_, "file_id"))))

  def decodeCommands(json: ujson.Value): List[BotCommand] =
    json.arrOpt.toList.flatten.flatMap: entry =>
      for
        command <- str(entry, "command")
        description <- str(entry, "description")
      yield BotCommand(command, description)

  // ---------- Tolerant field access ----------

  private[telegram] def field(json: ujson.Value, key: String): Option[ujson.Value] =
    json.objOpt.flatMap(_.get(key)).filterNot(_.isNull)

  /** Identifiers have at most 52 significant bits (research brief 02, section 3), so the JSON number is exact. */
  private[telegram] def long(json: ujson.Value, key: String): Option[Long] =
    field(json, key).flatMap(_.numOpt).filter(n => n == math.rint(n)).map(_.toLong)

  private[telegram] def str(json: ujson.Value, key: String): Option[String] = field(json, key).flatMap(_.strOpt)

  private def list(json: ujson.Value, key: String): List[ujson.Value] =
    field(json, key).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
end TelegramModels
