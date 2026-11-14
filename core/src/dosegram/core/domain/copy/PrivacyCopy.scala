package dosegram.core.domain.copy

import dosegram.core.ports.DataDomain

/** The deletion copy of BOT_UX section 20.3 (`privacy.delete.*`, `privacy.deleted`; PRODUCT_SPEC R-IDC-19, R-IDC-20,
  * AC-IDC-12). Each confirmation names what is deleted, says that it cannot be undone, and says what deletion cannot
  * do: remove messages and images already in the chat (research brief 06, H10). The account confirmation states the
  * backup period (ROADMAP X-35).
  */
object PrivacyCopy:

  def DeleteData: String = tr("Delete data", "Видалити дані")
  def DeleteMyAccount: String = tr("Delete my account", "Видалити мій акаунт")
  def KeepMyAccount: String = tr("Keep my account", "Залишити мій акаунт")
  def Continue: String = tr("Continue", "Продовжити")
  def KeepTheData: String = tr("Keep the data", "Залишити дані")
  def DeleteThisData: String = tr("Delete this data", "Видалити ці дані")
  def Back: String = tr("Back", "Назад")

  /** The records of one domain, as a noun phrase: "water records". */
  def records(domain: DataDomain): String =
    domain match
      case DataDomain.Medication => tr("medication records", "записи про ліки")
      case DataDomain.Hydration  => tr("water records", "записи про воду")
      case DataDomain.Mood       => tr("mood records", "записи про настрій")
      case DataDomain.Sleep      => tr("sleep records", "записи про сон")
      case DataDomain.Nutrition  => tr("meal records", "записи про їжу")

  /** What the deletion of one domain removes, after its name. */
  private def contents(domain: DataDomain): String =
    domain match
      case DataDomain.Medication =>
        tr(
          "every medication, schedule, reminder, dose and count",
          "усі ліки, розклади, нагадування, дози й залишки"
        )
      case DataDomain.Hydration =>
        tr("every water entry, goal and quick amount", "усі записи води, цілі й швидкі об’єми")
      case DataDomain.Mood      => tr("every mood check-in and mood tag", "усі позначки настрою й теги настрою")
      case DataDomain.Sleep     => tr("every sleep entry, tap and goal", "усі записи сну, позначки й цілі")
      case DataDomain.Nutrition => tr("every meal entry, meal plan and meal tag", "усі записи їжі, плани й теги їжі")

  def domainLabel(domain: DataDomain): String =
    tr(s"Delete ${records(domain)}", s"Видалити ${records(domain)}")

  /** `privacy.delete.chat_limit`: what no deletion can do (H10). */
  def chatLimit: String = tr(
    "Messages and images already in this chat stay there until you delete them in Telegram.",
    "Повідомлення й зображення, що вже є в цьому чаті, лишаються там, доки ви не видалите їх у Telegram."
  )

  def cannotUndo: String = tr("This cannot be undone.", "Це не можна скасувати.")

  /** `privacy.delete.menu`. */
  def menu: String = tr(
    "What do you want to delete? Each choice asks you to confirm first.",
    "Що ви хочете видалити? Кожен вибір спершу попросить підтвердження."
  )

  /** `privacy.delete.domain`: the confirmation of one domain. */
  def domainConfirm(domain: DataDomain): List[String] =
    List(
      tr(
        s"Delete your ${records(domain)}? This deletes ${contents(domain)}, and the daily figures made from them.",
        s"Видалити ваші ${records(domain)}? Буде видалено ${contents(domain)}, а також денні підсумки з них."
      ),
      tr(
        "The consent for this area is withdrawn too. The other areas stay as they are.",
        "Згоду для цього розділу теж буде відкликано. Інші розділи лишаються без змін."
      ),
      cannotUndo,
      chatLimit
    )

  def domainConfirmFromApp(domain: DataDomain): List[String] =
    tr(
      "The app asked to delete data. Confirm it here.",
      "Застосунок запросив видалення даних. Підтвердьте його тут."
    ) +: domainConfirm(domain)

  /** `privacy.delete.domain.done`. */
  def domainDeleted(domain: DataDomain): String =
    tr(
      s"Your ${records(domain)} are deleted. The consent for this area is withdrawn.",
      s"Ваші ${records(domain)} видалено. Згоду для цього розділу відкликано."
    )

  /** `privacy.delete.kept`. */
  def kept: String = tr("Nothing was deleted.", "Нічого не видалено.")

  /** `privacy.delete.account`: the first confirmation. */
  def accountConfirm: List[String] =
    List(
      tr(
        "Delete your account? This deletes all your records, schedules, settings, consents and generated files.",
        "Видалити ваш акаунт? Буде видалено всі ваші записи, розклади, налаштування, згоди й створені файли."
      ),
      tr(
        "All reminders stop at once. The bot then forgets this chat.",
        "Усі нагадування одразу припиняться. Після цього бот забуде цей чат."
      ),
      tr(
        "Your records become unreadable in live storage at once. Encrypted backups expire within 35 days.",
        "Ваші записи одразу стануть недоступними в робочому сховищі. Зашифровані резервні копії зникнуть " +
          "протягом 35 днів."
      ),
      chatLimit,
      tr("To keep a copy, send /export first.", "Щоб зберегти копію, спершу надішліть /export.")
    )

  def accountConfirmFromApp: List[String] =
    tr(
      "The app asked to delete your account. Confirm it here.",
      "Застосунок запросив видалення вашого акаунта. Підтвердьте його тут."
    ) +: accountConfirm

  /** `privacy.delete.account.final`: the second confirmation. */
  def accountFinal: List[String] =
    List(
      tr(
        "Last step: delete your account and all your records now?",
        "Останній крок: видалити ваш акаунт і всі записи зараз?"
      ),
      cannotUndo
    )

  /** `privacy.deleted`: the last message of the bot (BUX-DEL-04). */
  def deleted: String = tr(
    "Your account and all your records are deleted. Reminders have stopped. Messages and images already in this " +
      "chat stay until you delete them. Send /start if you want to begin again. Goodbye.",
    "Ваш акаунт і всі записи видалено. Нагадування припинено. Повідомлення й зображення, що вже є в цьому чаті, " +
      "лишаються, доки ви їх не видалите. Надішліть /start, якщо захочете почати знову. До побачення."
  )

  /** `privacy.delete.expired`: a confirmation older than ten minutes (brief 06 section 12.3). */
  def expired: String = tr(
    "This confirmation has expired. Nothing was deleted. Open /privacy to start again.",
    "Термін цього підтвердження минув. Нічого не видалено. Відкрийте /privacy, щоб почати знову."
  )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("privacy.delete.button", DeleteData),
      CopyEntry("privacy.delete.account.button", DeleteMyAccount),
      CopyEntry("privacy.delete.account.keep", KeepMyAccount),
      CopyEntry("privacy.delete.continue", Continue),
      CopyEntry("privacy.delete.keep_data", KeepTheData),
      CopyEntry("privacy.delete.this_data", DeleteThisData),
      CopyEntry("privacy.delete.back", Back),
      CopyEntry("privacy.delete.chat_limit", chatLimit),
      CopyEntry("privacy.delete.menu", menu),
      CopyEntry("privacy.delete.kept", kept),
      CopyEntry("privacy.delete.account", accountConfirm.mkString(" ")),
      CopyEntry("privacy.delete.account.app", accountConfirmFromApp.mkString(" ")),
      CopyEntry("privacy.delete.account.final", accountFinal.mkString(" ")),
      CopyEntry("privacy.deleted", deleted),
      CopyEntry("privacy.delete.expired", expired)
    ) ++ DataDomain.values.toList.flatMap: domain =>
      List(
        CopyEntry(s"privacy.delete.${domain.code}.label", domainLabel(domain)),
        CopyEntry(s"privacy.delete.${domain.code}", domainConfirm(domain).mkString(" ")),
        CopyEntry(s"privacy.delete.${domain.code}.app", domainConfirmFromApp(domain).mkString(" ")),
        CopyEntry(s"privacy.delete.${domain.code}.done", domainDeleted(domain))
      )
end PrivacyCopy
