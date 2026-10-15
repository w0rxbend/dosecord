package dosecord.core.chat

import dosecord.contracts.ChatRef
import dosecord.contracts.OutboundMessage

/** Durable reply destination, independent of account linking and later primary-channel changes. The extra payload field
  * is backward compatible with stored OutboundMessage JSON.
  */
private[core] object InteractionReplyPayload:
  private val ChatField = "deliveryChat"

  def encode(message: OutboundMessage, chat: ChatRef): String =
    val json = ujson.read(OutboundMessage.toJson(message))
    json(ChatField) = upickle.default.writeJs(chat)
    ujson.write(json)

  def chat(payload: String): Option[ChatRef] =
    ujson.read(payload).obj.get(ChatField).map(upickle.default.read[ChatRef](_))
