package dosegram.core.domain.copy

/** The "What's new" note (supervisor decision, 2026-11-28; BOT_UX section 6.5): the commands a release added that the
  * user can see, sent once after the user's next command. `Release` names the current release; a release that changes
  * what the user sees raises it and replaces `items`, so a release that changes nothing visible sends nothing.
  */
object WhatsNewCopy:

  /** The release the items below describe. Raise it only with new items. Release 2 (supervisor decision, 2026-11-28):
    * the settings hub of `/settings`, which also gives the chat its "Animated mood" switch (BOT_UX section 6.5).
    */
  val Release: Int = 2

  def title: String = tr("What's new", "Що нового")

  /** One line per command the release added or changed, the command first. */
  def items: List[String] = List(
    tr(
      "/settings: every setting in one card, switched in place: language, time zone, your day, image theme, discreet reminders, animated mood and the notes after updates.",
      "/settings: усі налаштування в одній картці, що змінюються на місці: мова, часовий пояс, ваш день, тема зображень, стримані нагадування, анімований настрій і примітки після оновлень."
    )
  )

  def once: String = tr(
    "This note comes once after an update. /whatsnew shows it again.",
    "Ця примітка приходить один раз після оновлення. /whatsnew покаже її знову."
  )

  def StopThese: String = tr("Don't show these", "Не показувати такі")
  def TurnOn: String = tr("Show after updates", "Показувати після оновлень")

  def off: String = tr(
    "Notes after updates are off. /whatsnew shows the latest one and turns them back on.",
    "Примітки після оновлень вимкнено. /whatsnew покаже останню й увімкне їх знову."
  )

  def on: String = tr(
    "Notes after updates are on: one comes after an update that adds something you can use.",
    "Примітки після оновлень увімкнено: одна приходить після оновлення, що додає щось корисне для вас."
  )

  /** The lines of the note. */
  def lines: List[String] = (title :: items.map(item => s"• $item")) :+ once

  def entries: List[CopyEntry] =
    List(
      CopyEntry("whatsnew.note", lines.mkString("\n")),
      CopyEntry("whatsnew.stop", StopThese),
      CopyEntry("whatsnew.turn_on", TurnOn),
      CopyEntry("whatsnew.off", off),
      CopyEntry("whatsnew.on", on)
    )
end WhatsNewCopy
