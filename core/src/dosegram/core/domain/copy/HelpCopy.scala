package dosegram.core.domain.copy

/** Help copy (ROADMAP M1.4a). `/help` is rendered from the `CommandSpec` registry (M0.12d) so it cannot omit a command;
  * these are the per-command description lines it uses, keyed by the command's usage string.
  */
object HelpCopy:

  def intro =
    tr("Dosegram keeps track of your medications and reminders.", "Dosegram веде облік ваших ліків і нагадувань.")

  /** Fallbacks for input that matches no command (the console is free-text). */
  def unknownCommand = tr("I don't know that command — try /help.", "Такої команди я не знаю — спробуйте /help.")
  def unknownMessage = tr("I didn't understand that — try /help.", "Не вдалося зрозуміти — спробуйте /help.")

  /** (usage, description) per command, in display order. */
  def commands: List[(String, String)] = List(
    "/start" -> tr("Create your account and pick a timezone.", "Створити акаунт і вибрати часовий пояс."),
    "/today" -> tr("Show today's doses and record them.", "Показати сьогоднішні дози й записати їх."),
    "/meds" -> tr("Show your medications.", "Показати ваші ліки."),
    "/history" -> tr("Show the last 7 days.", "Показати останні 7 днів."),
    "/taken [medication]" -> tr("Record a dose as taken.", "Записати дозу як прийняту."),
    "/snooze <minutes>" -> tr("Snooze the current reminder.", "Відкласти поточне нагадування."),
    "/skip [medication]" -> tr("Mark a dose as skipped.", "Позначити дозу як пропущену."),
    "/log <medication>" -> tr("Log an as-needed dose.", "Записати дозу за потреби."),
    "/menu" -> tr("Open the main menu.", "Відкрити головне меню."),
    "/app" -> tr("Open the app.", "Відкрити застосунок."),
    "/cancel" -> tr("Cancel the current setup.", "Скасувати поточне налаштування."),
    "/help" -> tr("Show this help.", "Показати цю довідку.")
  )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("help.intro", intro),
      CopyEntry("help.unknown_command", unknownCommand),
      CopyEntry("help.unknown_message", unknownMessage)
    ) ++ commands.map((usage, description) =>
      CopyEntry(s"help.command.${usage.drop(1).takeWhile(_.isLetterOrDigit)}", s"$usage — $description")
    )
end HelpCopy
