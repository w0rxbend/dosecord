package dosegram.core.domain.copy

/** The description of each command in the current language, as `/help` prints it and as the bot registers it per
  * language (PRODUCT_SPEC R-LOC-10, BOT_UX BUX-LANG-04). The English text is the registry's own description.
  */
object CommandCopy:

  private val Ukrainian: Map[String, String] = Map(
    "start" -> "Створити акаунт і вибрати часовий пояс.",
    "today" -> "Показати сьогоднішні дози й записати їх.",
    "meds" -> "Показати ваші ліки.",
    "history" -> "Показати останні 7 днів.",
    "taken" -> "Записати останню дозу як прийняту.",
    "snooze" -> "Відкласти останню дозу.",
    "skip" -> "Пропустити останню дозу.",
    "log" -> "Записати дозу, яку ви вже прийняли.",
    "menu" -> "Відкрити головне меню.",
    "app" -> "Відкрити застосунок.",
    "cancel" -> "Скасувати поточне налаштування.",
    "help" -> "Показати цю довідку.",
    "water" -> "Записати воду.",
    "mood" -> "Записати настрій.",
    "sleep" -> "Записати сон.",
    "food" -> "Записати прийом їжі.",
    "meal" -> "Записати прийом їжі.",
    "stats" -> "Показати статистику.",
    "privacy" -> "Переглянути й відкликати згоди.",
    "reminders" -> "Нагадування, готові плани, робочі й тихі години.",
    "insights" -> "Закономірності у ваших записах.",
    "export" -> "Експортувати записи або створити зведення.",
    "language" -> "Змінити мову."
  )

  /** The description of the command `name`; `english` is the registry's text. */
  def description(name: String, english: String): String =
    Lang.current match
      case Lang.En => english
      case Lang.Uk => Ukrainian.getOrElse(name, english)

  /** The commands that have a Ukrainian description, for the test that every registered command has one. */
  def ukrainianNames: Set[String] = Ukrainian.keySet
end CommandCopy
