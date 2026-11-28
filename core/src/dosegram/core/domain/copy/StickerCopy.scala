package dosegram.core.domain.copy

/** "My mood stickers" (`/mood stickers`, supervisor decision of 2026-11-28; BOT_UX section 11.4): a personal sticker
  * set of the blob in each of the five steps. The words name what the set holds and nothing about the user's own
  * records.
  */
object StickerCopy:

  /** The set's title, which everyone the user shares the set with sees: it names no product and no record. */
  def title: String = tr("Mood blobs", "Блоби настрою")

  def ready: String =
    tr(
      "Your mood stickers are ready: the blob of each of the five steps, from very unpleasant to very pleasant.",
      "Ваші стікери настрою готові: блоб кожного з п'яти кроків, від дуже неприємно до дуже приємно."
    )

  /** The line under the link: the set and its link are the user's own on the messenger. */
  def ownSet: String =
    tr(
      "The set is yours: anyone you send a sticker or the link to can add it. Deleting your account deletes the set.",
      "Набір ваш: його може додати кожен, кому ви надішлете стікер або посилання. Видалення акаунта видаляє набір."
    )

  def Add: String = tr("Add the stickers", "Додати стікери")

  def failed: String =
    tr(
      "The stickers could not be made just now. Try /mood stickers again in a few minutes.",
      "Зараз не вдалося створити стікери. Спробуйте /mood stickers ще раз за кілька хвилин."
    )

  /** The account has an image underway, or the images of the day are used up. */
  def busy: String =
    tr(
      "An image is still being made for you. Try /mood stickers again in a minute.",
      "Для вас ще готується зображення. Спробуйте /mood stickers ще раз за хвилину."
    )

  /** This chat has no sticker sets, or no renderer is wired. */
  def unavailable: String =
    tr("Mood stickers are not available here.", "Стікери настрою тут недоступні.")

  def entries: List[CopyEntry] =
    List(
      CopyEntry("stickers.title", title),
      CopyEntry("stickers.ready", ready),
      CopyEntry("stickers.own_set", ownSet),
      CopyEntry("stickers.add", Add),
      CopyEntry("stickers.failed", failed),
      CopyEntry("stickers.busy", busy),
      CopyEntry("stickers.unavailable", unavailable)
    )
end StickerCopy
