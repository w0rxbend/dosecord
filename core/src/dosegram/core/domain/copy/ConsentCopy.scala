package dosegram.core.domain.copy

import dosegram.core.ports.ConsentPurpose

/** Consent copy (BOT_UX section 5.5 and the transcript of section 21.7; PRODUCT_SPEC R-IDC-6, R-IDC-7). The statement
  * is shown in the message at both stages, and the ledger stores its hash under [[NoticeVersion]]: a change of a
  * statement is a new version.
  */
object ConsentCopy:

  /** The version of the statements below; the consent ledger records it with every grant. */
  val NoticeVersion: Int = 1

  val Agree = "I agree"
  val NotNow = "Not now"
  val Confirm = "Confirm"
  val GoBack = "Go back"

  /** `safety.urgent` (BOT_UX BUX-MOOD-07): part of the mood consent text. */
  val SafetyUrgent =
    "Dosegram cannot respond to emergencies. If you need urgent help, contact your local emergency number."

  /** What one purpose covers, in the three forms the two stages and the receipt use. */
  private final case class Wording(mine: String, yours: String, short: String, later: String)

  private def wording(purpose: ConsentPurpose): Wording =
    purpose match
      case ConsentPurpose.Medication =>
        Wording(
          "stores and processes my medication records",
          "may store and process your medication records",
          "medication records",
          "Dose statistics stay off. Send /stats to turn them on later."
        )
      case ConsentPurpose.Hydration =>
        Wording(
          "stores and processes my water intake records",
          "may store and process your water intake records",
          "water records",
          "Water tracking stays off. Send /water to turn it on later."
        )
      case ConsentPurpose.Mood =>
        Wording(
          s"stores and processes my mood records. $SafetyUrgent",
          s"may store and process your mood records. $SafetyUrgent",
          "mood records",
          "Mood check-ins stay off. Send /mood to turn them on later."
        )
      case ConsentPurpose.CrossDomainPatterns =>
        Wording(
          "compares my records across domains, for example water and mood",
          "may compare your records across domains, for example water and mood",
          "comparisons across domains",
          "Comparisons across domains stay off."
        )
      case ConsentPurpose.ImagesInChat =>
        Wording(
          "sends images made from my records into this chat. They stay in the messenger's cloud and in this chat " +
            "on every device, and Dosegram cannot recall them later",
          "may send images made from your records into this chat. They stay in the messenger's cloud and in this " +
            "chat on every device, and Dosegram cannot recall them later",
          "images in this chat",
          "Images stay off. Every figure is still sent as text."
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

  def withdrawn(purpose: ConsentPurpose): String =
    s"Consent withdrawn: ${wording(purpose).short}. Nothing new is recorded and these records are no longer shown."

  private def sentence(text: String): String = if text.endsWith(".") then text else s"$text."

  val entries: List[CopyEntry] =
    List(
      CopyEntry("consent.agree", Agree),
      CopyEntry("consent.not_now", NotNow),
      CopyEntry("consent.confirm", Confirm),
      CopyEntry("consent.go_back", GoBack),
      CopyEntry("safety.urgent", SafetyUrgent),
      CopyEntry("privacy.title", privacyTitle),
      CopyEntry("privacy.none", privacyNone)
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
