package dosegram.core.domain.copy

/** The language choice of the chat (BOT_UX section 5.4: `/language` and the settings entry; ROADMAP G2.22). Each
  * language is named in itself, so a user who cannot read the current language still finds their own.
  */
object LanguageCopy:

  def prompt: String = tr(
    "Choose the language of the bot and the app.",
    "Виберіть мову бота й застосунку."
  )

  /** A language in its own words, the same in every catalogue. */
  def name(lang: Lang): String =
    lang match
      case Lang.En => "English"
      case Lang.Uk => "Українська"

  /** The answer to the choice, written in the chosen language. */
  def changed(lang: Lang): String =
    Lang.within(lang):
      tr(
        "Language: English. The app follows this choice too.",
        "Мову змінено на українську. Застосунок теж нею говоритиме."
      )

  def entries: List[CopyEntry] =
    List(CopyEntry("language.prompt", prompt)) ++
      Lang.values.toList.flatMap(lang =>
        List(
          CopyEntry(s"language.name.${lang.code}", name(lang)),
          CopyEntry(s"language.changed.${lang.code}", changed(lang))
        )
      )
end LanguageCopy
