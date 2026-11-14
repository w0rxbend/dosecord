package dosegram.adapter.telegram

import dosegram.core.ports.InboxEntry

/** The inbox form of an update (ADR-017). The inbox keeps the decoded update written back as the minimal JSON that
  * [[TelegramModels.decodeUpdate]] reads, not the body Telegram sent: what the adapter does not read (names, the raw
  * language tag, entities) is never stored; of the language only the shipped locale it names is kept. Decoding the
  * stored body gives back the same [[Update]].
  */
object InboxCodec:

  /** Updates without a chat share one queue. */
  private val NoChat = "none"

  def entry(update: Update): InboxEntry =
    InboxEntry(
      vendor = TelegramAdapter.Vendor,
      vendorEventId = vendorEventId(update.updateId),
      chatKey = chatOf(update.body).fold(NoChat)(_.toString),
      sequence = update.updateId,
      body = ujson.write(encode(update))
    )

  def update(entry: InboxEntry): Option[Update] =
    val json =
      try Some(ujson.read(entry.body, trace = false))
      catch case _: ujson.ParsingFailedException => None
    json.flatMap(TelegramModels.decodeUpdate)

  def vendorEventId(updateId: Long): String = s"telegram:update:$updateId"

  /** The chat whose updates are processed in order: a callback belongs to the chat of its message, or to its sender's
    * private chat when the message is no longer attached.
    */
  private def chatOf(body: UpdateBody): Option[Long] =
    body match
      case UpdateBody.NewMessage(message)        => Some(message.chat.id)
      case UpdateBody.EditedMessage(message)     => Some(message.chat.id)
      case UpdateBody.Callback(query)            => Some(query.message.fold(query.from.id)(_.chat.id))
      case UpdateBody.ChatMemberChanged(chat, _) => Some(chat.id)
      case UpdateBody.Unknown(_)                 => None

  private def encode(update: Update): ujson.Obj =
    val json = ujson.Obj("update_id" -> number(update.updateId))
    update.body match
      case UpdateBody.NewMessage(message)             => json("message") = messageJson(message)
      case UpdateBody.EditedMessage(message)          => json("edited_message") = messageJson(message)
      case UpdateBody.Callback(query)                 => json("callback_query") = callbackJson(query)
      case UpdateBody.ChatMemberChanged(chat, status) =>
        json("my_chat_member") = ujson.Obj("chat" -> chatJson(chat), "new_chat_member" -> ujson.Obj("status" -> status))
      // An empty object of the kind's name decodes to the same unknown kind.
      case UpdateBody.Unknown(kind) => json(kind) = ujson.Obj()
    json

  private def messageJson(message: TgMessage): ujson.Obj =
    val json = ujson.Obj("message_id" -> number(message.messageId), "chat" -> chatJson(message.chat))
    message.from.foreach(user => json("from") = userJson(user))
    message.sentAt.foreach(at => json("date") = number(at.getEpochSecond))
    contentFields(message.content).foreach((key, value) => json(key) = value)
    message.replyTo.foreach: replied =>
      val reply = ujson.Obj("message_id" -> number(replied.messageId))
      // Only whether the replied message carries media is read.
      if replied.carriesMedia then reply("document") = ujson.Obj("file_id" -> "")
      json("reply_to_message") = reply
    json

  private def contentFields(content: MessageContent): List[(String, ujson.Value)] =
    content match
      case MessageContent.Text(text)            => List("text" -> text)
      case MessageContent.Photo(sizes, caption) =>
        ("photo" -> ujson.Arr.from(sizes.map(photoJson))) :: captionOf(caption)
      case MessageContent.Document(fileId, caption) =>
        ("document" -> ujson.Obj("file_id" -> fileId)) :: captionOf(caption)
      case MessageContent.WebAppData => List("web_app_data" -> ujson.Obj())
      case MessageContent.Other      => Nil

  private def captionOf(caption: Option[String]): List[(String, ujson.Value)] =
    caption.toList.map(text => "caption" -> ujson.Str(text))

  private def photoJson(size: TgPhotoSize): ujson.Obj =
    ujson.Obj("file_id" -> size.fileId, "width" -> size.width, "height" -> size.height)

  private def callbackJson(query: TgCallbackQuery): ujson.Obj =
    val json = ujson.Obj("id" -> query.id, "from" -> userJson(query.from))
    query.message.foreach(message => json("message") = messageJson(message))
    query.data.foreach(data => json("data") = data)
    json

  private def chatJson(chat: TgChat): ujson.Obj =
    ujson.Obj("id" -> number(chat.id), "type" -> (if chat.isPrivate then "private" else "other"))

  private def userJson(user: TgUser): ujson.Obj =
    val json = ujson.Obj("id" -> number(user.id))
    user.locale.foreach(code => json("locale") = code)
    json

  /** Identifiers have at most 52 significant bits (research brief 02, section 3), so a JSON number holds them. */
  private def number(value: Long): ujson.Num = ujson.Num(value.toDouble)
