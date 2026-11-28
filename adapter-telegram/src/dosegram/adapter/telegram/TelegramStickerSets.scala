package dosegram.adapter.telegram

import dosegram.contracts.ChatRef
import dosegram.core.ports.StickerImage
import dosegram.core.ports.StickerSetFailure
import dosegram.core.ports.StickerSets

/** The personal sticker sets of "My mood stickers" on the Bot API (supervisor decision of 2026-11-28; research brief 04
  * section 3.2). A set is named `<key>_by_<bot username>`, as Telegram requires of a set a bot creates, and is owned by
  * the chat's user: in a private chat the chat id is the user id (ARCHITECTURE section 7.3). Publishing a set that
  * exists gives it the new stickers in place and the new title, so a second request updates the one set. Each sticker
  * is uploaded from the asset store; the stickers are static PNGs, 512 px square.
  *
  * Logs name the method and the kind of failure only.
  */
final class TelegramStickerSets(
    api: BotApi,
    media: MediaStore,
    botUsername: () => Option[String],
    log: String => Unit
) extends StickerSets:

  import TelegramStickerSets.*

  override def publish(
      chat: ChatRef,
      setKey: String,
      title: String,
      stickers: List[StickerImage]
  ): Either[StickerSetFailure, String] =
    for
      name <- nameOf(setKey)
      owner <- chat.chatId.toLongOption.toRight(StickerSetFailure.Refused)
      inputs <- uploads(stickers)
      _ <- api.getStickerSet(name) match
        case Right(existing)                   => update(owner, name, title, existing, inputs)
        case Left(TelegramError.MissingTarget) =>
          api.createNewStickerSet(owner, name, title, inputs).left.map(failure("createNewStickerSet"))
        case Left(error) => Left(failure("getStickerSet")(error))
    yield s"$LinkPrefix$name"

  override def remove(vendor: String, setKey: String): Either[StickerSetFailure, Unit] =
    nameOf(setKey).flatMap: name =>
      api.deleteStickerSet(name) match
        case Right(()) | Left(TelegramError.MissingTarget) => Right(())
        case Left(error)                                   => Left(failure("deleteStickerSet")(error))

  /** The set's name under Telegram's rules: letters, digits and single underscores, starting with a letter, ending in
    * `_by_<bot username>`, at most 64 characters. A bot without a username has no sets.
    */
  private def nameOf(setKey: String): Either[StickerSetFailure, String] =
    botUsername() match
      case None           => Left(StickerSetFailure.Unsupported)
      case Some(username) =>
        val name = s"${setKey}_by_$username"
        Either.cond(isValidName(name), name, StickerSetFailure.Refused)

  private def uploads(stickers: List[StickerImage]): Either[StickerSetFailure, List[InputSticker]] =
    val inputs = stickers.zipWithIndex.map: (sticker, index) =>
      media
        .load(sticker.assetKey)
        .map(bytes => InputSticker(MediaPayload.Upload(s"sticker$index.png", Png, bytes), List(sticker.emoji)))
    if stickers.isEmpty || stickers.size > MaxStickers then Left(StickerSetFailure.Refused)
    else if inputs.exists(_.isEmpty) then
      log("telegram sticker set not published: a sticker's bytes are gone")
      Left(StickerSetFailure.Retryable)
    else Right(inputs.flatten)

  /** The set's stickers in place, in order: each one replaced, the missing ones added, the extra ones deleted, then the
    * title when it changed.
    */
  private def update(
      owner: Long,
      name: String,
      title: String,
      existing: StickerSetInfo,
      inputs: List[InputSticker]
  ): Either[StickerSetFailure, Unit] =
    val replaced = inputs.zipWithIndex.foldLeft[Either[StickerSetFailure, Unit]](Right(())): (done, entry) =>
      val (input, index) = entry
      done.flatMap: _ =>
        existing.stickers.lift(index) match
          case Some(old) => api.replaceStickerInSet(owner, name, old, input).left.map(failure("replaceStickerInSet"))
          case None      => api.addStickerToSet(owner, name, input).left.map(failure("addStickerToSet"))
    val trimmed = existing.stickers
      .drop(inputs.size)
      .foldLeft(replaced): (done, extra) =>
        done.flatMap(_ => api.deleteStickerFromSet(extra).left.map(failure("deleteStickerFromSet")))
    trimmed.flatMap: _ =>
      if existing.title == title then Right(())
      else api.setStickerSetTitle(name, title).left.map(failure("setStickerSetTitle"))

  private def failure(method: String)(error: TelegramError): StickerSetFailure =
    log(s"telegram $method failed: ${error.productPrefix}")
    error match
      case TelegramError.RateLimited(_) | TelegramError.Unavailable | TelegramError.TokenInvalid |
          TelegramError.Conflict =>
        StickerSetFailure.Retryable
      case _ => StickerSetFailure.Refused

object TelegramStickerSets:

  /** The link that opens a set in every Telegram client. */
  val LinkPrefix = "https://t.me/addstickers/"

  /** The most stickers a set is created with in one call. */
  val MaxStickers = 50

  /** The longest name Telegram accepts for a set. */
  val MaxNameChars = 64

  private val Png = "image/png"
  private val NameShape = "[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*".r

  def isValidName(name: String): Boolean = name.length <= MaxNameChars && NameShape.matches(name)
