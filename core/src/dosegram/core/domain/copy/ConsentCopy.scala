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
  val Locale: String = "en"

  private val Day = DateTimeFormatter.ofPattern("d MMM yyyy", JavaLocale.ENGLISH)

  /** The local date of a tap, as the receipt and the `/privacy` card print it. */
  def dateOf(at: Instant, zone: ZoneId): String = Day.withZone(zone).format(at)

  val Agree = "I agree"
  val NotNow = "Not now"
  val Confirm = "Confirm"
  val GoBack = "Go back"

  /** `safety.urgent` (BOT_UX BUX-MOOD-07): part of the mood consent text. */
  val SafetyUrgent =
    "Dosegram cannot respond to emergencies. If you need urgent help, contact your local emergency number."

  /** What one purpose covers, in the forms the two stages and the receipt use; `later` is what stays off after "Not
    * now", and `stopped` is what a withdrawal does, sentence by sentence.
    */
  private final case class Wording(mine: String, yours: String, short: String, later: String, stopped: String)

  /** No deletion of one domain exists yet (ROADMAP G2.17), so a withdrawal says that the stored records stay. */
  private val RecordsKept = "The records already stored are kept: this version cannot delete them yet."

  private def wording(purpose: ConsentPurpose): Wording =
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
  def statement(purpose: ConsentPurpose): String = sentence(s"I agree that Dosegram ${wording(purpose).mine}")

  /** Stage 2 repeats the statement before the recording tap (BUX-CONS-02). */
  def confirmation(purpose: ConsentPurpose): String =
    sentence(s"Please confirm: Dosegram ${wording(purpose).yours}") + " You can withdraw at any time with /privacy."

  /** The receipt; `date` is the local date of the confirming tap. */
  def receipt(purpose: ConsentPurpose, date: String): String =
    s"Consent recorded on $date: ${wording(purpose).short}. Privacy notice version $NoticeVersion. " +
      "Withdraw at any time with /privacy."

  /** Declining names what stays off and how to turn it on later (BUX-CONS-08). */
  def declined(purpose: ConsentPurpose): String = s"Okay. ${wording(purpose).later}"

  val privacyTitle = "Your consents:"
  val privacyNone = "No consent is recorded."

  def privacyLine(purpose: ConsentPurpose, date: String): String =
    s"${wording(purpose).short.capitalize}: since $date"

  def withdrawLabel(purpose: ConsentPurpose): String = s"Withdraw: ${wording(purpose).short}"

  /** States exactly what the withdrawal did (PRODUCT_SPEC R-IDC-10, BOT_UX BUX-CONS-06). */
  def withdrawn(purpose: ConsentPurpose): String =
    s"Consent withdrawn: ${wording(purpose).short}. ${wording(purpose).stopped}"

  /** A medication wizard that finishes after the consent was withdrawn saves nothing and says so. */
  val medicationOff = "Medication tracking is off, so nothing was saved. Send /meds to turn it on."

  /** `onb.notice` (BOT_UX BUX-CONS-01), the first card of onboarding. */
  val notice: List[String] = List(
    "Welcome to Dosegram. Before you start, please read this.",
    "Dosegram stores what you record here: medications, schedules and doses, water, mood. This is health data.",
    "This chat is not end-to-end encrypted: the messenger can read the messages and images the bot sends.",
    "Your records are kept on the Dosegram server. Only you see them here; the people who run the service can " +
      "access the database.",
    "You agree to each kind of record separately and can withdraw at any time with /privacy. Deleting stored " +
      "records and exporting them are not available in this version yet.",
    "Reminders are best effort: one can be late or missing. Dosegram gives no medical advice.",
    "What do you want to start with? You can turn on the others later."
  )

  /** The domains onboarding offers; consent is asked for the chosen one only (BUX-CONS-03). */
  val onboardingPurposes: List[ConsentPurpose] =
    List(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood)

  def onboardingLabel(purpose: ConsentPurpose): String =
    purpose match
      case ConsentPurpose.Medication                                        => "Medication"
      case ConsentPurpose.Hydration                                         => "Water"
      case ConsentPurpose.Mood                                              => "Mood"
      case ConsentPurpose.CrossDomainPatterns | ConsentPurpose.ImagesInChat => wording(purpose).short.capitalize

  val onboardingChoiceHint = "Choose one of the buttons."
  val agreeHint = "Tap \"I agree\" to go on, or go back."
  val confirmHint = "Tap \"Confirm\" to record your consent, or go back."

  private def sentence(text: String): String = if text.endsWith(".") then text else s"$text."

  val entries: List[CopyEntry] =
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
