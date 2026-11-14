package dosegram.core.domain.copy

import dosegram.core.ports.ConsentPurpose

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale as JavaLocale

/** Consent copy (BOT_UX section 5.5 and the transcript of section 21.7; PRODUCT_SPEC R-IDC-6, R-IDC-7). The statement
  * is shown in the message at both stages, and the ledger stores its hash under [[NoticeVersion]]: a change of a
  * statement is a new version.
  */
object ConsentCopy:

  /** The version of the statements below; the consent ledger records it with every grant. */
  val NoticeVersion: Int = 1

  /** The locale of the statements below; the consent ledger records it with every grant (PRODUCT_SPEC R-LOC-11). */
  def Locale: String = Lang.current.code

  private val EnglishDay = DateTimeFormatter.ofPattern("d MMM yyyy", JavaLocale.ENGLISH)
  private val UkrainianDay = DateTimeFormatter.ofPattern("d MMMM yyyy", JavaLocale.forLanguageTag("uk"))

  /** The local date of a tap, as the receipt and the `/privacy` card print it: "13 Nov 2026", "13 листопада 2026". */
  def dateOf(at: Instant, zone: ZoneId): String =
    val format = Lang.current match
      case Lang.En => EnglishDay
      case Lang.Uk => UkrainianDay
    format.withZone(zone).format(at)

  def Agree: String = tr("I agree", "Погоджуюся")
  def NotNow: String = tr("Not now", "Не зараз")
  def Confirm: String = tr("Confirm", "Підтвердити")
  def GoBack: String = tr("Go back", "Назад")

  /** `safety.urgent` (BOT_UX BUX-MOOD-07): part of the mood consent text. */
  def SafetyUrgent: String = tr(
    "Dosegram cannot respond to emergencies. If you need urgent help, contact your local emergency number.",
    "Dosegram не може реагувати на надзвичайні ситуації. Якщо вам потрібна термінова допомога, зателефонуйте " +
      "на місцевий номер екстреної служби."
  )

  /** What one purpose covers, in the forms the two stages and the receipt use; `later` is what stays off after "Not
    * now", and `stopped` is what a withdrawal does, sentence by sentence.
    */
  private final case class Wording(mine: String, yours: String, short: String, later: String, stopped: String)

  /** A withdrawal keeps the stored records unless the user deletes them (BUX-CONS-06, ROADMAP G2.17). */
  private def RecordsKept: String = tr(
    "The records already stored are kept unless you delete them.",
    "Уже збережені записи лишаються, якщо ви їх не видалите."
  )

  private def wording(purpose: ConsentPurpose): Wording =
    Lang.current match
      case Lang.En => english(purpose)
      case Lang.Uk => ukrainian(purpose)

  private def ukrainian(purpose: ConsentPurpose): Wording =
    purpose match
      case ConsentPurpose.Medication =>
        Wording(
          "зберігає та обробляє мої записи про ліки: ліки, розклади, нагадування та дози, які я записую",
          "може зберігати та обробляти ваші записи про ліки: ліки, розклади, нагадування та дози, які ви записуєте",
          "записи про ліки",
          "Облік ліків лишається вимкненим: ліки, розклади й дози не записуються, нагадування не надсилаються. " +
            "Надішліть /meds, щоб увімкнути його пізніше.",
          "Нагадування зупинено: усі розклади призупинено, усі відкриті дози скасовано. Нічого нового не " +
            "записується, а записи про ліки не показуються ні в цьому чаті, ні в застосунку. " + RecordsKept +
            " Якщо пізніше ви знову погодитеся, відновіть кожен розклад у /meds."
        )
      case ConsentPurpose.Hydration =>
        Wording(
          "зберігає та обробляє мої записи про випиту воду",
          "може зберігати та обробляти ваші записи про випиту воду",
          "записи про воду",
          "Облік води лишається вимкненим. Надішліть /water, щоб увімкнути його пізніше.",
          "Нічого нового не записується, а записи про воду не показуються ні в цьому чаті, ні в застосунку. " +
            RecordsKept
        )
      case ConsentPurpose.Mood =>
        Wording(
          s"зберігає та обробляє мої записи про настрій. $SafetyUrgent",
          s"може зберігати та обробляти ваші записи про настрій. $SafetyUrgent",
          "записи про настрій",
          "Позначки настрою лишаються вимкненими. Надішліть /mood, щоб увімкнути їх пізніше.",
          "Нічого нового не записується, а записи про настрій не показуються ні в цьому чаті, ні в застосунку. " +
            RecordsKept
        )
      case ConsentPurpose.Sleep =>
        Wording(
          "зберігає та обробляє мої записи про сон: час і оцінки, які я вводжу",
          "може зберігати та обробляти ваші записи про сон: час і оцінки, які ви вводите",
          "записи про сон",
          "Облік сну лишається вимкненим. Надішліть /sleep, щоб увімкнути його пізніше.",
          "Нічого нового не записується, а записи про сон не показуються ні в цьому чаті, ні в застосунку. " +
            RecordsKept
        )
      case ConsentPurpose.Nutrition =>
        Wording(
          "зберігає та обробляє мої записи про їжу: тип прийому їжі, час і мої нотатки",
          "може зберігати та обробляти ваші записи про їжу: тип прийому їжі, час і ваші нотатки",
          "записи про їжу",
          "Облік їжі лишається вимкненим. Надішліть /food, щоб увімкнути його пізніше.",
          "Нічого нового не записується, а записи про їжу не показуються ні в цьому чаті, ні в застосунку. " +
            RecordsKept
        )
      case ConsentPurpose.CrossDomainPatterns =>
        Wording(
          "порівнює мої записи з різних розділів, наприклад воду й настрій",
          "може порівнювати ваші записи з різних розділів, наприклад воду й настрій",
          "порівняння між розділами",
          "Порівняння між розділами лишаються вимкненими.",
          "Відтепер записи з різних розділів не порівнюються. Самі записи лишаються без змін."
        )
      case ConsentPurpose.ImagesInChat =>
        Wording(
          "надсилає в цей чат зображення, створені з моїх записів. Вони зберігаються в хмарі месенджера й у цьому " +
            "чаті на всіх пристроях, і Dosegram не може згодом їх відкликати",
          "може надсилати в цей чат зображення, створені з ваших записів. Вони зберігаються в хмарі месенджера й у " +
            "цьому чаті на всіх пристроях, і Dosegram не може згодом їх відкликати",
          "зображення в цьому чаті",
          "Зображення лишаються вимкненими. Усі цифри й далі надходять текстом.",
          "Нові зображення не надсилаються: усі цифри надходять текстом. Зображення, що вже є в цьому чаті, " +
            "лишаються там, доки ви їх не видалите."
        )

  private def english(purpose: ConsentPurpose): Wording =
    purpose match
      case ConsentPurpose.Medication =>
        Wording(
          "stores and processes my medication records: medications, schedules, reminders and the doses I record",
          "may store and process your medication records: medications, schedules, reminders and the doses you record",
          "medication records",
          "Medication tracking stays off: no medication, schedule or dose is recorded and no reminder is sent. " +
            "Send /meds to turn it on later.",
          "Reminders have stopped: every schedule is paused and every open dose is cancelled. Nothing new is " +
            "recorded, and medication records are not shown in this chat or in the app. " + RecordsKept +
            " If you agree again later, resume each schedule under /meds."
        )
      case ConsentPurpose.Hydration =>
        Wording(
          "stores and processes my water intake records",
          "may store and process your water intake records",
          "water records",
          "Water tracking stays off. Send /water to turn it on later.",
          s"Nothing new is recorded, and water records are not shown in this chat or in the app. $RecordsKept"
        )
      case ConsentPurpose.Mood =>
        Wording(
          s"stores and processes my mood records. $SafetyUrgent",
          s"may store and process your mood records. $SafetyUrgent",
          "mood records",
          "Mood check-ins stay off. Send /mood to turn them on later.",
          s"Nothing new is recorded, and mood records are not shown in this chat or in the app. $RecordsKept"
        )
      case ConsentPurpose.Sleep =>
        Wording(
          "stores and processes my sleep records: the times and ratings I enter",
          "may store and process your sleep records: the times and ratings you enter",
          "sleep records",
          "Sleep tracking stays off. Send /sleep to turn it on later.",
          s"Nothing new is recorded, and sleep records are not shown in this chat or in the app. $RecordsKept"
        )
      case ConsentPurpose.Nutrition =>
        Wording(
          "stores and processes my meal records: meal type, time and my notes",
          "may store and process your meal records: meal type, time and your notes",
          "meal records",
          "Meal tracking stays off. Send /food to turn it on later.",
          s"Nothing new is recorded, and meal records are not shown in this chat or in the app. $RecordsKept"
        )
      case ConsentPurpose.CrossDomainPatterns =>
        Wording(
          "compares my records across domains, for example water and mood",
          "may compare your records across domains, for example water and mood",
          "comparisons across domains",
          "Comparisons across domains stay off.",
          "No comparison across domains is made from now on. Your records stay as they are."
        )
      case ConsentPurpose.ImagesInChat =>
        Wording(
          "sends images made from my records into this chat. They stay in the messenger's cloud and in this chat " +
            "on every device, and Dosegram cannot recall them later",
          "may send images made from your records into this chat. They stay in the messenger's cloud and in this " +
            "chat on every device, and Dosegram cannot recall them later",
          "images in this chat",
          "Images stay off. Every figure is still sent as text.",
          "No new image is sent: every figure comes as text. Images already in this chat stay there until you " +
            "delete them."
        )

  /** Stage 1: the full statement. It is the text whose hash the ledger keeps. */
  def statement(purpose: ConsentPurpose): String =
    sentence(
      tr(s"I agree that Dosegram ${wording(purpose).mine}", s"Я погоджуюся, що Dosegram ${wording(purpose).mine}")
    )

  /** Stage 2 repeats the statement before the recording tap (BUX-CONS-02). */
  def confirmation(purpose: ConsentPurpose): String =
    tr(
      sentence(s"Please confirm: Dosegram ${wording(purpose).yours}") + " You can withdraw at any time with /privacy.",
      sentence(s"Підтвердьте, будь ласка: Dosegram ${wording(purpose).yours}") +
        " Відкликати згоду можна будь-коли через /privacy."
    )

  /** The receipt; `date` is the local date of the confirming tap. */
  def receipt(purpose: ConsentPurpose, date: String): String =
    tr(
      s"Consent recorded on $date: ${wording(purpose).short}. Privacy notice version $NoticeVersion. " +
        "Withdraw at any time with /privacy.",
      s"Згоду записано $date: ${wording(purpose).short}. Версія повідомлення про конфіденційність: $NoticeVersion. " +
        "Відкликати можна будь-коли через /privacy."
    )

  /** Declining names what stays off and how to turn it on later (BUX-CONS-08). */
  def declined(purpose: ConsentPurpose): String =
    tr(s"Okay. ${wording(purpose).later}", s"Гаразд. ${wording(purpose).later}")

  def privacyTitle: String = tr("Your consents:", "Ваші згоди:")
  def privacyNone: String = tr("No consent is recorded.", "Жодної згоди не записано.")

  def privacyLine(purpose: ConsentPurpose, date: String): String =
    tr(s"${wording(purpose).short.capitalize}: since $date", s"${wording(purpose).short.capitalize}: від $date")

  def withdrawLabel(purpose: ConsentPurpose): String =
    tr(s"Withdraw: ${wording(purpose).short}", s"Відкликати: ${wording(purpose).short}")

  /** States exactly what the withdrawal did (PRODUCT_SPEC R-IDC-10, BOT_UX BUX-CONS-06). */
  def withdrawn(purpose: ConsentPurpose): String =
    tr(
      s"Consent withdrawn: ${wording(purpose).short}. ${wording(purpose).stopped}",
      s"Згоду відкликано: ${wording(purpose).short}. ${wording(purpose).stopped}"
    )

  /** A medication wizard that finishes after the consent was withdrawn saves nothing and says so. */
  def medicationOff: String = tr(
    "Medication tracking is off, so nothing was saved. Send /meds to turn it on.",
    "Облік ліків вимкнено, тож нічого не збережено. Надішліть /meds, щоб його увімкнути."
  )

  /** `onb.notice` (BOT_UX BUX-CONS-01), the first card of onboarding. */
  def notice: List[String] =
    Lang.current match
      case Lang.En => EnglishNotice
      case Lang.Uk => UkrainianNotice

  private val UkrainianNotice: List[String] = List(
    "Вітаємо в Dosegram. Перш ніж почати, прочитайте, будь ласка, ось що.",
    "Dosegram зберігає те, що ви тут записуєте: ліки, розклади й дози, воду, настрій, сон. Це дані про здоров’я.",
    "Цей чат не має наскрізного шифрування: месенджер може читати повідомлення й зображення, які надсилає бот.",
    "Ваші записи зберігаються на сервері Dosegram. Тут їх бачите лише ви; люди, які обслуговують сервіс, мають " +
      "доступ до бази даних.",
    "Ви погоджуєтеся на кожен вид записів окремо й можете відкликати згоду будь-коли через /privacy. Там же " +
      "можна видалити збережені записи, а експортувати їх — через /export.",
    "Нагадування надсилаються без гарантії: якесь може запізнитися або не прийти. Dosegram не дає медичних порад.",
    "З чого хочете почати? Решту можна ввімкнути пізніше."
  )

  private val EnglishNotice: List[String] = List(
    "Welcome to Dosegram. Before you start, please read this.",
    "Dosegram stores what you record here: medications, schedules and doses, water, mood, sleep. This is health " +
      "data.",
    "This chat is not end-to-end encrypted: the messenger can read the messages and images the bot sends.",
    "Your records are kept on the Dosegram server. Only you see them here; the people who run the service can " +
      "access the database.",
    "You agree to each kind of record separately and can withdraw at any time with /privacy. You can also " +
      "delete your stored records there, and export them with /export.",
    "Reminders are best effort: one can be late or missing. Dosegram gives no medical advice.",
    "What do you want to start with? You can turn on the others later."
  )

  /** The domains onboarding offers; consent is asked for the chosen one only (BUX-CONS-03). */
  val onboardingPurposes: List[ConsentPurpose] =
    List(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood)

  def onboardingLabel(purpose: ConsentPurpose): String =
    purpose match
      case ConsentPurpose.Medication => tr("Medication", "Ліки")
      case ConsentPurpose.Hydration  => tr("Water", "Вода")
      case ConsentPurpose.Mood       => tr("Mood", "Настрій")
      case ConsentPurpose.Sleep | ConsentPurpose.CrossDomainPatterns | ConsentPurpose.ImagesInChat |
          ConsentPurpose.Nutrition =>
        wording(purpose).short.capitalize

  def onboardingChoiceHint: String = tr("Choose one of the buttons.", "Виберіть одну з кнопок.")
  def agreeHint: String =
    tr("Tap \"I agree\" to go on, or go back.", "Натисніть «Погоджуюся», щоб продовжити, або поверніться назад.")
  def confirmHint: String = tr(
    "Tap \"Confirm\" to record your consent, or go back.",
    "Натисніть «Підтвердити», щоб записати згоду, або поверніться назад."
  )

  private def sentence(text: String): String = if text.endsWith(".") then text else s"$text."

  def entries: List[CopyEntry] =
    List(
      CopyEntry("consent.agree", Agree),
      CopyEntry("consent.not_now", NotNow),
      CopyEntry("consent.confirm", Confirm),
      CopyEntry("consent.go_back", GoBack),
      CopyEntry("safety.urgent", SafetyUrgent),
      CopyEntry("privacy.title", privacyTitle),
      CopyEntry("privacy.none", privacyNone),
      CopyEntry("consent.medication.off", medicationOff),
      CopyEntry("onb.notice", notice.mkString(" ")),
      CopyEntry("onb.notice.hint", onboardingChoiceHint),
      CopyEntry("consent.agree.hint", agreeHint),
      CopyEntry("consent.confirm.hint", confirmHint)
    ) ++ onboardingPurposes.map(purpose =>
      CopyEntry(s"onb.notice.${purpose.id}", onboardingLabel(purpose))
    ) ++ ConsentPurpose.values.toList.flatMap: purpose =>
      List(
        CopyEntry(s"consent.${purpose.id}.stage1", statement(purpose)),
        CopyEntry(s"consent.${purpose.id}.stage2", confirmation(purpose)),
        CopyEntry(s"consent.${purpose.id}.receipt", receipt(purpose, "13 Nov 2026")),
        CopyEntry(s"consent.${purpose.id}.declined", declined(purpose)),
        CopyEntry(s"privacy.${purpose.id}.line", privacyLine(purpose, "13 Nov 2026")),
        CopyEntry(s"privacy.${purpose.id}.withdraw", withdrawLabel(purpose)),
        CopyEntry(s"privacy.${purpose.id}.withdrawn", withdrawn(purpose))
      )
end ConsentCopy
