package dosegram.core.ports

import dosegram.contracts.ChatRef

/** One sticker of a personal set: the drawn picture, by the key the asset store holds its bytes under, and the emoji it
  * answers to.
  */
final case class StickerImage(assetKey: String, emoji: String):
  require(assetKey.nonEmpty, "a sticker names its picture")
  require(emoji.nonEmpty, "a sticker answers to an emoji")

/** Why a set was not published or removed. `Retryable` may pass; `Refused` will not; `Unsupported` is a vendor with no
  * personal sticker sets. None carries the vendor's text, which can quote the request.
  */
enum StickerSetFailure:
  case Unsupported, Retryable, Refused

/** A personal sticker set of the chat's user ("My mood stickers", supervisor decision of 2026-11-28), named by `setKey`
  * and owned by that user on the vendor's side. The vendor's naming rules stay with its adapter: the core passes a key
  * of letters, digits and single underscores that starts with a letter, and gets back the link that adds the set.
  */
trait StickerSets:

  /** Creates the set, or gives an existing one these stickers in this order and this title; the link that adds it. */
  def publish(
      chat: ChatRef,
      setKey: String,
      title: String,
      stickers: List[StickerImage]
  ): Either[StickerSetFailure, String]

  /** Deletes the set of `vendor`; a set that does not exist is already removed. */
  def remove(vendor: String, setKey: String): Either[StickerSetFailure, Unit]

object StickerSets:

  /** No vendor with sticker sets is wired. */
  val unavailable: StickerSets = new StickerSets:
    override def publish(
        chat: ChatRef,
        setKey: String,
        title: String,
        stickers: List[StickerImage]
    ): Either[StickerSetFailure, String] = Left(StickerSetFailure.Unsupported)
    override def remove(vendor: String, setKey: String): Either[StickerSetFailure, Unit] =
      Left(StickerSetFailure.Unsupported)

  /** A key of letters, digits and single underscores that starts with a letter: the shape every vendor's name allows.
    */
  def isValidKey(setKey: String): Boolean = KeyShape.matches(setKey)

  private val KeyShape = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*".r
