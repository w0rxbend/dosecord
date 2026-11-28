package dosegram.core.scheduling

import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.StickerSets

import java.time.Instant
import java.util.UUID

/** The deletion of a personal sticker set after the account it was made for is deleted (supervisor decision of
  * 2026-11-28): an outbox row written on the deletion's transaction, after the erasure, with no account and no chat, so
  * it outlives the account; the dispatcher asks the vendor to delete the set, with the outbox's retries. Its payload
  * holds the set's key and nothing else.
  */
object StickerSetRemoval:
  val Kind = "sticker_set_removal"

  /** What a sent removal records in place of a message handle: it sent no message. */
  val Target = "sticker-set"

  private val Payload = """\{"set":"([a-z0-9_]+)"\}""".r

  def row(id: UUID, vendor: String, setKey: String, now: Instant): NewOutboxMessage =
    require(StickerSets.isValidKey(setKey), "a set key of letters, digits and single underscores")
    NewOutboxMessage(
      id = id,
      sendKey = s"stickers.remove:$setKey",
      op = OutboxOp.Send,
      kind = Kind,
      vendor = vendor,
      payload = s"""{"set":"$setKey"}""",
      importance = "bulk",
      nextAttemptAt = now
    )

  /** The set a removal row names; `None` for a payload this object did not write. */
  def setKeyOf(payload: String): Option[String] =
    payload match
      case Payload(setKey) => Some(setKey)
      case _               => None
